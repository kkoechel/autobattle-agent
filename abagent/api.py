"""Thin client for the AutoBattle v1 API.

Stdlib only, so the droplet needs no pip install for the non-LLM half of the
agent. GET requests are not rate limited (api-docs.php "Rate limiting"), so
reads run at full speed; writes are throttled for regular accounts but the
agent's key is is_api_user, which is exempt.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://autobattle.online/api/v1"
SPEC = "https://autobattle.online/api/openapi.json"   # one level up, not under /v1
UA = "abagent/0.1 (+autobattle deck agent)"


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:400]}")
        self.status = status
        self.body = body


class Api:
    def __init__(self, key: str | None = None, base: str = BASE, timeout: int = 120):
        self.key = key or os.environ.get("AB_API_KEY") or ""
        if not self.key:
            raise SystemExit("AB_API_KEY is not set (export it or pass key=)")
        self.base = base.rstrip("/")
        self.timeout = timeout

    def _call(self, method: str, path: str, params: dict | None = None,
              body: dict | None = None, retries: int = 3):
        url = f"{self.base}/{path.lstrip('/')}"
        if params:
            url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.key}")
        req.add_header("User-Agent", UA)
        if data:
            req.add_header("Content-Type", "application/json")

        for attempt in range(retries):
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    return json.loads(r.read().decode())
            except urllib.error.HTTPError as e:
                payload = e.read().decode(errors="replace")
                # 429 carries Retry-After: 1. 5xx is worth one more try.
                if e.code == 429 and attempt < retries - 1:
                    time.sleep(float(e.headers.get("Retry-After", 1)) + 0.25)
                    continue
                if e.code >= 500 and attempt < retries - 1:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise ApiError(e.code, payload) from None
            except urllib.error.URLError:
                if attempt < retries - 1:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise
        raise AssertionError("unreachable")

    # --- reads -----------------------------------------------------------
    def me(self) -> dict:
        return self._call("GET", "/me")["user"]

    def cards(self, fields: str | None = None, playable: bool | None = None) -> list[dict]:
        """Full catalog. The default (no fields) is the only form that carries
        effects_json/stats_json, which the offline engine needs."""
        params = {}
        if fields:
            params["fields"] = fields
        if playable is not None:
            params["playable"] = 1 if playable else 0
        return self._call("GET", "/cards", params)["cards"]

    def cohort(self) -> dict:
        return self._call("GET", "/cohort")

    def next_closing_cohort(self) -> dict | None:
        """The open cohort that closes soonest, across ALL arenas.

        This is the one POST /cohort/register will act on. The endpoint takes
        no arena parameter -- it selects `ORDER BY c.closes_at ASC LIMIT 1`
        over every open cohort (api/v1/index.php route_cohort_register) -- so
        which arena you end up in is purely a function of when you call.
        """
        cs = self.cohort().get("open_cohorts") or []
        return min(cs, key=lambda c: c["closes_at"]) if cs else None

    def open_cohort_for(self, arena: str) -> dict | None:
        """The open (still-accepting) cohort for one arena, or None.

        /cohort returns `open_cohort` (the caller's default arena) plus
        `open_cohorts` for every arena, so pick from the list rather than
        assuming the singular field is the arena we want.
        """
        for c in self.cohort().get("open_cohorts") or []:
            if c.get("arena_slug") == arena:
                return c
        return None

    def meta_index(self) -> list[dict]:
        return self._call("GET", "/meta")["arenas"]

    def meta(self, arena: str) -> dict:
        """Latest finalized cohort for one arena: every deck's card list and
        battle plan, already shaped like the engine's slots[]."""
        return self._call("GET", "/meta", {"arena": arena})

    def results(self, arena: str | None = None, deck_id: int | None = None,
                limit: int = 25, before: str | None = None) -> dict:
        return self._call("GET", "/results", {
            "arena": arena, "deck_id": deck_id, "limit": limit, "before": before})

    def deck(self, deck_id: int) -> dict:
        return self._call("GET", f"/decks/{deck_id}")["deck"]

    def decks(self) -> list[dict]:
        return self._call("GET", "/decks")["decks"]

    def validator_version(self) -> dict:
        return self._call("GET", "/validator_version")

    # --- writes ----------------------------------------------------------
    def create_deck(self, name: str) -> dict:
        return self._call("POST", "/decks", body={"name": name})

    def update_deck(self, deck_id: int, cards: list[dict] | None = None,
                    battle_plan: dict | None = None, name: str | None = None) -> dict:
        body: dict = {}
        if name is not None:
            body["name"] = name
        if cards is not None:
            body["cards"] = cards
        if battle_plan is not None:
            body["battle_plan"] = battle_plan
        return self._call("PUT", f"/decks/{deck_id}", body=body)

    def register(self, deck_id: int, arena: str | None = None) -> dict:
        """Register a deck, optionally naming the arena.

        Without `arena` the server takes whichever cohort closes soonest
        across ALL arenas, so which format you enter depends on what second
        you called -- see register_when_targetable() in cli.py for the
        workaround that needs. With it (API >= 1.9.0) registration is durable:
        it writes user_arena_decks, so every future cohort in that arena
        includes the deck, and it validates the deck against that arena's
        rules instead of silently entering a format it cannot legally play.
        """
        body: dict = {"deck_id": deck_id}
        if arena:
            body["arena"] = arena
        return self._call("POST", "/cohort/register", body=body)

    def api_version(self) -> str:
        """The served OpenAPI version. Unauthenticated, and not under /v1."""
        req = urllib.request.Request(SPEC, method="GET")
        req.add_header("User-Agent", UA)
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return str(json.loads(r.read().decode())["info"]["version"])

    def api_paths(self) -> set[str]:
        req = urllib.request.Request(SPEC, method="GET")
        req.add_header("User-Agent", UA)
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return set(json.loads(r.read().decode())["paths"])

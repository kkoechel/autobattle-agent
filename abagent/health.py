"""Detect the ways these agents stall, because they stall silently.

Four separate stalls have now run for hours before anyone noticed, and every
one of them looked healthy from the outside: the timers fired, the services
exited zero, the logs said "success". What they did not say is that nothing
was happening.

  - cycles overran the timer, so systemd skipped firings; nothing errored
  - min_gain made stalling the normal path, so the agent held every cycle
  - the explorer's floor tracked its own incumbent and ratcheted downwards
  - mutation shells came from a store the explorers never populated, and
    "0 mutations of the top 0 decks" printed every run for its whole life

The shared shape is that absence of progress is invisible. An exception gets
logged, a crash gets a non-zero exit, but an agent doing nothing productive
looks exactly like an agent with nothing to do. So these checks read the
agent's own state files and assert that something is actually moving.

Each returns (ok, message). Nothing here fixes anything: a monitor that also
repairs hides the failure it was built to expose.
"""
from __future__ import annotations

import datetime
import json
import os


def _age_hours(ts: str | None) -> float | None:
    if not ts:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            t = datetime.datetime.strptime(str(ts)[:19], fmt)
        except ValueError:
            continue
        return (datetime.datetime.utcnow() - t).total_seconds() / 3600
    return None


def check_explorer(var: str, max_idle_h: float = 3.0) -> list[tuple[bool, str]]:
    """Is the explorer still finding and developing decks?"""
    out = []
    path = os.path.join(var, "explore.json")
    try:
        st = json.load(open(path))
    except (FileNotFoundError, ValueError):
        return [(False, f"{path} missing or unreadable — explorer has no state")]

    # The deck in the slot should change. If installed_at stops moving, the
    # agent is running and achieving nothing -- the exact failure that hid for
    # a day behind healthy-looking timers.
    age = _age_hours(st.get("installed_at"))
    moving = age is not None and age < max_idle_h
    out.append((moving,
                f"slot last changed {age:.1f}h ago" if age is not None
                else "slot has never been changed"))

    # An accumulation count, not a movement one, so it is only a failure when
    # the slot is ALSO stale. The history is legitimately empty right after a
    # deliberate reset -- clearing the copy-era floor wipes it -- and a check
    # that cries wolf then is worse than no check: this agent has stalled
    # silently five times, and the whole value of these is that a FAIL means
    # something.
    tried = st.get("tried") or {}
    ids = st.get("tried_ids") or []
    have = bool(ids) or bool(tried)
    out.append((have or moving,
                f"decks tried: {len(ids)} by list, {len(tried)} by name"
                + ("" if have else " (history reset; slot still moving)")))

    # A falling best score means the floor is not holding.
    best = float(st.get("best_screen") or 0)
    out.append((best > 0, f"best screen ever: {best:.1f}W"))
    cur = st.get("focus") or {}
    if cur:
        out.append((float(cur.get("screen") or 0) >= best * 0.6,
                    f"focus '{cur.get('name')}' at {cur.get('screen')}W "
                    f"vs best {best:.1f}W"))
    return out


def check_competitor(var: str, max_idle_h: float = 24.0) -> list[tuple[bool, str]]:
    out = []
    for name, label in (("cards.json", "card catalogue"),
                        ("meta_pure.json", "arena meta")):
        p = os.path.join(var, name)
        if not os.path.exists(p):
            out.append((False, f"{label} missing ({name})"))
            continue
        age = (datetime.datetime.now().timestamp() - os.path.getmtime(p)) / 3600
        out.append((age < 1.0, f"{label} refreshed {age:.1f}h ago"))
    sec = os.path.join(var, "second.json")
    if os.path.exists(sec):
        try:
            exp = json.load(open(sec)).get("pass_expiry")
            left = _age_hours(exp)
            days = (-left / 24) if left is not None else None
            out.append((days is not None and days > 3,
                        f"double-entry pass has {days:.0f} days left"
                        if days is not None else "pass expiry unknown"))
        except ValueError:
            out.append((False, "second.json unreadable"))
    return out


def report(checks: list[tuple[str, list[tuple[bool, str]]]]) -> int:
    bad = 0
    for label, results in checks:
        print(f"\n{label}")
        for ok, msg in results:
            print(f"  {'ok  ' if ok else 'FAIL'}  {msg}")
            bad += 0 if ok else 1
    print(f"\n{bad} problem(s)")
    return 1 if bad else 0

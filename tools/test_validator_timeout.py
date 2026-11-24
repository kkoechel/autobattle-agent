"""A validator call that never returns must end, and name the deck that did it.

Regression test for 2026-11-24. quill produced 13 of the box's 29 daily
systemd timeouts; a killed run had burned 15min 35s of CPU inside the Go
validator child while the Python parent sat in subprocess.run with one second
of its own and no timeout= argument. The payload it left behind carried the
pre-fix Clockwork Castle (supertype "Structure Relic", self-manifesting) in six
candidate slots at ten copies each.

Ryan fixed that card and deliberately did not bound board size in the engines,
so the next card like it will do the same thing. These tests are about the
harness surviving it, not about any one card.
"""
import glob, importlib.util, os, re, sys, tempfile, time, types

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from abagent.harness import Harness

FAILED = []


def check(name, cond, detail=""):
    print(("  ok   " if cond else "  FAIL ") + name + (("  -- " + detail) if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


def fake_validator(body: str) -> str:
    fd, path = tempfile.mkstemp(suffix=".sh")
    os.write(fd, body.encode())
    os.close(fd)
    os.chmod(path, 0o755)
    return path


def harness(validator, workdir, **env):
    for k, v in env.items():
        os.environ[k] = str(v)
    try:
        return Harness(validator, [], workdir=workdir)
    finally:
        for k in env:
            os.environ.pop(k, None)


def main():
    work = tempfile.mkdtemp()

    # 1. ONE flat cap, set per unit. It was first written as
    #    floor + matches/rate, and that calibration is what broke the
    #    coordinator -- a wall-clock budget cannot be derived from a throughput
    #    measurement taken on a different workload.
    import inspect
    h = harness(fake_validator("#!/bin/sh\nexit 0\n"), work,
                ABAGENT_VALIDATOR_TIMEOUT=1305)
    check("the cap is read from the environment", h.budget() == 1305.0,
          f"{h.budget()}")
    check("nothing can pass a match count into it",
          list(inspect.signature(h.budget).parameters) == [])

    #    An unconfigured importer must degrade to the behaviour from before the
    #    timeout existed (systemd killing the unit), never to a tighter cap than
    #    anyone measured. The worst LEGITIMATE call seen on the box is the
    #    coordinator's round-robin at ~480s wall on v4.75.
    hd = harness(fake_validator("#!/bin/sh\nexit 0\n"), work)
    check("the default errs long, not short",
          hd.budget() >= 3 * 480, f"default {hd.budget():.0f}s vs 3x480s")
    check("and clears the wall of the unit that regressed",
          hd.budget() > 1740, f"{hd.budget():.0f}s vs TimeoutStartSec 1740")

    # 2. a validator that never returns is stopped, and says so
    hangs = fake_validator("#!/bin/sh\nwhile :; do :; done\n")
    h = harness(hangs, work, ABAGENT_VALIDATOR_TIMEOUT=2)
    t0 = time.time()
    err = None
    try:
        h._run({"matches": [], "slots": [], "cards": {}})
    except RuntimeError as e:
        err = str(e)
    took = time.time() - t0
    check("a hanging validator raises instead of hanging", err is not None)
    check("it is stopped near its budget, not left running", took < 20,
          f"took {took:.1f}s")
    check("the message says it timed out", err and "TIMED OUT" in err, str(err)[:90])
    check("the payload is kept for reproduction",
          err and "payload kept at" in err, str(err)[:90])
    kept = [f for f in os.listdir(work) if f.endswith(".json")]
    check("and the file is really there", len(kept) == 1, f"{kept}")

    # 3. with the hunt enabled, a timeout rides the drop-crashers path
    import importlib, abagent.harness as H
    os.environ["ABAGENT_BISECT_TIMEOUTS"] = "1"
    importlib.reload(H)
    check("hunting is on when asked for", H._BISECT_TIMEOUTS is True)
    h = H.Harness(hangs, [], workdir=work)
    h.timeout_s = 2.0
    said = []
    out = h.evaluate([("solo", [1], {})],
                     [type("O", (), {"slot_id": "O0", "cards": [1],
                                     "battle_plan": {}})()],
                     [1], drop_crashers=True, log=said.append)
    check("the cycle survives a candidate that never finishes", out == {})
    check("the candidate is recorded, not swallowed",
          [lab for lab, _ in h.engine_failures] == ["solo"],
          str(h.engine_failures))
    check("the log says it never finishes, not that it crashed",
          any("NEVER FINISHES" in m for m in said), "; ".join(said)[:110])

    # 4. by default the hunt is OFF, because each bisect level pays the full
    #    timeout again. Proven on quill's kept payload: the 3,024 Castle
    #    matches never finished at all, so bisecting them would have spent
    #    multiples of the first probe against the run's deadline.
    os.environ.pop("ABAGENT_BISECT_TIMEOUTS", None)
    importlib.reload(H)
    check("hunting is off unless asked for", H._BISECT_TIMEOUTS is False)
    h2 = H.Harness(hangs, [], workdir=work)
    h2.timeout_s = 2.0
    raised = None
    try:
        h2.evaluate([("solo", [1], {})],
                    [type("O", (), {"slot_id": "O0", "cards": [1],
                                    "battle_plan": {}})()],
                    [1], drop_crashers=True, log=lambda m: None)
    except RuntimeError as e:
        raised = str(e)
    check("a timeout propagates rather than being quietly dropped",
          raised is not None and "TIMED OUT" in raised, str(raised)[:80])

    # 5. EVERY unit that can reach the harness caps a call under its own wall.
    #    The cap lives in the unit file, beside the TimeoutStartSec it is
    #    derived from, because that is the only place both numbers are visible
    #    at once. This check is here because the regression below was not a
    #    wrong number so much as a number set in one place for one caller.
    units = []
    for path in sorted(glob.glob(os.path.join(ROOT, "deploy", "*.service"))
                       + glob.glob(os.path.join(ROOT, "server", "deploy",
                                                "*.service"))):
        body = open(path).read()
        wall = re.search(r"^TimeoutStartSec=(\d+)\s*$", body, re.M)
        if not wall:
            continue
        execs = " ".join(re.findall(r"^ExecStart=.*$", body, re.M))
        reaches = "abagent.cli" in execs
        for script in re.findall(r"bin/(\w+\.py)", execs):
            f = os.path.join(ROOT, "server", "bin", script)
            reaches = reaches or (os.path.exists(f) and "harness" in open(f).read())
        if reaches:
            units.append((os.path.basename(path), int(wall.group(1)), body))

    check("the harness-running units were found", len(units) >= 9,
          f"{len(units)}: {[u[0] for u in units]}")
    for name, wall, body in units:
        cap = re.search(r"^Environment=ABAGENT_VALIDATOR_TIMEOUT=(\d+)\s*$",
                        body, re.M)
        check(f"{name} caps a validator call", cap is not None)
        if not cap:
            continue
        secs = int(cap.group(1))
        # Strictly under the supervisor's kill, or the cap never fires and the
        # unit dies with nothing saved -- the 900-vs-900 mistake in
        # abagent.service, which took five days to spot.
        check(f"{name}: {secs}s is under its {wall}s wall", secs < wall)
        # And not so far under that legitimate work is recorded as a hang,
        # which is the failure mode that is worse than the hang.
        check(f"{name}: and not arbitrarily tighter than that",
              0.70 * wall <= secs <= 0.80 * wall,
              f"{secs} vs three quarters of {wall} = {0.75 * wall:.0f}")
        dl = re.search(r"^Environment=ABAGENT_DEADLINE=(\d+)\s*$", body, re.M)
        if dl:
            check(f"{name}: and under the run's own deadline",
                  secs < int(dl.group(1)), f"{secs} vs {dl.group(1)}")

    # 6. THE REGRESSION. The coordinator imports this module from /opt/abagent
    #    and its round-robin call was not wrapped, so the first timeout exited
    #    1 and scored NOBODY: agentgame-round failed at 15:34, 16:04 and 16:34
    #    on 2026-11-24, having completed every half hour for the three days
    #    before. A round is scored from a field that moves; a round not scored
    #    at all is a hole in the season that cannot be back-filled.
    cards = os.path.join(work, "cards.json")
    open(cards, "w").write("[]")
    os.environ.update(AGENTGAME_TOKEN="t", AGENTGAME_ADMIN="a",
                      AGENTGAME_CARDS=cards, AGENTGAME_WORK=work)
    os.environ.pop("AGENTGAME_SEED0", None)
    spec = importlib.util.spec_from_file_location(
        "roundmod", os.path.join(ROOT, "server", "bin", "round.py"))
    rnd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(rnd)

    def deck(n):
        return {"player_id": n, "handle": f"p{n}", "deck_name": f"deck{n}",
                "revision": 1, "cards": [{"card_id": 1, "quantity": 100}],
                "battle_plan": {}}

    posted = {}

    def fake_call(path, payload=None, admin=False):
        if path == "/field":
            return {"decks": [deck(1), deck(2), deck(3)]}
        if path == "/next-round":
            return {"seed0": 1000, "round_no": 7, "season": 1}
        if path == "/round":
            posted.update(payload)
            return {"round_id": 99, "decks": len(payload["results"])}
        raise AssertionError(path)

    class FakeH:
        fail_all = False
        calls = 0

        def __init__(self, *a, **k):
            self.engine_failures = []

        def evaluate(self, cands, opps, seeds, **kw):
            FakeH.calls += 1
            if FakeH.fail_all or FakeH.calls == 1:
                raise RuntimeError("validator TIMED OUT after 1305s on 483 "
                                   "match(es); payload kept at /x/y.json")
            return {"me": types.SimpleNamespace(wins=1.0, losses=1.0, draws=0.0)}

    rnd.harness = types.SimpleNamespace(Harness=FakeH, Opponent=H.Opponent)
    rnd.call = fake_call
    rc = rnd.main()
    check("a deck that never finishes no longer kills the round", rc == 0,
          f"rc={rc}")
    ids = [r["player_id"] for r in posted.get("results") or []]
    check("the decks that did finish are still scored", sorted(ids) == [2, 3],
          str(ids))
    check("the one that did not has no row rather than a 0", 1 not in ids,
          str(posted.get("results")))
    check("and the RECORD says a deck went unscored, not just the journal",
          "unscored" in (posted.get("note") or ""), repr(posted.get("note")))
    check("naming it, so the gap can be chased later",
          "p1" in (posted.get("note") or ""), repr(posted.get("note")))

    #    But a round almost nobody could play must not be posted: standings
    #    moved by two surviving decks would read as a real result.
    posted.clear()
    FakeH.fail_all, FakeH.calls = True, 0
    rc = rnd.main()
    check("a round nobody could play is not posted at all",
          rc != 0 and not posted, f"rc={rc} posted={bool(posted)}")

    print()
    if FAILED:
        print("FAILED: " + ", ".join(FAILED)); sys.exit(1)
    print("all checks passed")


main()

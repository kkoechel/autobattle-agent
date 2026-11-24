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
import os, sys, tempfile, time
sys.path.insert(0, '/home/hhkk/Documents/autobattle-agent')

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

    # 1. the budget scales with the work, so bisection cannot blow the deadline
    h = harness(fake_validator("#!/bin/sh\nexit 0\n"), work,
                ABAGENT_VALIDATOR_RATE=40, ABAGENT_VALIDATOR_FLOOR=45)
    big, half = h.budget_for(8568), h.budget_for(4284)
    check("budget scales with match count", big > half,
          f"{big:.0f}s vs {half:.0f}s")
    check("halving the payload roughly halves the overage",
          abs((big - 45) / 2 - (half - 45)) < 1.0)
    check("a whole bisect costs about twice the first probe",
          sum(h.budget_for(8568 >> i) for i in range(5)) < 3 * big,
          f"{sum(h.budget_for(8568 >> i) for i in range(5)):.0f}s vs 3x{big:.0f}s")

    # 2. a validator that never returns is stopped, and says so
    hangs = fake_validator("#!/bin/sh\nwhile :; do :; done\n")
    h = harness(hangs, work, ABAGENT_VALIDATOR_RATE=40, ABAGENT_VALIDATOR_FLOOR=2)
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
    h.rate, h.floor_s = 40.0, 2.0
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
    h2.rate, h2.floor_s = 40.0, 2.0
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

    print()
    if FAILED:
        print("FAILED: " + ", ".join(FAILED)); sys.exit(1)
    print("all checks passed")


main()

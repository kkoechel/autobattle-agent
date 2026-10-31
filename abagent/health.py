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

    # Is the agent still CHOOSING? Not: is the slot still churning.
    #
    # The original check read installed_at alone, on the reasoning that a slot
    # which stops changing means an agent running and achieving nothing -- the
    # failure that hid for a day behind healthy-looking timers. That was right
    # while every cycle either rotated or did nothing.
    #
    # It stopped being sufficient on 2026-10-31, when rotation gained a guard
    # that refuses to install a candidate screening below what is already
    # there. vane declined a 13.5W candidate over a 13.7W incumbent and so
    # installed nothing -- the fix working -- and a run of those reads to the
    # old check as an agent achieving nothing.
    #
    # Written BEFORE that reading was available, not after: the four failures
    # standing when this was added were NOT caused by the guard, and the first
    # version of this comment wrongly said they were. flint, pike, rook and
    # sable had all last installed hours before the guard was deployed (19:58,
    # 17:34, 21:24 and 15:46 against a 00:12:33 deploy) and none had a
    # held_at at all. Two of them simply crossed the 3h threshold while the
    # work was going on. Their decks are plateaued, which is a real condition
    # this check is right to report.
    #
    # A deliberate hold is a decision, so held_at counts as movement. An agent
    # that has neither installed nor decided anything is the real stall, and
    # that is still caught. Keeping the two timestamps separate rather than
    # having cli.py touch installed_at matters: installed_at means "the live
    # deck changed", several other things read it, and overloading it to mean
    # "something happened" would lose the distinction this check needs.
    i_age = _age_hours(st.get("installed_at"))
    h_age = _age_hours(st.get("held_at"))
    ages = [a for a in (i_age, h_age) if a is not None]
    age = min(ages) if ages else None
    moving = age is not None and age < max_idle_h
    if age is None:
        msg = "slot has never been changed"
    elif h_age is not None and (i_age is None or h_age < i_age):
        msg = (f"slot deliberately held {h_age:.1f}h ago"
               + (f" ({st['held_reason']})" if st.get("held_reason") else ""))
    else:
        msg = f"slot last changed {i_age:.1f}h ago"
    out.append((moving, msg))

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

    best = float(st.get("best_screen") or 0)
    out.append((best > 0, f"best screen ever: {best:.1f}W"))

    # Compare the focus against ITS OWN recent screens, never against
    # best_screen.
    #
    # A screen is measured against whatever meta snapshot and seed block that
    # run drew, and the two move every ten minutes: one deck logged baselines
    # of 14.6, 11.0, 13.1 and 11.6 on four consecutive runs while every
    # refinement between them was a valid paired gain. best_screen is the
    # all-time MAXIMUM of that noisy quantity -- the winner's curse, now
    # pointed at the health check -- so a single honest run loses to it and
    # the check cries wolf. It fired on a deck that was improving.
    cur = st.get("focus") or {}
    if cur:
        hist = [float(x) for x in (cur.get("screens") or []) if x]
        now = float(cur.get("screen") or 0)
        if len(hist) >= 3:
            import statistics
            ref = statistics.median(hist[-6:])
            out.append((now >= ref * 0.6,
                        f"focus '{cur.get('name')}' at {now:.1f}W vs its own "
                        f"recent median {ref:.1f}W"))
        else:
            out.append((True, f"focus '{cur.get('name')}' at {now:.1f}W "
                              f"({len(hist)} screens recorded; need 3 to judge)"))
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

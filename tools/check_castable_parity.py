#!/usr/bin/env python3
"""Do castable.py's tables still match the main app's, key for key?

castable.py says of itself: "The reference implementation is
inc/deck_castability.php in the main repo [...] This is its port and must not
drift from it." That sentence has been false twice. Engine v4.58 added
opponent_gain_honor and opponent_gain_shield, and this port kept calling
honor_gained a proof for weeks afterwards. Engine v4.74 added eleven payment
keys, `pay_and` and the gravecast `pay`, and main's handoff had to ASK for the
port -- nothing here noticed.

Both times the drift was invisible because every downstream report said clean.
A missing producer is the dangerous direction: castable's verdict is reached by
finding NO producer in the deck, so a key it has not learned turns a live card
into a "dead" one, and the bot generator cuts cards on that verdict.

So this diffs the three tables mechanically instead of trusting a comment.
It needs the main repo checked out; without it the check is SKIPPED and says
so, because a check that passes when it cannot see the reference is the same
silence in a new costume.

    python3 tools/check_castable_parity.py [path/to/autobattle.online]
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from abagent import castable  # noqa: E402

DEFAULT_MAIN = os.environ.get("AB_MAIN_REPO",
                              str(Path.home() / "Documents" / "autobattle.online"))
FAILED: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    print(("  ok   " if cond else "  FAIL ") + name
          + (("  -- " + detail) if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


def entries(block: str) -> dict[str, str]:
    """Split a PHP array body into {'key': 'raw body'} by bracket depth."""
    out, i = {}, 0
    while True:
        m = re.compile(r"'([A-Za-z_]\w*)'\s*=>\s*\[").search(block, i)
        if not m:
            return out
        depth, j = 1, m.end()
        while j < len(block) and depth:
            if block[j] == "[":
                depth += 1
            elif block[j] == "]":
                depth -= 1
            j += 1
        out[m.group(1)] = block[m.end():j - 1]
        i = j


def const(php: str, name: str) -> dict[str, str]:
    m = re.search(r"const\s+" + name + r"\s*=\s*\[", php)
    if not m:
        raise SystemExit(f"could not find const {name} in the reference")
    depth, j = 1, m.end()
    while j < len(php) and depth:
        if php[j] == "[":
            depth += 1
        elif php[j] == "]":
            depth -= 1
        j += 1
    return entries(php[m.end():j - 1])


def strings(body: str) -> set[str]:
    """Bare quoted strings in a body, ignoring any nested keyed array."""
    flat = re.sub(r"'\w+'\s*=>\s*\[[^\]]*\]", "", body)
    return set(re.findall(r"'([a-z_]+)'", flat))


def sub(body: str, key: str) -> set[str]:
    m = re.search(r"'" + key + r"'\s*=>\s*\[([^\]]*)\]", body)
    return set(re.findall(r"'([a-z_]+)'", m.group(1))) if m else set()


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else DEFAULT_MAIN)
    ref = root / "inc" / "deck_castability.php"
    if not ref.is_file():
        print(f"SKIPPED: no reference at {ref}")
        print("castable.py's tables were NOT checked against the main app. Pass")
        print("the main repo's path, or set AB_MAIN_REPO.")
        return 0
    # Comments carry example key names; they are documentation, not table rows.
    php = re.sub(r"//[^\n]*", "", ref.read_text(encoding="utf-8"))
    print(f"reference: {ref}")

    print("\nDC_SELF_TALLY vs SELF_TALLY")
    php_self = const(php, "DC_SELF_TALLY")
    check("the same metrics, no more and no fewer",
          set(php_self) == set(castable.SELF_TALLY),
          f"only in php {set(php_self) - set(castable.SELF_TALLY)}; "
          f"only here {set(castable.SELF_TALLY) - set(php_self)}")
    for m in sorted(set(php_self) & set(castable.SELF_TALLY)):
        spec, body = castable.SELF_TALLY[m], php_self[m]
        for field in ("effects", "costs", "passives"):
            want, got = sub(body, field), set(spec.get(field, ()))
            check(f"{m}.{field}", want == got,
                  f"missing {sorted(want - got)}; extra {sorted(got - want)}")
        want_rs = (re.search(r"'requires_stat'\s*=>\s*'(\w+)'", body) or [None, None])[1]
        check(f"{m}.requires_stat", want_rs == spec.get("requires_stat"),
              f"php {want_rs!r} vs here {spec.get('requires_stat')!r}")

    print("\nDC_OPP_TALLY vs OPP_TALLY")
    php_opp = const(php, "DC_OPP_TALLY")
    check("the same metrics", set(php_opp) == set(castable.OPP_TALLY),
          f"only in php {set(php_opp) - set(castable.OPP_TALLY)}; "
          f"only here {set(castable.OPP_TALLY) - set(php_opp)}")
    for m in sorted(set(php_opp) & set(castable.OPP_TALLY)):
        want, got = strings(php_opp[m]), set(castable.OPP_TALLY[m])
        check(f"{m}", want == got,
              f"missing {sorted(want - got)}; extra {sorted(got - want)}")

    print("\nDC_STAT_GAIN vs STAT_GAIN")
    php_stat = const(php, "DC_STAT_GAIN")
    check("the same stats", set(php_stat) == set(castable.STAT_GAIN),
          f"only in php {set(php_stat) - set(castable.STAT_GAIN)}; "
          f"only here {set(castable.STAT_GAIN) - set(php_stat)}")
    for s in sorted(set(php_stat) & set(castable.STAT_GAIN)):
        spec, body = castable.STAT_GAIN[s], php_stat[s]
        for field in ("effects", "passives", "opp"):
            want, got = sub(body, field), set(spec.get(field, ()))
            check(f"{s}.{field}", want == got,
                  f"missing {sorted(want - got)}; extra {sorted(got - want)}")
        check(f"{s}.champion_melee",
              ("'champion_melee' => true" in body) == bool(spec.get("champion_melee")))

    print("\nBASELINE_METRICS and the chaos table")
    bl = re.search(r"const\s+DC_BASELINE_METRICS\s*=\s*\[([^\]]*)\]", php)
    if bl:
        check("baseline metrics", set(re.findall(r"'([a-z_]+)'", bl.group(1)))
              == set(castable.BASELINE_METRICS))
    ch = const_chaos = re.search(r"const\s+DC_CHAOS_EFFECTS\s*=\s*\[(.*?)\n\];", php, re.S)
    if ch:
        want = dict(re.findall(r"'(\w+)'\s*=>\s*'(\w+)'", ch.group(1)))
        check("chaos roll -> effect", want == castable.CHAOS_EFFECTS,
              f"php {want}\n        here {castable.CHAOS_EFFECTS}")

    print()
    if FAILED:
        print(f"{len(FAILED)} difference(s) from the reference:")
        for f in FAILED:
            print(f"  - {f}")
        return 1
    print("0 difference(s) from the reference")
    return 0


raise SystemExit(main(sys.argv))

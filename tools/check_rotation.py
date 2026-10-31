#!/usr/bin/env python3
"""Does a rotation have to beat the deck we already hold?

Before 2026-10-30 nothing asked. A candidate only had to clear
floor_frac * best_ever -- exploration slack, spent out of the LIVE slot -- so
an agent could and did install a deck it had just measured as worse than the
one already there.

ONE case below is a real measurement and the rest are constructed. That
distinction is the point: the first survey of this bug reported seven of ten
explorers "playing something other than their own best find", and it was an
artifact of keying on deck NAMES and of comparing two screen numbers from
different evaluate() calls. By card-list hash, the headline example (ember)
was playing exactly its own best deck under a second name.

Run: python3 tools/check_rotation.py
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from abagent.cli import keep_over_rotate

fails = []


def case(label, got, want):
    ok = got == want
    print(f"  {'ok  ' if ok else 'FAIL'}  {label}: {got!r}")
    if not ok:
        fails.append(f"{label}: got {got!r}, wanted {want!r}")


print("MEASURED — vane installed a deck it had just screened as worse:")
# vane, 2026-10-30 22:52: `rotating slot -> 'Rise of the Iron Yield' (12.8W
# screen, incumbent 13.5W)`. Both numbers from one evaluate() call, which is
# what makes this one evidence. Repeated at 12.7 over 12.6 on 10-31 01:01.
case("vane: 12.8W candidate vs 13.5W incumbent",
     keep_over_rotate(12.8, 13.5, False, None), (13.5, "incumbent"))
case("vane again, at the margin: 12.7 vs 12.6",
     keep_over_rotate(12.7, 12.6, False, None), (None, ""))

print("MEASURED — rook, where the stored numbers had the sign backwards:")
# Stored: focus 16.0W, best_screen 17.2W, i.e. "the held deck is better".
# On one screen: `focus 14.9W vs held 'Silence of the Hollow' 13.7W`.
case("rook: focus 14.9W vs held 13.7W, same screen",
     keep_over_rotate(14.9, None, False, 13.7), (None, ""))

print("CONSTRUCTED — a candidate far below a held deck:")
case("3.5W candidate vs 21.1W held best",
     keep_over_rotate(3.5, 3.5, False, 21.1), (21.1, "held best"))

print("rotation still happens when the candidate earns it:")
case("clearly better candidate rotates",
     keep_over_rotate(22.0, 13.5, False, 21.1), (None, ""))
case("a tie rotates — the screen cannot tell them apart",
     keep_over_rotate(21.1, 13.5, False, 21.1), (None, ""))
case("beats the incumbent with no held best",
     keep_over_rotate(14.0, 13.5, False, None), (None, ""))

print("an incumbent we are not allowed to keep gets no vote:")
case("copy incumbent, no held best -> anything rotates",
     keep_over_rotate(4.0, 19.0, True, None), (None, ""))
case("copy incumbent, held best still counts",
     keep_over_rotate(4.0, 19.0, True, 15.0), (15.0, "held best"))
case("copy incumbent, candidate beats the held best",
     keep_over_rotate(16.0, 19.0, True, 15.0), (None, ""))

print("the held best wins over the incumbent only when it is better:")
case("held best below the incumbent -> keep the incumbent",
     keep_over_rotate(5.0, 18.0, False, 12.0), (18.0, "incumbent"))
case("held best above the incumbent -> restore it",
     keep_over_rotate(5.0, 12.0, False, 18.0), (18.0, "held best"))

# The refine path calls the same predicate with no incumbent: the focus IS
# what is installed, so the only question is whether the held deck beats it.
print("the refine path (focus vs held best, no incumbent in play):")
case("a focus far behind its held deck",
     keep_over_rotate(3.5, None, False, 21.1), (21.1, "held best"))
case("a focus that is still ahead keeps refining",
     keep_over_rotate(18.0, None, False, 15.0), (None, ""))
case("a focus level with the held best keeps refining",
     keep_over_rotate(15.0, None, False, 15.0), (None, ""))
case("no held best -> nothing to fall back to",
     keep_over_rotate(3.5, None, False, None), (None, ""))

print("nothing to keep:")
case("no incumbent and no held best",
     keep_over_rotate(0.5, None, False, None), (None, ""))

print()
if fails:
    print(f"{len(fails)} failure(s)")
    for f in fails:
        print("  -", f)
    sys.exit(1)
print("0 failure(s)")

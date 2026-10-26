#!/usr/bin/env python3
"""Write the launcher icon set from one of the Paragon crops.

    tools/make_icon.py K1          head only
    tools/make_icon.py K2          head and the fan of cards

WHY A SCRIPT. There are eleven files across five densities plus a monochrome
layer, and getting one of them wrong shows up as a blurry icon on exactly the
phones you do not own. Generated from the same source crop every time, with a
NEAREST upscale because the art is pixel art and anything smoother turns the
hard edges to porridge.

The adaptive foreground is drawn into the SAFE ZONE -- the middle 66% that a
launcher is guaranteed not to crop. Masks vary by device and the circle is
the cruellest; a figure sized to the full canvas loses its hair to it.
"""
import sys
from PIL import Image, ImageDraw

RES = "android/app/src/main/res"
SRC = f"{RES}/drawable-nodpi/paragon_1.png"
INK = (0x12, 0x13, 0x1A, 255)
SAFE = 0.66

CROPS = {
    # Head. The bottom edge takes the jaw: cut at 52 it landed under the
    # mouth, which reads as a crop rather than a portrait.
    "K1": (36, 2, 88, 57),      # head
    "K2": (34, 2, 118, 68),     # head and cards
    "K3": (30, 2, 122, 92),     # upper body
}
FG = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def foreground(box: tuple, size: int) -> Image.Image:
    art = Image.open(SRC).convert("RGBA").crop(box)
    target = int(size * SAFE)
    s = target / max(art.size)
    art = art.resize((max(1, int(art.width * s)), max(1, int(art.height * s))),
                     Image.NEAREST)
    c = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return c


def legacy(box: tuple, size: int, round_mask: bool) -> Image.Image:
    """The pre-adaptive icon: background baked in, masked, for old launchers."""
    base = Image.new("RGBA", (size, size), INK)
    base.alpha_composite(foreground(box, size))
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if round_mask:
        d.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size - 1, size - 1),
                            radius=int(size * 0.18), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), m)
    return out


def main() -> int:
    which = (sys.argv[1] if len(sys.argv) > 1 else "K2").upper()
    if which not in CROPS:
        print(f"pick one of {', '.join(CROPS)}", file=sys.stderr)
        return 2
    box = CROPS[which]
    for dpi, size in FG.items():
        foreground(box, size).save(f"{RES}/mipmap-{dpi}/ic_launcher_foreground.png")
    for dpi, size in LEGACY.items():
        legacy(box, size, False).save(f"{RES}/mipmap-{dpi}/ic_launcher.png")
        legacy(box, size, True).save(f"{RES}/mipmap-{dpi}/ic_launcher_round.png")
    # THE THEMED LAYER IS LEFT ALONE, on purpose.
    #
    # A launcher tints it to a single colour, so whatever goes here loses
    # every internal edge. Flattening the Paragon to his own alpha produces a
    # featureless blob -- a head-shaped splat nobody could name. Keeping only
    # the dark outlines instead reads at 120px and turns to noise at 48,
    # which is the size that matters. The eye-and-shard mark that was drawn
    # for this is the one thing that survives the tint, and a themed icon is
    # an abstract mark anyway: the game's sigil rather than its hero.
    print(f"wrote the icon set from {which} {box}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

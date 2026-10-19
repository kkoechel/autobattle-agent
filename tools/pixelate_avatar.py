#!/usr/bin/env python3
"""Turn a card's artwork into a full-screen pixel-art backdrop.

WHY THIS WORKS AT ALL. The catalogue's art is the `vectorposter` style --
flat colour, hard edges, no gradients or texture. That is already most of the
way to pixel art, so the job is resampling and a palette, not stylisation. A
photographic source would need far more than this and would still look muddy.

WHAT IT PRODUCES. One portrait-aspect image per card, sized so the phone
scales it by a whole number: 180x320 upscales 6x to 1080x1920 with no
resampling blur, which is why the app draws it with FilterQuality.None. The
character fills the frame and its own scene becomes the background, so a
single source gives both halves of "a pixel avatar over a pixel background".
The dialogue box is expected to cover the bottom; nothing load-bearing is put
there.

THE CROP IS HAND-PICKED, NOT DETECTED. Face detection on stylised art is
unreliable -- a minotaur, a fairy and a toucan share no features a detector
is trained on -- and a wrong crop is instantly obvious, so the boxes below
were read off the images by eye. They are in source pixels and each one is
then expanded to the target aspect around its own centre and clamped.
"""
from __future__ import annotations

import argparse
import os
import sys

from PIL import Image

# Logical pixels, and a palette. Both were chosen by rendering the same card
# at every size that divides 1080x1920 exactly and looking at the results.
#
#   180x320 (6x)  too fine -- reads as a low-resolution illustration, not as
#                 pixel art, and sits oddly beside the hand-drawn 48x48 NPC
#   135x240 (8x)  chunky enough to read as pixel art while the minotaur still
#                 has an eye and a muzzle
#   108x192 (10x) unmistakably pixel art, but faces start to dissolve
#
# Sixteen colours is the SNES's own per-palette budget and is what the hand
# drawn portrait already uses, so the two share an idiom rather than merely
# a subject.
W, H = 135, 240
COLOURS = 16

# card id -> (box around the SUBJECT, in source pixels)
SUBJECTS = {
    "491": (600, 30, 1270, 780),     # minotaur: horns, head, shoulders, fists
    # Tighter than the others on purpose. The fairy is a slender figure in
    # a busy frame, and at the wider crop the gold swirls read as the
    # subject while she dissolved into them -- at this size a thin shape
    # needs more of the frame, not the same share of it.
    "722": (505, 115, 835, 600),     # head, wings and torso
    "947": (500, 110, 1190, 810),    # toucan wizard: crest, beak, robe, book
}


def expand_to_aspect(box, size, aspect):
    """Grow a subject box to the target aspect about its centre, then clamp.

    Grows rather than crops: the subject box is what must be visible, so the
    extra has to come from around it. Clamping can push the result off-centre
    at an edge, which is correct -- better an off-centre subject than one with
    a slice missing.
    """
    x0, y0, x1, y1 = box
    iw, ih = size
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    bw, bh = x1 - x0, y1 - y0
    if bw / bh > aspect:
        bh = bw / aspect
    else:
        bw = bh * aspect
    bw, bh = min(bw, iw), min(bh, ih)
    x0, y0 = cx - bw / 2, cy - bh / 2
    x0 = max(0, min(x0, iw - bw))
    y0 = max(0, min(y0, ih - bh))
    return (round(x0), round(y0), round(x0 + bw), round(y0 + bh))


def pixelate(src: str, box, w=W, h=H, colours=COLOURS) -> Image.Image:
    im = Image.open(src).convert("RGB")
    crop = im.crop(expand_to_aspect(box, im.size, w / h))
    # BOX, not LANCZOS. Lanczos rings at every hard edge and this art is all
    # hard edges -- the halo survives quantisation and reads as noise at the
    # size the pixels end up. Box averaging is what a chunky downsample wants.
    small = crop.resize((w, h), Image.BOX)
    # No dithering. Dither scatters single pixels through flat areas, which is
    # precisely the texture this style does not have, and at 6x upscale each
    # stray pixel is a visible 6x6 block.
    return small.quantize(colors=colours, method=Image.MEDIANCUT,
                          dither=Image.NONE).convert("RGB")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("sources", nargs="+", help="card art files named <id>_*.png")
    ap.add_argument("--out", required=True)
    ap.add_argument("--width", type=int, default=W)
    ap.add_argument("--height", type=int, default=H)
    ap.add_argument("--colours", type=int, default=COLOURS)
    ap.add_argument("--preview", type=int, default=0,
                    help="also write an Nx nearest upscale, to look at")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    for src in a.sources:
        base = os.path.basename(src)
        cid = base.split("_")[0]
        if cid not in SUBJECTS:
            print(f"  no subject box for {base}; skipping", file=sys.stderr)
            continue
        im = pixelate(src, SUBJECTS[cid], a.width, a.height, a.colours)
        name = base.rsplit(".", 1)[0]
        out = os.path.join(a.out, f"avatar_{name}.png")
        im.save(out)
        print(f"  {base} -> {os.path.basename(out)} "
              f"{im.size[0]}x{im.size[1]}, {len(im.getcolors(1 << 16))} colours")
        if a.preview:
            p = im.resize((im.size[0] * a.preview, im.size[1] * a.preview),
                          Image.NEAREST)
            p.save(os.path.join(a.out, f"preview_{name}.png"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

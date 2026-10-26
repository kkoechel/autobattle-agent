#!/usr/bin/env python3
"""Turn Paragon art and locale art into the app's pixel layers.

TWO LAYERS, TWO PALETTES. The Paragon is cut out and quantised ALONE, and the
locale is quantised alone behind it. That is not an implementation detail: 16
colours shared between a figure and its background is what turned the first
test piece into sixteen shades of brown, losing a green waistcoat and a cyan
spell entirely. Separated, a teal-and-crimson Paragon survives against both a
golden forest and a grey graveyard.

WHY MAGENTA. The art is generated on a flat chroma field. White was tried
first and is wrong, because white IS in the character -- outline, jacket,
card faces -- so a cut bleeds into the figure. A key colour that appears
nowhere on the subject cuts perfectly.

WHY FLOOD-FILL AND NOT A COLOUR TEST. A global "is this pixel magenta" test
punches holes anywhere the figure happens to contain the key. Flooding inward
from the edges only removes background that is CONNECTED to the edge, so an
enclosed magenta pixel is safe by construction.

WHY THE ALPHA IS HARDENED. A soft edge resampled to 135px becomes a fringe of
half-transparent pixels, and at 8x upscale each one is a visible block of
halo. The threshold makes the edge a decision rather than a gradient.

LOCALES ARE CARD ART. The catalogue is already drawn in the pixelart-b style
-- flat colour, hard edges -- so a locale needs resampling and a palette, not
stylisation. Over a thousand of them exist and none had to be drawn.
"""
from __future__ import annotations

import argparse
import sys

from PIL import Image

W, H = 135, 240
COLOURS = 16


def chroma_cut(path: str, tol: int = 110) -> Image.Image:
    """The subject, with the keyed background removed, cropped to its bounds."""
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    key = px[4, 4]

    def is_key(c) -> bool:
        # Generous on purpose: JPEG smears a hard chroma edge badly, and a
        # tight test leaves a magenta rind around the whole figure.
        return (c[0] > 150 and c[2] > 150 and c[1] < 120
                and abs(c[0] - key[0]) < tol and abs(c[2] - key[2]) < tol)

    alpha = Image.new("L", (w, h), 255)
    ad = alpha.load()
    seen = bytearray(w * h)
    stack = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    while stack:
        x, y = stack.pop()
        if x < 0 or y < 0 or x >= w or y >= h:
            continue
        i = y * w + x
        if seen[i]:
            continue
        seen[i] = 1
        if not is_key(px[x, y]):
            continue
        ad[x, y] = 0
        stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]

    box = alpha.getbbox()
    if box is None:
        sys.exit(f"{path}: the cut removed everything -- is it really chroma-keyed?")
    im = im.convert("RGBA")
    im.putalpha(alpha)
    return im.crop(box)


def paragon(path: str, w: int = W, h: int = H) -> Image.Image:
    """A cut-out figure, scaled to fill the frame's height."""
    cut = chroma_cut(path)
    cw, ch = cut.size
    tw = max(1, round(cw * (h / ch)))
    small = cut.resize((tw, h), Image.BOX)
    rgb = (small.convert("RGB")
           .quantize(colors=COLOURS, method=Image.MEDIANCUT, dither=Image.NONE)
           .convert("RGB"))
    rgb.putalpha(small.getchannel("A").point(lambda v: 255 if v > 140 else 0))
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.alpha_composite(rgb, ((w - tw) // 2, 0))
    return out


def locale(path: str, w: int = W, h: int = H) -> Image.Image:
    """Card art as a backdrop: cover the frame, centre-cropped."""
    b = Image.open(path).convert("RGB")
    bw, bh = b.size
    s = max(w / bw, h / bh)
    nw, nh = max(w, round(bw * s)), max(h, round(bh * s))
    b = b.resize((nw, nh), Image.BOX)
    b = b.crop(((nw - w) // 2, (nh - h) // 2, (nw - w) // 2 + w, (nh - h) // 2 + h))
    return b.quantize(colors=COLOURS, method=Image.MEDIANCUT,
                      dither=Image.NONE).convert("RGB")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("--out", required=True)
    ap.add_argument("--kind", choices=("paragon", "locale"), default="paragon")
    ap.add_argument("--width", type=int, default=W)
    ap.add_argument("--height", type=int, default=H)
    a = ap.parse_args()
    img = (paragon if a.kind == "paragon" else locale)(a.src, a.width, a.height)
    img.save(a.out)
    print(f"{a.out}  {img.size[0]}x{img.size[1]}  {a.kind}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

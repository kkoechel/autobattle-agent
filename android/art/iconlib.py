"""Cut the gold artwork off its JPEG background and lay it out as an adaptive icon.

The source is gold-on-ink, so alpha comes from distance to the background
colour rather than a chroma key: that keeps the antialiased edges as partial
alpha instead of a 1-bit staircase. RGB is left untouched, which would fringe
on a different backdrop -- it does not here, because the background LAYER is
the same ink the artwork was drawn on.
"""
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

INK = (0x12, 0x13, 0x1A)
CANVAS = 432          # 108dp at xxxhdpi
SAFE = 264            # 66dp: the circle Google guarantees is never masked away


def cut(path, lo=28, hi=90):
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    bg = im[:40, :40].reshape(-1, 3).mean(0)
    d = np.sqrt(((im - bg) ** 2).sum(2))
    a = np.clip((d - lo) / (hi - lo), 0, 1)
    rgba = np.dstack([im, a * 255]).astype(np.uint8)
    out = Image.fromarray(rgba, "RGBA")
    return out.crop(out.getbbox())


def fit_in_circle(art, diameter):
    """Largest scale at which no opaque pixel escapes the circle."""
    r = diameter / 2.0
    a = np.asarray(art)[:, :, 3] > 24
    ys, xs = np.where(a)
    h, w = a.shape
    # pixel offsets from the art's centre, in source pixels
    dx, dy = xs - (w - 1) / 2.0, ys - (h - 1) / 2.0
    reach = np.sqrt(dx * dx + dy * dy).max()
    return r / reach


def foreground(path, canvas=CANVAS, safe=SAFE):
    art = cut(path)
    s = fit_in_circle(art, safe)
    art = art.resize((max(1, round(art.width * s)), max(1, round(art.height * s))),
                     Image.LANCZOS)
    fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    fg.paste(art, ((canvas - art.width) // 2, (canvas - art.height) // 2), art)
    return fg


def mask_shape(size, kind):
    m = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    elif kind == "squircle":
        d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1),
                            radius=int(size * 4 * 0.30), fill=255)
    else:
        d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1),
                            radius=int(size * 4 * 0.08), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def render(fg, kind, size):
    """What the launcher actually shows: layers composited, then masked."""
    full = Image.new("RGBA", fg.size, INK + (255,))
    full.alpha_composite(fg)
    full = full.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(full, (0, 0), mask_shape(size, kind))
    return out


def cut_boost_cool(path, lo=6, hi=60, slate=(0x6A, 0x72, 0x8C)):
    """As cut(), but repaint the cool-toned strokes to a brighter slate.

    The three-cards source draws its two outer cards as outlines that peak at
    63 units from the background against the gold card's 250 -- visible at
    1024px and gone by 48. Since they carry the whole meaning of the icon
    (three decks, one chosen) they are lifted rather than lost. Only cool
    pixels are touched, so the gold card keeps its own colour.
    """
    im = np.asarray(Image.open(path).convert("RGB")).astype(float)
    bg = im[:40, :40].reshape(-1, 3).mean(0)
    d = np.sqrt(((im - bg) ** 2).sum(2))
    a = np.clip((d - lo) / (hi - lo), 0, 1)
    cool = im[:, :, 2] >= im[:, :, 0]
    rgb = im.copy()
    rgb[cool] = slate
    out = Image.fromarray(np.dstack([rgb, a * 255]).astype(np.uint8), "RGBA")
    return out.crop(out.getbbox())


def foreground_at(art, diameter, canvas=CANVAS):
    s = fit_in_circle(art, diameter)
    a = art.resize((max(1, round(art.width * s)), max(1, round(art.height * s))),
                   Image.LANCZOS)
    fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    fg.paste(a, ((canvas - a.width) // 2, (canvas - a.height) // 2), a)
    return fg

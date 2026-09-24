"""Generate every launcher asset from one source image.

Adaptive layers are 108dp; the artwork is fitted to the 72dp safe circle, which
is the largest size no launcher mask can clip. Legacy PNGs for API 24-25 are
cut from the same composite but cropped to that safe region first: nothing
masks them, so art sized for a mask would just look small.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from iconlib import cut, cut_boost_cool, foreground_at, mask_shape, INK, CANVAS
from PIL import Image
import numpy as np

SRC = sys.argv[1] if len(sys.argv) > 1 else "/home/hhkk/Downloads/agentapplogo.jpeg"
RES = "/home/hhkk/Documents/autobattle-agent/android/app/src/main/res"
BOOST = os.environ.get("BOOST_COOL") == "1"
SAFE_DIA = 288                      # 72dp

art = (cut_boost_cool if BOOST else cut)(SRC)
fg = foreground_at(art, SAFE_DIA)

# --- adaptive foreground, one per density (108dp) ---
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for d, m in DENS.items():
    p = f"{RES}/mipmap-{d}"
    os.makedirs(p, exist_ok=True)
    n = round(108 * m)
    fg.resize((n, n), Image.LANCZOS).save(f"{p}/ic_launcher_foreground.png")

# --- monochrome (Android 13 themed icons): alpha silhouette, no colour ---
a = np.asarray(fg)[:, :, 3]
mono = Image.fromarray(np.dstack([np.full_like(a, 255)] * 3 + [a]), "RGBA")
os.makedirs(f"{RES}/drawable", exist_ok=True)
mono.save(f"{RES}/drawable/ic_launcher_monochrome.png")

# --- legacy PNGs for API 24-25: no mask is applied, so crop to the safe
#     region first or the art reads as tiny on exactly the oldest devices ---
comp = Image.new("RGBA", (CANVAS, CANVAS), INK + (255,))
comp.alpha_composite(fg)
off = (CANVAS - SAFE_DIA) // 2
safe = comp.crop((off, off, off + SAFE_DIA, off + SAFE_DIA))
for d, m in DENS.items():
    n = round(48 * m)
    big = safe.resize((n * 4, n * 4), Image.LANCZOS)
    for name, shape in (("ic_launcher", "legacy"), ("ic_launcher_round", "circle")):
        out = Image.new("RGBA", (n * 4, n * 4), (0, 0, 0, 0))
        out.paste(big, (0, 0), mask_shape(n * 4, shape))
        out.resize((n, n), Image.LANCZOS).save(f"{RES}/mipmap-{d}/{name}.png")

# --- Play Store listing icon: 512x512, opaque, no transparency allowed ---
play = comp.resize((512, 512), Image.LANCZOS).convert("RGB")
play.save("/home/hhkk/Documents/autobattle-agent/android/play_icon_512.png")

print("foreground/monochrome/legacy/round written for", ", ".join(DENS))
print("play_icon_512.png written")

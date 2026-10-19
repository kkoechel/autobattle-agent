from PIL import Image, ImageDraw, ImageFilter
S = 48
SS = 4                                   # supersample, then nearest-downsample
# A 16-colour ramp, three tones per material, lit from the upper left.
P = dict(
    out =(20,16,26,255),                 # outline
    sk1 =(236,205,168,255), sk2=(206,168,130,255), sk3=(160,122,90,255),
    hd1 =(74,58,40,255),  hd2=(52,40,27,255),  hd3=(33,26,18,255),   # hood
    rb1 =(96,76,52,255),  rb2=(66,52,35,255),  rb3=(44,35,24,255),   # robe
    gl1 =(245,217,139,255), gl2=(217,164,65,255), gl3=(150,110,40,255),
    pa1 =(240,232,210,255), pa2=(206,195,168,255),
    eye =(40,54,72,255), wht=(248,246,240,255),
    emb =(206,106,92,255),
)

def px(d, x, y, c, k=SS):
    d.rectangle([x*k, y*k, x*k+k-1, y*k+k-1], fill=c)

def blob(d, pts, c):
    d.polygon([(x*SS, y*SS) for x, y in pts], fill=c)

def disc(d, cx, cy, rx, ry, c):
    d.ellipse([(cx-rx)*SS, (cy-ry)*SS, (cx+rx)*SS, (cy+ry)*SS], fill=c)

def portrait(eyes="open", brow=0, mouth="flat", glasses=True, quill=False,
             eye_col=None):
    im = Image.new("RGBA", (S*SS, S*SS), (0,0,0,0))
    d = ImageDraw.Draw(im)

    # --- shoulders and robe -------------------------------------------------
    blob(d, [(4,48),(6,40),(14,36),(34,36),(42,40),(44,48)], P['rb2'])
    blob(d, [(4,48),(6,40),(14,36),(24,36),(24,48)], P['rb1'])      # lit side
    blob(d, [(31,37),(42,40),(44,48),(34,48)], P['rb3'])            # shadow
    # collar
    blob(d, [(16,37),(24,43),(32,37),(30,36),(24,40),(18,36)], P['gl2'])
    px(d, 23, 43, P['gl1']); px(d, 24, 43, P['gl1'])

    # --- hood (behind the head) --------------------------------------------
    disc(d, 24, 21, 15, 16, P['hd2'])
    blob(d, [(9,22),(12,36),(36,36),(39,22)], P['hd2'])
    disc(d, 22, 20, 14, 15, P['hd1'])                               # lit edge
    disc(d, 24, 21, 13, 14, P['hd3'])                               # inner dark

    # --- head ---------------------------------------------------------------
    disc(d, 24, 22, 10, 11, P['sk2'])
    disc(d, 22, 21, 9, 10, P['sk1'])                                # lit
    blob(d, [(30,16),(34,22),(32,31),(26,33)], P['sk3'])            # shade side
    # jaw shadow under the hood lip
    blob(d, [(14,13),(34,13),(33,17),(15,17)], P['hd3'])
    # neck
    blob(d, [(20,31),(28,31),(28,37),(20,37)], P['sk3'])

    # --- face ---------------------------------------------------------------
    ec = eye_col or P['eye']
    if eyes == "open":
        for ex in (19, 27):
            px(d, ex, 22, P['wht']); px(d, ex+1, 22, P['wht'])
            px(d, ex+2, 22, P['sk3'])
            px(d, ex, 23, P['wht']); px(d, ex+1, 23, ec); px(d, ex+2, 23, ec)
    else:                                                            # closed
        for ex in (19, 27):
            for i in range(3): px(d, ex+i, 23, P['sk3'])
    # brows: y shifts with mood
    by = 20 + brow
    for ex in (19, 27):
        for i in range(3): px(d, ex+i, by, P['hd3'])
    # nose
    px(d, 23, 25, P['sk3']); px(d, 23, 26, P['sk2'])
    # mouth
    if mouth == "flat":
        for i in range(4): px(d, 21+i, 28, P['sk3'])
    elif mouth == "open":
        for i in range(3): px(d, 22+i, 28, P['out'])
        for i in range(3): px(d, 22+i, 29, P['sk3'])
    elif mouth == "smile":
        px(d, 21, 28, P['sk3']); px(d, 22, 29, P['sk3'])
        px(d, 23, 29, P['sk3']); px(d, 24, 29, P['sk3']); px(d, 25, 28, P['sk3'])
    elif mouth == "frown":
        px(d, 21, 29, P['sk3']); px(d, 22, 28, P['sk3'])
        px(d, 23, 28, P['sk3']); px(d, 24, 28, P['sk3']); px(d, 25, 29, P['sk3'])

    # --- spectacles ---------------------------------------------------------
    if glasses:
        for ex in (18, 26):
            for i in range(4):
                px(d, ex+i, 21, P['gl2']); px(d, ex+i, 24, P['gl2'])
            px(d, ex, 22, P['gl2']); px(d, ex, 23, P['gl2'])
            px(d, ex+3, 22, P['gl2']); px(d, ex+3, 23, P['gl2'])
        px(d, 22, 22, P['gl3']); px(d, 23, 22, P['gl3'])            # bridge
        px(d, 18, 22, P['gl1'])                                     # glint

    if quill:
        for i, (qx, qy) in enumerate([(38,30),(39,29),(40,28),(41,27),(42,26)]):
            px(d, qx, qy, P['pa1'] if i % 2 else P['pa2'])
        px(d, 37, 31, P['gl2'])

    small = im.resize((S, S), Image.NEAREST)

    # --- outline: one dark pixel around the silhouette ----------------------
    a = small.split()[3].point(lambda v: 255 if v > 128 else 0)
    grown = a.filter(ImageFilter.MaxFilter(3))
    out = Image.new("RGBA", (S, S), (0,0,0,0))
    out.paste(P['out'], (0,0), grown)
    out.paste(small, (0,0), small)
    return out

FRAMES = dict(
    idle         = dict(),
    asking       = dict(brow=-1, mouth="open"),
    working      = dict(eyes="closed", mouth="flat", quill=True),
    pleased      = dict(brow=-1, mouth="smile"),
    disappointed = dict(brow=1, mouth="frown"),
    stuck        = dict(brow=1, mouth="frown", eye_col=P['emb']),
)
OUT = "/home/hhkk/Documents/autobattle-agent/android/app/src/main/res/drawable-nodpi"
sheet = Image.new("RGBA", (S*len(FRAMES), S), (0,0,0,0))
for i, (n, kw) in enumerate(FRAMES.items()):
    im = portrait(**kw); im.save(f"{OUT}/npc_{n}.png"); sheet.paste(im, (i*S, 0))
sheet.resize((S*len(FRAMES)*6, S*6), Image.NEAREST).save(
    "/tmp/claude-1000/-home-hhkk-Documents-vsls/ce60e290-01d6-432c-aad1-724c6f8d9ee1/scratchpad/sheet.png")
print("drawn", list(FRAMES))

"""Generate the RavTube logo set (variant B: red play tile with the Rav head).

Renders the RavLauncher logo paths (ravlogo.svg) with Pillow - no SVG renderer
needed - on the RavLauncher night gradient, and writes every icon, banner and
header logo the app uses into smarttubetv/src/*/res/mipmap-nodpi* and images/.

Usage (needs Pillow):  python3 images/make_logo.py
Fonts: Lexend Exa from ../RavLauncher (falls back to Pillow's default font).
"""
import re, math, sys, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO_ROOT, "build", "logo-preview"); os.makedirs(OUT, exist_ok=True)
FONT = os.path.join(REPO_ROOT, "..", "RavLauncher", "launcher", "app", "src", "main", "res", "font", "lexend_exa.ttf")
INNER = "M250.04,304.81c-1.18,0-2.36-0.38-3.35-1.14l-34.75-26.67c-1.36-1.04-2.15-2.65-2.15-4.36v-62.92h-38.62c-3.04,0-5.5-2.46-5.5-5.5s2.46-5.5,5.5-5.5h44.12c3.04,0,5.5,2.46,5.5,5.5v65.71l29.25,22.45l29.25-22.45v-65.71c0-3.04,2.46-5.5,5.5-5.5h44.12c3.04,0,5.5,2.46,5.5,5.5s-2.46,5.5-5.5,5.5h-38.62v62.92c0,1.71-0.79,3.32-2.15,4.36l-34.75,26.67C252.4,304.43,251.22,304.81,250.04,304.81z"
OUTER = "M250.04,387.95c-1.18,0-2.36-0.38-3.35-1.14L103.63,277.01c-1.36-1.04-2.15-2.65-2.15-4.36V89.22c0-2.09,1.19-4,3.06-4.93c1.88-0.93,4.12-0.71,5.78,0.56l62.72,47.98h154.01l62.72-47.98c1.66-1.27,3.9-1.49,5.78-0.56c1.88,0.93,3.06,2.84,3.06,4.93v183.42c0,1.71-0.79,3.32-2.15,4.36L253.39,386.81C252.4,387.57,251.22,387.95,250.04,387.95zM112.48,269.93l137.56,105.59L387.6,269.93V100.36l-55.35,42.35c-0.96,0.73-2.13,1.13-3.34,1.13H171.18c-1.21,0-2.38-0.4-3.34-1.13l-55.35-42.35V269.93z"

def parse(d):
    toks = re.findall(r"[MmLlHhVvCcSsZz]|-?\d*\.?\d+(?:e-?\d+)?", d)
    subs, cur, x, y, sx, sy, lc = [], [], 0, 0, 0, 0, None
    i = 0; cmd = None
    def num():
        nonlocal i; v = float(toks[i]); i += 1; return v
    def cubic(p0, p1, p2, p3):
        for k in range(1, 25):
            t = k / 24; mt = 1 - t
            cur.append((mt**3*p0[0]+3*mt*mt*t*p1[0]+3*mt*t*t*p2[0]+t**3*p3[0], mt**3*p0[1]+3*mt*mt*t*p1[1]+3*mt*t*t*p2[1]+t**3*p3[1]))
    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]): cmd = toks[i]; i += 1
        if cmd in "Zz":
            if cur: subs.append(cur); cur = []
            x, y = sx, sy; lc = None; continue
        rel = cmd.islower(); c = cmd.upper()
        if c == "M":
            if cur: subs.append(cur)
            nx, ny = num(), num()
            x, y = (x+nx, y+ny) if rel else (nx, ny); sx, sy = x, y; cur = [(x, y)]; cmd = "l" if rel else "L"; lc = None
        elif c == "L":
            nx, ny = num(), num(); x, y = (x+nx, y+ny) if rel else (nx, ny); cur.append((x, y)); lc = None
        elif c == "H":
            nx = num(); x = x+nx if rel else nx; cur.append((x, y)); lc = None
        elif c == "V":
            ny = num(); y = y+ny if rel else ny; cur.append((x, y)); lc = None
        elif c == "C":
            a = [num() for _ in range(6)]
            if rel: a = [a[0]+x, a[1]+y, a[2]+x, a[3]+y, a[4]+x, a[5]+y]
            cubic((x, y), (a[0], a[1]), (a[2], a[3]), (a[4], a[5])); lc = (a[2], a[3]); x, y = a[4], a[5]
        elif c == "S":
            a = [num() for _ in range(4)]
            if rel: a = [a[0]+x, a[1]+y, a[2]+x, a[3]+y]
            c1 = (2*x-lc[0], 2*y-lc[1]) if lc else (x, y)
            cubic((x, y), c1, (a[0], a[1]), (a[2], a[3])); lc = (a[0], a[1]); x, y = a[2], a[3]
    if cur: subs.append(cur)
    return subs

SS = 4  # supersampling
def mask_paths(size, paths, box, scale_to, offset, evenodd=True):
    """Render svg paths (in svg coords; box=(x0,y0,x1,y1) of drawing) scaled to height scale_to px at offset."""
    W, H = size
    m = Image.new("L", (W*SS, H*SS), 0); dr = ImageDraw.Draw(m)
    x0, y0, x1, y1 = box; s = scale_to / (y1 - y0)
    for d in paths:
        subs = parse(d)
        for j, sp in enumerate(subs):
            pts = [((px - x0) * s * SS + offset[0]*SS, (py - y0) * s * SS + offset[1]*SS) for px, py in sp]
            dr.polygon(pts, fill=255 if j == 0 else 0)  # first subpath filled, later ones are holes
    return m

def down(m, W, H): return m.resize((W, H), Image.LANCZOS)

def gradient(W, H):
    # RavLauncher night gradient #080B18 -> #120F26 -> #1C0F24 (diagonal) + soft bokeh glows
    img = Image.new("RGB", (W, H))
    stops = [(0, (0x08,0x0B,0x18)), (0.5, (0x12,0x0F,0x26)), (1, (0x1C,0x0F,0x24))]
    px = img.load()
    for yy in range(H):
        for xx in range(W):
            t = (xx/W*0.6 + yy/H*0.4)
            for (a, ca), (b, cb) in zip(stops, stops[1:]):
                if a <= t <= b:
                    f = (t-a)/(b-a); px[xx, yy] = tuple(int(ca[k]+(cb[k]-ca[k])*f) for k in range(3)); break
    glow = Image.new("RGB", (W, H), 0); g = ImageDraw.Draw(glow)
    for (cx, cy, r, col) in [(0.18, 0.2, 0.45, (0xFF,0xB4,0x6B)), (0.85, 0.85, 0.5, (0xB5,0x9C,0xFF)), (0.9, 0.15, 0.3, (0xFF,0x7A,0x9C))]:
        R = r*min(W, H); g.ellipse([cx*W-R, cy*H-R, cx*W+R, cy*H+R], fill=tuple(int(c*0.16) for c in col))
    glow = glow.filter(ImageFilter.GaussianBlur(min(W, H)*0.18))
    return ImageChops.add(img, glow)

RED = (0xFF, 0x1E, 0x3C)
WHITE = (255, 255, 255)

def rounded_triangle(W, H, cx, cy, size, radius_frac=0.16):
    m = Image.new("L", (W*SS, H*SS), 0); d = ImageDraw.Draw(m)
    h = size; w = size * 0.92
    pts = [((cx - w*0.42)*SS, (cy - h/2)*SS), ((cx - w*0.42)*SS, (cy + h/2)*SS), ((cx + w*0.58)*SS, cy*SS)]
    d.polygon(pts, fill=255)
    r = size*radius_frac*SS
    m = m.filter(ImageFilter.GaussianBlur(r)).point(lambda v: 255 if v > 128 else 0)
    return m

def rounded_rect_mask(W, H, box, radius):
    m = Image.new("L", (W*SS, H*SS), 0); ImageDraw.Draw(m).rounded_rectangle([c*SS for c in box], radius=radius*SS, fill=255); return m

BOX = (101.48, 84.0, 398.6, 387.95)

def mark(W, H, variant, scale, cx=None, cy=None, tile_col=None):
    """Return RGBA layer with the RavTube mark."""
    cx = W/2 if cx is None else cx; cy = H/2 if cy is None else cy
    layer = Image.new("RGBA", (W, H), (0,0,0,0))
    hgt = scale; wdt = hgt * (BOX[2]-BOX[0])/(BOX[3]-BOX[1])
    off = (cx - wdt/2, cy - hgt/2)
    if variant == "A":   # white rav head, red play triangle as the face
        head = down(mask_paths((W, H), [OUTER], BOX, hgt, off), W, H)
        layer.paste(Image.new("RGBA", (W, H), WHITE+(255,)), (0,0), head)
        tri = down(rounded_triangle(W, H, cx + wdt*0.01, off[1] + hgt*0.53, hgt*0.34), W, H)
        layer.paste(Image.new("RGBA", (W, H), RED+(255,)), (0,0), tri)
    else:                # red play-button tile with the white rav inside, nose = play triangle
        pad = hgt*0.20
        rr = (cx - wdt/2 - pad*1.25, cy - hgt/2 - pad*0.75, cx + wdt/2 + pad*1.25, cy + hgt/2 + pad*0.75)
        tile = down(rounded_rect_mask(W, H, rr, hgt*0.30), W, H)
        layer.paste(Image.new("RGBA", (W, H), (tile_col or RED)+(255,)), (0,0), tile)
        head = down(mask_paths((W, H), [OUTER], BOX, hgt, off), W, H)
        layer.paste(Image.new("RGBA", (W, H), WHITE+(255,)), (0,0), head)
        tri = down(rounded_triangle(W, H, cx + wdt*0.01, off[1] + hgt*0.53, hgt*0.40), W, H)
        layer.paste(Image.new("RGBA", (W, H), WHITE+(255,)), (0,0), tri)
    return layer

def icon(variant, W=320, content=0.62, bg=True):
    img = gradient(W, W).convert("RGBA") if bg else Image.new("RGBA", (W, W), (0,0,0,0))
    s = W*content if variant == "A" else W*content*0.72
    img.alpha_composite(mark(W, W, variant, s))
    if bg:  # rounded corners like a TV app tile
        m = rounded_rect_mask(W, W, (0,0,W,W), W*0.12); img.putalpha(down(m, W, W))
    return img

def banner(variant, W=640, H=360):
    img = gradient(W, H).convert("RGBA")
    s = H*0.50 if variant == "A" else H*0.36
    img.alpha_composite(mark(W, H, variant, s, cx=W*0.27, cy=H*0.5))
    f = ImageFont.truetype(FONT, int(H*0.135)) if os.path.exists(FONT) else ImageFont.load_default(int(H*0.135))
    try: f.set_variation_by_axes([300])
    except Exception: pass
    d = ImageDraw.Draw(img)
    d.text((W*0.47, H*0.5), "RavTube", font=f, fill=WHITE, anchor="lm")
    return img


GREY=(0x22,0x22,0x26)
REPO = REPO_ROOT
def b_icon(W=320, content=0.62*0.80, margin=0.0):
    inner = int(W*(1-2*margin))
    ic = gradient(inner, inner).convert("RGBA")
    ic.alpha_composite(mark(inner, inner, "B", inner*content))
    ic.putalpha(down(rounded_rect_mask(inner, inner, (0,0,inner,inner), inner*0.12), inner, inner))
    out = Image.new("RGBA", (W, W), (0,0,0,0)); out.alpha_composite(ic, ((W-inner)//2, (W-inner)//2)); return out
def b_mark_only(S, col=None):
    im = Image.new("RGBA", (S, S), (0,0,0,0)); im.alpha_composite(mark(S, S, "B", S*0.62, tile_col=col)); return im
def b_mark_word(W, H, col=None):
    im = Image.new("RGBA", (W, H), (0,0,0,0))
    im.alpha_composite(mark(W, H, "B", H*0.40, cx=W*0.22, cy=H*0.5, tile_col=col))
    f = ImageFont.truetype(FONT, int(H*0.20)) if os.path.exists(FONT) else ImageFont.load_default(int(H*0.20))
    try: f.set_variation_by_axes([300])
    except Exception: pass
    ImageDraw.Draw(im).text((W*0.42, H*0.5), "RavTube", font=f, fill=WHITE, anchor="lm"); return im

def red_banner(W, H):
    """TV launcher banner: full-bleed red with the white Rav head + play triangle, no text."""
    img = Image.new("RGBA", (W, H), RED + (255,))
    hgt = H * 0.70; wdt = hgt * (BOX[2]-BOX[0])/(BOX[3]-BOX[1])
    cx, cy = W/2, H/2; off = (cx - wdt/2, cy - hgt/2)
    head = down(mask_paths((W, H), [OUTER], BOX, hgt, off), W, H)
    img.paste(Image.new("RGBA", (W, H), WHITE+(255,)), (0,0), head)
    tri = down(rounded_triangle(W, H, cx + wdt*0.01, off[1] + hgt*0.53, hgt*0.40), W, H)
    img.paste(Image.new("RGBA", (W, H), WHITE+(255,)), (0,0), tri)
    return img

def save(img, rel): 
    path=f"{REPO}/{rel}"; img.save(path, optimize=True); print("schreef", rel, img.size)
M="smarttubetv/src/main/res/mipmap-nodpi"
save(b_icon(), f"{M}/app_icon.png"); save(b_icon(), f"{M}/app_icon_alt.png")
save(red_banner(320, 180), f"{M}/app_banner.png")
save(b_mark_word(320, 180), f"{M}/app_logo.png")
save(b_mark_word(320, 180), f"{M}/app_logo_semi_red.png")
save(b_mark_word(320, 180, GREY), f"{M}/app_logo_semi_grey.png")
for fl in ("ststable", "stbeta", "stfdroid"):
    F=f"smarttubetv/src/{fl}/res/mipmap-nodpi"
    save(b_icon(), f"{F}/app_icon.png")
    save(b_icon(margin=0.10), f"{F}-v30/app_icon.png")
    save(red_banner(640, 360), f"{F}/app_banner.png")
    save(b_mark_only(180), f"{F}/app_logo.png")
    save(b_mark_only(180), f"{F}/app_logo_semi_red.png")
    save(b_mark_only(180, GREY), f"{F}/app_logo_semi_grey.png")
save(b_icon(320), "images/app_icon.png"); save(b_icon(320), "images/app_icon_downscaled.png"); save(red_banner(320, 180), "images/app_banner.png")
pv = gradient(560, 150).convert("RGBA")
for j, sz in enumerate((128, 96, 64, 48)): pv.alpha_composite(b_icon().resize((sz, sz), Image.LANCZOS), (20 + j*140, (150-sz)//2))
pv.save(f"{OUT}/preview_B_sizes.png")

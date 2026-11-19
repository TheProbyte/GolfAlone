"""Golf Alone style board: real 1:1 crops of today vs mockups of the options.

Mockups are drawn here in Python at the game's logical resolution (1 px = 6 screen px,
as on a 1080-wide Pixel), not by the engine. Usage: styleboard.py <shots_dir> <out.png>
"""
import math, re, sys
from PIL import Image, ImageDraw, ImageFont

SHOTS, OUT = sys.argv[1], sys.argv[2]
S = 6  # logical -> screen
W = 1080
H_FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 34)
B_FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 26)
SM_FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 21)
WF_FONT = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 15)
BG = (238, 234, 224)
INKC = (34, 30, 40)

def hexc(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

# ------------------------------------------------------------------ proposed palette
W0, W1, W2, W3, W4, W5 = map(hexc, ['#16213e', '#1d3a6b', '#24598f', '#2f86ac', '#5fbcc4', '#c9efe6'])
G0, G1, G2, G3, G4, G5, G6 = map(hexc, ['#173b34', '#23573f', '#2f7445', '#43924b', '#63ad50', '#8fc95a', '#c4e27a'])
S0, S1, S2, S3, S4 = map(hexc, ['#6e4b3e', '#9b6a4f', '#c69a6c', '#e3c48e', '#f5e6b8'])
K0, K1, K2, K3, K4 = map(hexc, ['#2a2438', '#4a4461', '#6f6a85', '#9a97a8', '#cfcbc6'])
D0, D1, D2, D3 = map(hexc, ['#3a2422', '#5f3a2c', '#8a5a3a', '#b88752'])
RED, RED_D = hexc('#e0483e'), hexc('#a8303a')
BALL, BALL_S, OUTL = (255, 255, 255), hexc('#c8d6e5'), hexc('#1d2b3a')
P0, P1, P2, INK, STAMP_R, STAMP_G = map(hexc, ['#f6eedb', '#e6d6b0', '#c9b48a', '#2a2333', '#c8453a', '#3f8a4c'])
NAVY0, NAVY1, NAVY2, NAVYE, CREAM, GOLD = map(hexc, ['#121c2a', '#1b2a3a', '#24384d', '#4d6f8a', '#f7f3e3', '#ffd866'])
RAMPS = [
    ('sea', [W0, W1, W2, W3, W4, W5]),
    ('grass', [G0, G1, G2, G3, G4, G5, G6]),
    ('sand', [S0, S1, S2, S3, S4]),
    ('stone', [K0, K1, K2, K3, K4]),
    ('wood', [D0, D1, D2, D3]),
]
DARKER = {}
for _, r in RAMPS:
    for i in range(1, len(r)): DARKER[r[i]] = r[i - 1]
DARKER[r[0]] = r[0]

def hsh(x, y, s):
    h = (x * 374761393 + y * 668265263 + s * 1442695041) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    h ^= h >> 16
    return (h & 0xffffff) / 16777216.0

# ------------------------------------------------------------------ fonts
def parse3x5():
    src = open('/home/user/GolfAlone/app/src/main/java/com/theprobyte/golfalone/render/PixelFont.kt').read()
    g = {}
    for ch, pat in re.findall(r"'(.)' to \"([.#/]+)\"", src):
        rows = pat.split('/')
        g[ch] = rows
    g['\\'] = g.get("'")
    return g

F3 = parse3x5()
F5 = {
    'A': ".###.|#...#|#...#|#####|#...#|#...#|#...#", 'B': "####.|#...#|#...#|####.|#...#|#...#|####.",
    'C': ".###.|#...#|#....|#....|#....|#...#|.###.", 'D': "####.|#...#|#...#|#...#|#...#|#...#|####.",
    'E': "#####|#....|#....|####.|#....|#....|#####", 'F': "#####|#....|#....|####.|#....|#....|#....",
    'G': ".###.|#...#|#....|#.###|#...#|#...#|.####", 'H': "#...#|#...#|#...#|#####|#...#|#...#|#...#",
    'I': "###|.#.|.#.|.#.|.#.|.#.|###", 'L': "#....|#....|#....|#....|#....|#....|#####",
    'N': "#...#|##..#|#.#.#|#..##|#...#|#...#|#...#", 'O': ".###.|#...#|#...#|#...#|#...#|#...#|.###.",
    'P': "####.|#...#|#...#|####.|#....|#....|#....", 'R': "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
    'S': ".####|#....|#....|.###.|....#|....#|####.", 'T': "#####|..#..|..#..|..#..|..#..|..#..|..#..",
    'U': "#...#|#...#|#...#|#...#|#...#|#...#|.###.", 'W': "#...#|#...#|#...#|#.#.#|#.#.#|##.##|#...#",
    'X': "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#", 'Y': "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..",
    'M': "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#",
    '1': ".#.|##.|.#.|.#.|.#.|.#.|###", '2': ".###.|#...#|....#|...#.|..#..|.#...|#####",
    '3': "####.|....#|....#|.###.|....#|....#|####.", '6': ".###.|#....|#....|####.|#...#|#...#|.###.",
    '/': "....#|...#.|...#.|..#..|.#...|.#...|#....", '!': "#|#|#|#|#|.|#", '*': ".|.|.|#|.|.|.",
    ' ': "...|...|...|...|...|...|...",
}
F5 = {k: v.split('|') for k, v in F5.items()}

def text(img, s, x, y, col, font, scale=S, shadow=None, gap=1):
    d = ImageDraw.Draw(img)
    cx = x
    for ch in s:
        g = font.get(ch.upper()) or font.get('?')
        if g is None: cx += 4 * scale; continue
        for ry, row in enumerate(g):
            for rx, c in enumerate(row):
                if c == '#':
                    if shadow: d.rectangle([cx + rx * scale, y + (ry + 1) * scale, cx + (rx + 1) * scale - 1, y + (ry + 2) * scale - 1], fill=shadow)
        for ry, row in enumerate(g):
            for rx, c in enumerate(row):
                if c == '#':
                    d.rectangle([cx + rx * scale, y + ry * scale, cx + (rx + 1) * scale - 1, y + (ry + 1) * scale - 1], fill=col)
        cx += (len(g[0]) + gap) * scale
    return cx

def twidth(s, font, scale=S, gap=1):
    return sum((len((font.get(c.upper()) or font['?'])[0]) + gap) * scale for c in s) - gap * scale

# ------------------------------------------------------------------ mock terrain (logical px)
PW, PH = 84, 64

def land_field(x, y):
    best = -9
    for cx, cy, rx, ry in [(42, 30, 34, 21), (20, 44, 16, 12), (64, 17, 16, 12)]:
        dx = (x + 0.5 - cx) / rx; dy = (y + 0.5 - cy) / ry
        best = max(best, 1 - math.sqrt(dx * dx + dy * dy))
    return best

def patch(three_quarter):
    img = Image.new('RGB', (PW, PH))
    px = img.load()
    land = [[land_field(x, y) > 0 for x in range(PW)] for y in range(PH)]
    # distance to the other kind (brute force, small)
    def dist(x, y, want):
        best = 99
        for yy in range(max(0, y - 14), min(PH, y + 15)):
            for xx in range(max(0, x - 14), min(PW, x + 15)):
                if land[yy][xx] == want:
                    best = min(best, math.hypot(xx - x, yy - y))
        return best
    green = lambda x, y: ((x + .5 - 62) / 12) ** 2 + ((y + .5 - 18) / 8) ** 2 < 1
    wall = lambda x, y: 16 <= x < 40 and 8 <= y < 16
    for y in range(PH):
        for x in range(PW):
            if not land[y][x]:
                d = dist(x, y, True)
                dith = (x + y) % 2
                c = W3 if d < 2.5 + dith else W2 if d < 6 + dith else W1 if d < 11 + dith else W0
                if d < 1.5: c = W5
                elif 3 <= d < 4 and (x // 2 + y) % 3 == 0: c = W4
                px[x, y] = c
                continue
            d = dist(x, y, False)
            if d < 2.5:
                c = S4 if d < 1.5 else S3
                if hsh(x // 3, y // 3, 5) < .08 and d > 1: c = S2
            elif d < 6.5 or y < 6:
                c = G3
            else:
                c = G5 if (y // 8) % 2 == 0 else G4
            if green(x, y):
                c = G6 if (x // 8) % 2 == 0 else G5
                if not green(x - 1, y) or not green(x + 1, y) or not green(x, y - 1) or not green(x, y + 1): c = G4
            px[x, y] = c
    # tufts in rough on a jittered grid (clusters, not noise)
    for gy in range(0, PH, 5):
        for gx in range(0, PW, 5):
            x = gx + int(hsh(gx, gy, 1) * 4); y = gy + int(hsh(gx, gy, 2) * 4)
            if 1 <= x < PW - 2 and 1 <= y < PH and px[x, y] == G3 and px[x + 2, y] == G3 and hsh(gx, gy, 3) < .55:
                px[x, y] = G2; px[x + 1, y - 1] = G5; px[x + 2, y] = G2; px[x + 1, y] = G2
    # a daisy or two on the fairway
    for (x, y) in [(46, 40), (30, 30)]:
        px[x, y] = P0; px[x, y + 1] = G3
    # island bank (3/4 view): a short face under south-facing shores
    if three_quarter:
        for y in range(PH - 1, -1, -1):
            for x in range(PW):
                if land[y][x]: continue
                k = next((i for i in (1, 2, 3) if y - i >= 0 and land[y - i][x]), None)
                if k is None: continue
                px[x, y] = [S2, S1, S0][k - 1]
                for j in (1, 2):  # shadow on the water at the foot of the bank
                    if y + j < PH and not land[y + j][x] and px[x, y + j] not in (S0, S1, S2):
                        px[x, y + j] = W1 if j == 1 else (W2 if (x + y) % 2 else px[x, y + j])
    # shadows: object mask offset (2,2), one ramp step darker, dithered edge
    def shadow_at(x, y, soft):
        if 0 <= x < PW and 0 <= y < PH:
            c = px[x, y]
            if soft and (x + y) % 2: return
            px[x, y] = DARKER.get(c, c)
    # wall
    for y in range(8 + 2, 16 + (3 if three_quarter else 2)):
        for x in range(16 + 2, 40 + 2):
            if not wall(x, y): shadow_at(x, y, x >= 40 or y >= 16 + (2 if three_quarter else 1))
    for y in range(8, 16):
        for x in range(16, 40):
            u, v = x - 16, y - 8
            if three_quarter:
                if v < 5:
                    c = K4 if v == 0 or u == 0 else K3
                    if v == 4: c = K2
                else:
                    c = K2 if (u % 6) else K1
                    if v == 7: c = K1
                if (u in (0, 23)) and v in (0,): c = K3
            else:
                c = K3
                if v == 0 or u == 0: c = K4
                if v == 7 or u == 23: c = K1
                if v in (3,) and u % 6 != 2: c = K2
            px[x, y] = c
    # palm: shadow, trunk, canopy (outlined)
    bx, by = 28, 44
    for y in range(by - 2, by + 4):
        for x in range(bx - 2, bx + 14):
            if ((x - (bx + 6)) / 8) ** 2 + ((y - (by + 1)) / 3) ** 2 < 1: shadow_at(x, y, ((x - (bx + 6)) / 8) ** 2 + ((y - (by + 1)) / 3) ** 2 > .55)
    for i in range(10):
        x = bx - round(i * .3); y = by - i
        px[x, y] = D3 if i % 3 else D2; px[x + 1, y] = D1
    tx, ty = bx - 3, by - 10
    fr = [(-7, -1), (7, -1), (-6, 3), (6, 3), (-3, -4), (3, -4), (-1, 4), (2, 4)]
    pts = set()
    for dx, dy in fr:
        n = max(abs(dx), abs(dy))
        for s in range(n + 1):
            k = s / n
            pts.add((tx + round(dx * k), ty + round(dy * k + k * k * 2.5)))
    for (x, y) in list(pts):
        for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1), (0, 2)):
            if (x + ox, y + oy) not in pts: px[x + ox, y + oy] = G0
    for (x, y) in pts: px[x, y] = G3
    for (x, y) in pts:
        if (x, y - 1) not in pts: px[x, y] = G5
    px[tx, ty] = D1; px[tx + 1, ty] = D1
    # cup and flag
    cx, cy = 62, 19
    for (x, y) in [(cx - 1, cy - 1), (cx, cy - 1), (cx + 1, cy - 1), (cx - 2, cy), (cx - 1, cy), (cx, cy), (cx + 1, cy), (cx + 2, cy), (cx - 1, cy + 1), (cx, cy + 1), (cx + 1, cy + 1)]:
        px[x, y] = G0
    for i in range(1, 6): shadow_at(cx + i, cy - 1 + i // 3, False)
    for y in range(cy - 13, cy): px[cx, y] = P0
    for (x, y, c) in [(cx + 1, cy - 13, RED), (cx + 2, cy - 13, RED), (cx + 3, cy - 13, RED), (cx + 4, cy - 13, RED), (cx + 1, cy - 12, RED),
                      (cx + 2, cy - 12, RED), (cx + 3, cy - 12, RED), (cx + 4, cy - 12, RED), (cx + 5, cy - 12, RED), (cx + 1, cy - 11, RED_D),
                      (cx + 2, cy - 11, RED_D), (cx + 3, cy - 11, RED_D), (cx + 1, cy - 10, RED_D)]:
        px[x, y] = c
    # ball, 6 px with outline and offset shadow
    bx, by = 44, 50
    shape = ["..oo..", ".owwo.", "owwwso", "owwsso", ".osso.", "..oo.."]
    for y in range(6):
        for x in range(6):
            if shape[y][x] != '.': shadow_at(bx + x + 1, by + y + 1, False)
    for y in range(6):
        for x in range(6):
            ch = shape[y][x]
            if ch == 'o': px[bx + x, by + y] = OUTL
            elif ch == 'w': px[bx + x, by + y] = BALL
            elif ch == 's': px[bx + x, by + y] = BALL_S
    return img

def big(img): return img.resize((img.width * S, img.height * S), Image.NEAREST)

# ------------------------------------------------------------------ UI theme mocks (logical)
def card_navy():
    w, h = 84, 64
    img = Image.new('RGB', (w, h), W1)
    d = ImageDraw.Draw(img)
    d.rectangle([3, 3, w - 2, h - 2], fill=NAVY0)  # drop shadow
    d.rectangle([1, 1, w - 4, h - 4], fill=NAVY1, outline=NAVYE)
    d.line([2, 2, w - 5, 2], fill=NAVY2)
    return img

def card_paper():
    w, h = 84, 64
    img = Image.new('RGB', (w, h), W1)
    d = ImageDraw.Draw(img)
    d.rectangle([3, 3, w - 2, h - 2], fill=NAVY0)
    d.rectangle([1, 1, w - 4, h - 4], fill=P0)
    d.line([1, h - 4, w - 4, h - 4], fill=P2)
    for x in range(2, w - 4, 3): img.putpixel((x, 4), P2)  # perforation
    for y in range(8, h - 6, 5): d.line([4, y, w - 7, y], fill=P1)  # ruled lines
    return img

def plank(img, x, y, w, h):
    px = img.load()
    for yy in range(h):
        for xx in range(w):
            c = D2
            if yy == 0: c = D3
            if yy == h - 1: c = D0
            if yy == h - 2: c = D1
            if yy in (3, 6) and hsh(xx + x, yy, 7) > .3: c = D1
            if (xx in (0, w - 1)) and yy in (0, h - 1): continue
            px[x + xx, y + yy] = c

def ui_mocks():
    a = big(card_navy()); b = big(card_paper())
    # navy: cream & gold text, green button
    text(a, "BIRDIE!", (a.width - twidth("BIRDIE!", F5)) // 2, 6 * S, GOLD, F5, shadow=NAVY0)
    text(a, "2 SHOTS * PAR 3", (a.width - twidth("2 SHOTS * PAR 3", F3)) // 2, 20 * S, CREAM, F3, shadow=NAVY0)
    da = ImageDraw.Draw(a)
    da.rectangle([6 * S, 42 * S, 76 * S - 1, 58 * S - 1], fill=NAVY0)
    da.rectangle([6 * S, 41 * S, 76 * S - 1, 56 * S - 1], fill=hexc('#57ae54'))
    da.rectangle([6 * S, 41 * S, 76 * S - 1, 42 * S - 1], fill=hexc('#7cc673'))
    text(a, "NEXT HOLE", (a.width - twidth("NEXT HOLE", F5)) // 2, 45 * S, CREAM, F5, shadow=NAVY0)
    # paper: ink text, red stamp, driftwood plank button
    text(b, "BIRDIE!", (b.width - twidth("BIRDIE!", F5)) // 2, 7 * S, STAMP_R, F5)
    text(b, "2 SHOTS * PAR 3", (b.width - twidth("2 SHOTS * PAR 3", F3)) // 2, 21 * S, INK, F3)
    small = Image.new('RGB', (84, 64)); small.paste(card_paper())
    plank(small, 6, 40, 70, 17)
    pb = big(small)
    b.paste(pb.crop((6 * S, 40 * S, 76 * S, 57 * S)), (6 * S, 40 * S))
    text(b, "NEXT HOLE", (b.width - twidth("NEXT HOLE", F5)) // 2, 45 * S, P0, F5, shadow=D0)
    return a, b

# ------------------------------------------------------------------ layout wireframes
def wire(title, buttons, scale=1.25):
    w, h = int(180 * scale), int(400 * scale)
    img = Image.new('RGB', (w, h), (40, 60, 90))
    d = ImageDraw.Draw(img, 'RGBA')
    # thumb reach (one hand, either thumb): easy bottom, ok middle, hard top and top corners
    for y in range(h):
        f = y / h
        col = (220, 70, 60, 70) if f < .3 else (240, 200, 60, 60) if f < .55 else (80, 200, 110, 60)
        d.line([0, y, w, y], fill=col)
    d.rectangle([0, 0, w - 1, h - 1], outline=(20, 20, 24), width=3)
    for (label, x, y, bw, bh, kind) in buttons:
        fill = {'p': (90, 180, 90, 255), 'n': (90, 120, 150, 255), 'i': (60, 70, 90, 200)}[kind]
        r = [x * scale, y * scale, (x + bw) * scale, (y + bh) * scale]
        d.rectangle(r, fill=fill, outline=(15, 15, 20, 255), width=2)
        tw = d.textlength(label, font=WF_FONT)
        if tw < r[2] - r[0] - 4:
            d.text(((r[0] + r[2] - tw) / 2, (r[1] + r[3]) / 2 - 9), label, fill=(255, 255, 255), font=WF_FONT)
    return img, title

# ------------------------------------------------------------------ assemble
blocks = []
def heading(t, sub=None):
    img = Image.new('RGB', (W, 96 if sub else 60), BG)
    d = ImageDraw.Draw(img)
    d.text((24, 12), t, fill=INKC, font=H_FONT)
    if sub: d.text((24, 56), sub, fill=(90, 84, 96), font=SM_FONT)
    blocks.append(img)

def row(imgs, labels, h_extra=40):
    hmax = max(i.height for i in imgs)
    img = Image.new('RGB', (W, hmax + h_extra), BG)
    d = ImageDraw.Draw(img)
    n = len(imgs)
    gap = (W - sum(i.width for i in imgs)) // (n + 1)
    x = gap
    for im, lb in zip(imgs, labels):
        img.paste(im, (x, 0))
        d.text((x, hmax + 6), lb, fill=INKC, font=SM_FONT)
        x += im.width + gap
    blocks.append(img)

def note(lines):
    img = Image.new('RGB', (W, 14 + 32 * len(lines)), BG)
    d = ImageDraw.Draw(img)
    for i, l in enumerate(lines): d.text((24, 6 + 32 * i), l, fill=(60, 56, 66), font=B_FONT)
    blocks.append(img)

# title
top = Image.new('RGB', (W, 120), INKC)
dt = ImageDraw.Draw(top)
dt.text((24, 18), "Golf Alone: style board", fill=(247, 243, 227), font=H_FONT)
dt.text((24, 66), "1080 px wide, so on your Pixel every square is one game pixel at true size.", fill=(200, 196, 190), font=SM_FONT)
blocks.append(top)

heading("Today, as it looks on your phone", "real 1:1 crops from the game")
tee = Image.open(f'{SHOTS}/h1-1-tee.png').convert('RGB')
card = Image.open(f'{SHOTS}/h1-3-card.png').convert('RGB')
row([tee.crop((300, 1320, 804, 1704)), card.crop((288, 790, 792, 1174))],
    ["noisy speckle, tile-shaped shadows, 4 px ball", "3x5 font: the N reads as D (\"DEW BEST\")"])

heading("Decision 1: art direction", "mockups (drawn outside the engine), same proposed palette")
row([big(patch(False)), big(patch(True))],
    ["A: flat top-down, cleaned up", "B: 3/4 view: island banks, wall faces"])
note(["Both: clustered tufts instead of noise, soft offset shadows,",
      "hue-shifted ramps, a 6 px outlined ball, outlined objects."])

heading("Proposed palette: hue-shifted ramps", "shadows go cooler, highlights go warmer; no darkening by multiplying")
pal = Image.new('RGB', (W, 5 * 56 + 10), BG)
dp = ImageDraw.Draw(pal)
for i, (name, r) in enumerate(RAMPS):
    dp.text((24, 14 + i * 56), name, fill=INKC, font=B_FONT)
    for j, c in enumerate(r):
        dp.rectangle([150 + j * 70, 6 + i * 56, 150 + j * 70 + 62, 6 + i * 56 + 46], fill=c)
blocks.append(pal)

heading("Type", "today's 3x5 font vs a 5x7 font for headings and buttons")
ft = Image.new('RGB', (W, 190), W1)
text(ft, "NEW BEST  HOLE 1/6  PAR 3", 24, 24, CREAM, F3, shadow=NAVY0)
text(ft, "NEW BEST  HOLE 1/6  PAR 3", 24, 96, CREAM, F5, shadow=NAVY0)
blocks.append(ft)
note(["Every string fits at 5x7: the longest is 25 characters = 149 of 180 px.",
      "Keep 3x5 only for tiny labels (the scorecard grid)."])

heading("Decision 2: UI theme", "mockups of the hole card")
a, b = ui_mocks()
row([a, b], ["A: navy panels, refined", "B: scorecard paper + driftwood buttons"])

heading("Thumb reach and layout", "green = easy, yellow = stretch, red = hard (one-handed)")
wa, _ = wire("t", [("pause", 2, 10, 11, 9, 'i'), ("look", 167, 10, 11, 9, 'i'), ("PLAY COURSE", 28, 198, 124, 22, 'p'), ("PRACTISE", 28, 240, 124, 16, 'n'),
                   ("", 22, 362, 64, 14, 'n'), ("", 94, 362, 64, 14, 'n')], 1.0)
wb, _ = wire("t", [("PLAY", 12, 296, 156, 30, 'p'), ("HOLES", 12, 334, 74, 24, 'n'), ("SET", 94, 334, 74, 24, 'n')], 1.0)
wc, _ = wire("p", [("pause", 2, 10, 11, 9, 'i'), ("look", 167, 10, 11, 9, 'i'), ("card", 12, 140, 156, 128, 'i'), ("NEXT", 24, 225, 132, 18, 'p')], 1.0)
wd, _ = wire("p", [("info only", 0, 8, 180, 14, 'i'), ("hold still: peek", 20, 170, 140, 22, 'i'), ("||", 4, 346, 24, 24, 'n')], 1.0)
we, _ = wire("c", [("card sheet", 0, 236, 180, 164, 'i'), ("NEXT HOLE", 12, 340, 156, 30, 'p')], 1.0)
row([wa, wb, wc, wd, we], ["title", "title", "play + card", "play", "card"])
note(["Columns 1 and 3 are today; 2, 4 and 5 are proposed."])
note(["In play the lower half is where you pull back, so it stays clear: pause",
      "becomes a small chip that fires only on a clean tap (a drag that starts",
      "on it is a shot), and \"look at the green\" is hold-still-to-peek.",
      "Menus become bottom sheets with the main action lowest and biggest."])

H = sum(b.height for b in blocks)
board = Image.new('RGB', (W, H), BG)
y = 0
for b in blocks:
    board.paste(b, (0, y)); y += b.height
board.save(OUT)
print(OUT, board.size)

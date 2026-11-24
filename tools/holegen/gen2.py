"""Seeded hole generator for courses 10-19: a winding island from tee to green, with the
theme's challenges laid across it. Every map is then proven (or thrown away) by the bot."""
import random
from gen import Map, current

LAND = '.,:sPmrn;'

THEMES = {
    'oasis':    dict(style='desert',   mood='DESERT',   name='MIRAGE OASIS',    sig='quick',     pool=['quick', 'sandfield', 'pond', 'posts', 'slope', 'quick']),
    'mushroom': dict(style='mushroom', mood='TWILIGHT', name='MUSHROOM HOLLOW', sig='ventgap',   pool=['ventgap', 'bumpers', 'vines', 'kick', 'bumpers']),
    'carnival': dict(style='carnival', mood='EVENING',  name='CARNIVAL PIER',   sig='bumpers',   pool=['bumpers', 'boost', 'mill', 'deckgap', 'conveyor', 'kick']),
    'sky':      dict(style='sky',      mood='SKY',      name='SKYREACH',        sig='kickgap',   pool=['kickgap', 'portalgap', 'ridge', 'bridge', 'kickgap']),
    'clock':    dict(style='clock',    mood='BRONZE',   name='CLOCKWORK RUINS', sig='pulse',     pool=['pulse', 'gate', 'conveyor', 'mill', 'tunnel', 'pulse']),
    'mangrove': dict(style='mangrove', mood='SWAMP',    name='MANGROVE MAZE',   sig='lily',      pool=['lily', 'stream', 'mud', 'logs', 'lily']),
    'crystal':  dict(style='crystal',  mood='CAVE',     name='CRYSTAL CAVERNS', sig='wells',     pool=['portalgap', 'wells', 'pulse', 'walls', 'wells']),
    'autumn':   dict(style='autumn',   mood='AUTUMN',   name='AUTUMN WOODS',    sig='leaves',    pool=['leaves', 'stream', 'trees', 'logs', 'slope']),
    'zen':      dict(style='zen',      mood='SAKURA',   name='ZEN GARDEN',      sig='gravel',    pool=['gravel', 'pond', 'bridge', 'mill', 'stones']),
    'starfall': dict(style='',         mood='STARRY',   name='STARFALL ATOLL',  sig='wells',     pool=['wells', 'portalgap', 'bumpers', 'kickgap', 'boost']),
}

FORK = dict(oasis='~', mushroom='r', carnival='#', sky='~', clock='#', mangrove='m', crystal='#', autumn='P', zen='~', starfall='~')
GUARD = dict(oasis='Q', mushroom='s', carnival='s', sky='~', clock='s', mangrove='m', crystal='s', autumn='n', zen='s', starfall='s')
EDGE = dict(oasis='P', autumn='P', mangrove='P', mushroom='o', zen='#')

class Hole:
    def __init__(self, m, extra):
        self.m, self.extra = m, extra

def path_x(pts, y):
    """The path's x at row y (pts run from the tee upwards)."""
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        if y1 <= y <= y0:
            t = (y0 - y) / max(y0 - y1, 1e-6)
            return x0 + (x1 - x0) * t
    return pts[-1][0]

def land_cols(m, y):
    return [x for x in range(m.w) if m.g[y][x] in LAND]

def band(m, y0, y1, ch, keep_side=None):
    """Replace land in rows y0..y1 with ch; keep_side leaves a 2-tile strip of land at 'L' or 'R'."""
    for y in range(y0, y1 + 1):
        cols = land_cols(m, y)
        if not cols: continue
        lo, hi = min(cols), max(cols)
        for x in cols:
            if keep_side == 'L' and x <= lo + 1: continue
            if keep_side == 'R' and x >= hi - 1: continue
            m.g[y][x] = ch

def put_land(m, x, y, ch):
    if 0 <= y < m.h and 0 <= x < m.w and m.g[y][x] in '.,': m.g[y][x] = ch

def disc_land(m, cx, cy, r, ch, only='.,'):
    for y in range(m.h):
        for x in range(m.w):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 0.3 and m.g[y][x] in only: put_land(m, x, y, ch)

def challenge(kind, m, rnd, pts, ya, yb, extra):
    """Lay one challenge across the path between rows ya (lower, nearer the tee) and yb (upper)."""
    yc = (ya + yb) // 2
    px = int(round(path_x(pts, yc)))
    side = rnd.choice([-1, 1])
    if kind in ('ventgap', 'kickgap', 'portalgap', 'bridge', 'deckgap', 'lily'):
        thick = {'ventgap': 2, 'kickgap': 3, 'portalgap': rnd.randint(4, 6), 'bridge': 3, 'deckgap': 3, 'lily': 3}[kind]
        y0 = yc - thick // 2
        y1 = y0 + thick - 1
        keep = rnd.choice('LR') if kind == 'lily' else None
        band(m, y0, y1, '~', keep_side=keep)
        bx = int(round(path_x(pts, y1 + 2)))
        if kind == 'ventgap':
            put_land(m, bx, y1 + 2, 'V'); put_land(m, bx + 1, y1 + 2, 'V')
        elif kind == 'kickgap':
            put_land(m, bx, y1 + 2, 'Y')
            put_land(m, bx + side * 3, y1 + 2, 'Y')
        elif kind == 'portalgap':
            put_land(m, bx + side * 2, y1 + 2, '1')
            ax = int(round(path_x(pts, y0 - 2)))
            put_land(m, ax - side * 2, y0 - 2, '1')
        else:
            ch = 'j' if kind == 'lily' else '='
            for y in range(y0, y1 + 1):
                for x in (px, px + 1):
                    if 0 < x < m.w - 1: m.g[y][x] = ch
    elif kind in ('mill', 'pulse', 'gate', 'tunnel', 'walls'):
        cols = land_cols(m, yc)
        if not cols: return
        lo, hi = min(cols), max(cols)
        for x in range(lo, hi + 1): m.g[yc][x] = '#'
        if kind == 'mill':
            gx = max(lo + 1, min(hi - 3, px))
            m.g[yc][gx] = '.'; m.g[yc][gx + 1] = '.'
            extra.setdefault('mills', []).append((gx + 2 if gx + 2 <= hi else gx - 1, yc, 13.0, rnd.choice([1.6, -1.8, 2.0]), round(rnd.uniform(0, 3), 2)))
        elif kind == 'pulse':
            for x in range(lo, hi + 1): m.g[yc][x] = 'M'
            wx = rnd.randint(lo, max(lo, hi - 2))
            for x in range(wx, min(hi, wx + 2) + 1): m.g[yc][x] = 'R'
        elif kind == 'gate':
            gx = max(lo, min(hi - 1, px))
            m.g[yc][gx] = 'G'; m.g[yc][gx + 1] = 'G'
            py_ = min(m.h - 3, yc + rnd.randint(3, 5))
            pcols = land_cols(m, py_)
            if pcols: put_land(m, rnd.choice(pcols), py_, 'p')
        elif kind == 'tunnel':
            if yc + 1 < m.h:
                for x in range(lo, hi + 1):
                    if m.g[yc + 1][x] in LAND: m.g[yc + 1][x] = '#'
            mx = max(lo, min(hi, px + side * 2))
            ex = max(lo, min(hi, px - side * 2))
            put_land(m, mx, yc + 2, '(')
            put_land(m, ex, yc - 1, ')')
        elif kind == 'walls':
            for x in range(lo, hi + 1):
                if rnd.random() < 0.45: m.g[yc][x] = '.'
            m.g[yc][px] = '.'
    elif kind == 'quick':
        disc_land(m, px + side * rnd.uniform(0.5, 2), yc, rnd.uniform(1.4, 2.3), 'Q')
    elif kind == 'sandfield':
        for _ in range(rnd.randint(2, 3)): disc_land(m, px + rnd.uniform(-3, 3), yc + rnd.uniform(-3, 3), rnd.uniform(1, 1.8), 's')
    elif kind == 'gravel':
        for y in range(yc - 1, yc + 1):
            for x in land_cols(m, y): m.g[y][x] = 's'
        disc_land(m, px + side * 3, yc - 3, 1.2, 's')
    elif kind == 'pond':
        disc_land(m, px + side * rnd.uniform(1, 2.5), yc, rnd.uniform(1.6, 2.4), '~')
    elif kind in ('posts', 'trees'):
        for _ in range(rnd.randint(2, 4)): put_land(m, int(px + rnd.uniform(-3, 3)), int(yc + rnd.uniform(-3, 3)), 'P')
    elif kind == 'bumpers':
        for _ in range(rnd.randint(3, 5)): put_land(m, int(px + rnd.uniform(-3, 3)), int(yc + rnd.uniform(-3, 3)), 'o')
    elif kind == 'vines':
        disc_land(m, px + side * 2, yc, rnd.uniform(1.4, 2.2), 'r')
    elif kind == 'mud':
        disc_land(m, px + side * 1.5, yc, rnd.uniform(1.2, 2), 'm')
    elif kind == 'leaves':
        for _ in range(rnd.randint(2, 3)): disc_land(m, px + rnd.uniform(-3, 3), yc + rnd.uniform(-2, 2), 1.1, 'n')
    elif kind == 'kick':
        put_land(m, px, yc + 2, 'Y')
    elif kind == 'boost':
        put_land(m, px, ya - 1, 'B')
    elif kind == 'wells':
        put_land(m, int(round(px + side * rnd.uniform(1.5, 3))), yc, '@')
    elif kind == 'stones':
        for _ in range(rnd.randint(2, 4)): put_land(m, int(px + rnd.uniform(-3, 3)), int(yc + rnd.uniform(-3, 3)), '#')
    elif kind == 'logs':
        x0 = px + side * 3
        for k in range(4):
            put_land(m, int(x0 - side * k), yc - 2 + k, 'w')
    elif kind == 'ridge':
        for y in range(yb, ya + 1):
            for x in land_cols(m, y):
                if m.g[y][x] == '.': m.g[y][x] = ';'
        extra['wind'] = rnd.choice([0.0, 180.0])
    elif kind in ('stream', 'conveyor'):
        cols = land_cols(m, yc)
        if cols:
            lo, hi = min(cols), max(cols)
            ch = rnd.choice('64')
            for y in (yc, yc + 1):
                for x in range(lo + 1, hi):
                    if m.g[y][x] in LAND: m.g[y][x] = ch
    elif kind == 'slope':
        pass  # done on the green in make()

def make(theme, seed, size, teach=False):
    T = THEMES[theme]
    rnd = random.Random(seed * 7919 + hash(theme) % 1000)
    W = 20 if rnd.random() < 0.6 else 22
    H = {'S': rnd.randint(30, 36), 'M': rnd.randint(44, 52), 'L': rnd.randint(60, 70), 'X': rnd.randint(64, 70)}[size]
    m = Map(W, H)
    teeY = H - 6
    greenY = 7
    nb = {'S': 1, 'M': 2, 'L': 3, 'X': 4}[size]
    pts = [(rnd.uniform(6, W - 7), teeY)]
    for k in range(nb):
        y = teeY - (teeY - greenY) * (k + 1) / (nb + 1)
        pts.append((rnd.uniform(4, 7) if (size == 'X' and k % 2 == 0) else (rnd.uniform(W - 8, W - 5) if size == 'X' else rnd.uniform(5, W - 6)), y))
    gx = rnd.uniform(7, W - 8)
    pts.append((gx, greenY + 2))
    for (a, b) in zip(pts, pts[1:]):
        m.line(a[0], a[1], b[0], b[1], '.', rnd.uniform(2.2, 2.9) if size == 'X' else rnd.uniform(2.3, 4.0))
    kinds = [T['sig']] if teach else [T['sig']] + [rnd.choice(T['pool'] + ['fork', 'fork']) for _ in range(nb - 1)]
    if not teach and nb == 1 and rnd.random() < 0.5: kinds = [rnd.choice(T['pool'] + ['fork'])]
    rnd.shuffle(kinds)
    stretch = [(int(pts[k][1]) - 2, int(pts[k + 1][1]) + 2) for k in range(len(pts) - 1)]
    forks = []
    for kind, (ya, yb) in zip(kinds, stretch):
        if kind != 'fork': continue
        yc = (ya + yb) // 2
        r = rnd.uniform(4.6, 5.6)
        cx = max(2 + r, min(W - 3 - r, path_x(pts, yc)))
        m.disc(cx, yc, r, '.')
        forks.append((cx, yc))
    grx, gry = rnd.uniform(3.4, 4.6), rnd.uniform(2.8, 3.4)
    m.oval(gx, greenY, grx + 1.2, gry + 1, '.')
    m.oval(gx, greenY, grx, gry, ':')
    m.oval(pts[0][0], teeY, 4, 2.6, '.')
    m.border()
    extra = {'style': T['style']}
    for cx, yc in forks:
        core = FORK[theme]
        if core == 'P':
            for dx, dy in ((0, 0), (-1, 1), (1, -1), (1, 1)): put_land(m, int(round(cx)) + dx, yc + dy, 'P')
        else:
            for y in range(yc - 2, yc + 3):
                for x in range(m.w):
                    if ((x - cx) / 2.3) ** 2 + ((y - yc) / 1.9) ** 2 <= 1: m.g[y][x] = core
    extra['kinds'] = kinds[:len(stretch)]
    for kind, (ya, yb) in zip(kinds, stretch):
        if kind == 'fork': continue
        challenge(kind, m, rnd, pts, ya, yb, extra)
    if rnd.random() < 0.75:
        sd = rnd.choice([-1, 1])
        disc_land(m, gx + sd * (grx + 1.3), greenY + rnd.uniform(-0.5, 2), rnd.uniform(1.0, 1.5), GUARD[theme], only='.')
    if theme in EDGE:
        edge = [(x, y) for y in range(greenY + 5, teeY - 3) for x in range(1, W - 1)
                if m.g[y][x] == '.' and (m.g[y][x - 1] == ',' or m.g[y][x + 1] == ',')]
        for x, y in rnd.sample(edge, min(len(edge), rnd.randint(3, 7))): m.g[y][x] = EDGE[theme]
    if 'slope' in kinds or (not teach and rnd.random() < 0.3):
        d = rnd.choice('^v<>')
        for y in range(greenY - 3, greenY + 4):
            for x in range(W):
                if m.g[y][x] == ':' and rnd.random() < 0.55 and (x + y) % 2 == 0: m.g[y][x] = d
    cx, cy = int(round(gx)), greenY
    m.g[cy][cx] = 'O'
    m.g[teeY][int(round(pts[0][0]))] = 'T'
    # Filters: margins of sea, no portal left unpaired.
    rows = m.rows()
    if any(r[0] != '~' or r[-1] != '~' for r in rows) or rows[0].strip('~') or rows[-1].strip('~'):
        return None
    flat = ''.join(rows)
    if flat.count('1') not in (0, 2) or flat.count('O') != 1 or flat.count('T') != 1: return None
    if flat.count('(') != flat.count(')') or flat.count('(') > 1: return None
    return Hole(m, extra)

def block(id_, h, rows=None):
    lines = [f'== {id_}', f"style={h.extra.get('style', '')}"]
    if 'wind' in h.extra: lines.append(f"wind={h.extra['wind']}")
    if 'mills' in h.extra: lines.append('mills=' + ';'.join(','.join(str(v) for v in mm) for mm in h.extra['mills']))
    lines.append('kinds=' + ','.join(h.extra.get('kinds', [])))
    lines.append('rows:')
    lines += rows if rows is not None else h.m.rows()
    return '\n'.join(lines) + '\n'

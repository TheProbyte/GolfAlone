"""Second rounds for the painted courses (1-9): the same islands, reworked.

python3 remix.py <Course.kt> <course id> <seeds> > candidates.txt

Only land becomes other land, so the coast can't change: the cup moves on the green (and the
green may shrink round it), the tee may move back, and the course's own hazards are added beside
the line. Crab patrols, tidal sand, scenery and windmill sweeps are left alone. Every candidate
is then proved by CandidateProofTest, and a person picks from the previews.
"""
import math, random, re, sys
from collections import deque

# Hazards each course adds: (character, single tile only).
HAZARDS = dict(
    cove=[('s', False), ('P', True)], marsh=[('m', False), ('s', False)], light=[('o', True), ('s', False)],
    lagoon=[('s', False), ('P', True)], wreck=[('s', False), ('#', True)], ember=[('g', False), ('s', False)],
    frost=[('n', False), ('i', False)], temple=[('r', False), ('#', True)], bluff=[('s', False), ('#', True)],
)
GREEN = ':^v<>O'
BLOCK = '~#LHxGJMRwPo'


def parse(path):
    """Each hole in a course file: (kotlin name, display name, par, extra-args text, rows)."""
    src = open(path).read()
    out = []
    for m in re.finditer(r'    val (\w+) = Hole\(\n(.*?)\n    \)\n', src, re.S):
        body = m.group(2)
        name = re.search(r'name = "([^"]+)"', body).group(1)
        par = int(re.search(r'par = (\d+)', body).group(1))
        extra = body[body.index('\n', body.index('par = ')) + 1:body.index('        rows = listOf(')]
        rows = re.findall(r'^            "([^"]*)",', body, re.M)
        out.append((m.group(1), name, par, extra, rows))
    return out


def forbidden(extra, w, h):
    """Tiles the remix must not touch: crab patrols, scenery, windmill sweeps."""
    bad = set()
    for crab in re.findall(r'Crab\(([^)]*)\)', extra):
        pts = [tuple(map(int, p.split(' to '))) for p in re.findall(r'\d+ to \d+', crab)]
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            for x in range(min(x0, x1), max(x0, x1) + 1):
                for y in range(min(y0, y1), max(y0, y1) + 1): bad.add((x, y))
        bad.update(pts)
    for key in ('lighthouse', 'lanterns'):
        for seg in re.findall(key + r' = (.*)', extra):
            bad.update(tuple(map(int, p.split(' to '))) for p in re.findall(r'\d+ to \d+', seg))
    for wm in re.findall(r'Windmill\((\d+), (\d+), length = ([\d.]+)f', extra):
        hx, hy, ln = int(wm[0]), int(wm[1]), float(wm[2])
        r = ln / 8 + 1.5
        for y in range(h):
            for x in range(w):
                if (x - hx) ** 2 + (y - hy) ** 2 <= r * r: bad.add((x, y))
    return bad


def find(g, ch):
    for y, r in enumerate(g):
        for x, c in enumerate(r):
            if c == ch: return x, y
    return None


def path(g, a, b):
    """Shortest walk over land from a to b (tiles), or [] if there's none."""
    h, w = len(g), len(g[0])
    prev = {a: None}
    q = deque([a])
    while q:
        p = q.popleft()
        if p == b: break
        x, y = p
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < w and 0 <= n[1] < h and n not in prev and g[n[1]][n[0]] not in BLOCK:
                prev[n] = p
                q.append(n)
    if b not in prev:
        # No walk (a gate, a tunnel or a vent's hop on the way): the straight line will do.
        n = int(max(abs(b[0] - a[0]), abs(b[1] - a[1]), 1))
        return [(round(a[0] + (b[0] - a[0]) * k / n), round(a[1] + (b[1] - a[1]) * k / n)) for k in range(n + 1)]
    out, p = [], b
    while p: out.append(p); p = prev[p]
    return out[::-1]


def remix(course, rows, extra, seed):
    rnd = random.Random(f'{course}:{seed}:{len(rows)}')
    g = [list(r) for r in rows]
    h, w = len(g), len(g[0])
    bad = forbidden(extra, w, h)
    cup, tee = find(g, 'O'), find(g, 'T')
    edits = []
    # The cup moves: to green with green all round it, two or more tiles from where it was.
    spots = [(x, y) for y in range(1, h - 1) for x in range(1, w - 1) if g[y][x] == ':' and
             all(g[y + dy][x + dx] in GREEN for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))) and
             math.dist((x, y), cup) >= 2 and (x, y) not in bad]
    if spots:
        greens = [(x, y) for y in range(h) for x in range(w) if g[y][x] in GREEN]
        gcx = sum(p[0] for p in greens) / len(greens)
        gcy = sum(p[1] for p in greens) / len(greens)
        spots.sort(key=lambda p: -math.dist(p, (gcx, gcy)))
        nx, ny = rnd.choice(spots[:max(2, len(spots) // 2)])
        g[cup[1]][cup[0]] = ':'
        g[ny][nx] = 'O'
        cup = (nx, ny)
        edits.append('cup')
        # The green shrinks round it.
        if rnd.random() < 0.8:
            r = rnd.uniform(2.3, 3.2)
            for y in range(h):
                for x in range(w):
                    if g[y][x] == ':' and math.dist((x, y), cup) > r: g[y][x] = '.'
            edits.append('green')
    # Sometimes the tee moves back along the island.
    if rnd.random() < 0.5:
        line = path(g, tee, cup)
        far = len(line)
        backs = [(x, y) for y in range(1, h - 1) for x in range(1, w - 1) if g[y][x] == '.' and (x, y) not in bad and
                 all(g[y + dy][x + dx] in '.,T' for dx in (-1, 0, 1) for dy in (-1, 0, 1))]
        backs = [p for p in backs if 2 <= math.dist(p, tee) <= 6 and len(path(g, p, cup)) > far]
        if backs:
            g[tee[1]][tee[0]] = '.'
            tee = rnd.choice(backs)
            g[tee[1]][tee[0]] = 'T'
            edits.append('tee')
    # A guard or two against the green.
    rim = [(x, y) for y in range(1, h - 1) for x in range(1, w - 1) if g[y][x] in '.,' and (x, y) not in bad and
           any(g[y + dy][x + dx] in GREEN for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))) and math.dist((x, y), cup) >= 2]
    rnd.shuffle(rim)
    guards = 0
    for (x, y) in rim[:rnd.randint(1, 2)]:
        ch, single = rnd.choice(HAZARDS[course])
        if single: continue
        for yy in range(y - 1, y + 2):
            for xx in range(x - 1, x + 2):
                if 0 < xx < w - 1 and 0 < yy < h - 1 and g[yy][xx] in '.,' and (xx, yy) not in bad and math.dist((xx, yy), cup) >= 2 \
                        and (xx - x) ** 2 + (yy - y) ** 2 <= 1.3:
                    g[yy][xx] = ch
        guards += 1
    if guards: edits.append(f'{guards} guards')
    # The course's hazards, beside the line from tee to cup.
    line = path(g, tee, cup)
    if not line: return None
    near = set()
    for (x, y) in line[2:-3]:
        for dx in range(-3, 4):
            for dy in range(-3, 4):
                if 1 <= abs(dx) + abs(dy) <= 4: near.add((x + dx, y + dy))
    keep = set()
    for p in (tee, cup):
        for dx in range(-2, 3):
            for dy in range(-2, 3): keep.add((p[0] + dx, p[1] + dy))
    cands = [p for p in near if 0 < p[0] < w - 1 and 0 < p[1] < h - 1 and g[p[1]][p[0]] == '.' and p not in bad and p not in keep]
    rnd.shuffle(cands)
    added = 0
    for (x, y) in cands[:rnd.randint(3, 6)]:
        ch, single = rnd.choice(HAZARDS[course])
        if ch == ';' and 'windDeg' not in extra: ch, single = 's', False
        if single:
            if any(g[y + dy][x + dx] in 'P#o' for dx in (-1, 0, 1) for dy in (-1, 0, 1)): continue
            g[y][x] = ch
        else:
            r = rnd.uniform(0.8, 1.6)
            for yy in range(y - 2, y + 3):
                for xx in range(x - 2, x + 3):
                    if 0 < xx < w - 1 and 0 < yy < h - 1 and g[yy][xx] == '.' and (xx, yy) not in bad and (xx, yy) not in keep \
                            and (xx - x) ** 2 + (yy - y) ** 2 <= r * r + 0.3:
                        g[yy][xx] = ch
        added += 1
    if added: edits.append(f'{added} hazards')
    return [''.join(r) for r in g], edits


def props(extra):
    """The proof harness's view of a hole's extras: tide, wind, windmills and crabs."""
    out = []
    m = re.search(r'tideStart = (\d+)', extra)
    if m: out.append(f'tide={m.group(1)}')
    m = re.search(r'windDeg = (-?[\d.]+)f', extra)
    if m: out.append(f'wind={m.group(1)}')
    mills = re.findall(r'Windmill\((\d+), (\d+), length = ([\d.]+)f, omega = (-?[\d.]+)f, phase = ([\d.]+)f\)', extra)
    if mills: out.append('mills=' + ';'.join(','.join(v) for v in mills))
    crabs = re.findall(r'Crab\(([^)]*)\)', extra)
    if crabs: out.append('crabs=' + '|'.join(':'.join(p.replace(' to ', ',') for p in re.findall(r'\d+ to \d+', c)) for c in crabs))
    return out


if __name__ == '__main__':
    path_kt, course, seeds = sys.argv[1], sys.argv[2], int(sys.argv[3])
    style = sys.argv[4] if len(sys.argv) > 4 else ''
    for n, (kname, name, par, extra, rows) in enumerate(parse(path_kt)):
        seen = set()
        for seed in range(seeds):
            r = remix(course, rows, extra, seed)
            if not r: continue
            new, edits = r
            key = tuple(new)
            if key in seen or new == rows: continue
            seen.add(key)
            print(f'== {course}-{n + 1}-r{seed}')
            print(f'style={style}')
            print('kinds=' + ','.join(e.replace(' ', '_') for e in edits))
            for p in props(extra): print(p)
            print('rows:')
            print('\n'.join(new))

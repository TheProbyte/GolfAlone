"""Tiny map painter: shapes on a grid of sea, then a rough border round grass, then Kotlin rows."""
import math

class Map:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.g = [['~'] * w for _ in range(h)]
    def rect(self, x0, y0, x1, y1, ch):
        for y in range(max(0, y0), min(self.h, y1 + 1)):
            for x in range(max(0, x0), min(self.w, x1 + 1)):
                self.g[y][x] = ch
        return self
    def oval(self, cx, cy, rx, ry, ch):
        for y in range(self.h):
            for x in range(self.w):
                if ((x - cx) / (rx + .01)) ** 2 + ((y - cy) / (ry + .01)) ** 2 <= 1.0:
                    self.g[y][x] = ch
        return self
    def put(self, x, y, ch):
        self.g[y][x] = ch
        return self
    def line(self, x0, y0, x1, y1, ch, r=0):
        """A round brush of radius [r] (tiles) along the segment."""
        n = int(max(abs(x1 - x0), abs(y1 - y0), 1) * 2)
        for k in range(n + 1):
            x = x0 + (x1 - x0) * k / n; y = y0 + (y1 - y0) * k / n
            self.disc(x, y, r, ch)
        return self
    def disc(self, cx, cy, r, ch):
        for y in range(self.h):
            for x in range(self.w):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 0.3:
                    self.g[y][x] = ch
        return self
    def path(self, pts, ch, r):
        for (a, b) in zip(pts, pts[1:]): self.line(a[0], a[1], b[0], b[1], ch, r)
        return self
    def border(self, ch=',', of='.:', into='~'):
        """Rough (or [ch]) on every [into] cell touching a cell in [of] (8 ways)."""
        add = []
        for y in range(self.h):
            for x in range(self.w):
                if self.g[y][x] not in into: continue
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        yy, xx = y + dy, x + dx
                        if 0 <= yy < self.h and 0 <= xx < self.w and self.g[yy][xx] in of:
                            add.append((x, y)); break
                    else: continue
                    break
        for x, y in add: self.g[y][x] = ch
        return self
    def replace(self, a, b, x0=0, y0=0, x1=None, y1=None):
        x1 = self.w - 1 if x1 is None else x1; y1 = self.h - 1 if y1 is None else y1
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if self.g[y][x] == a: self.g[y][x] = b
        return self
    def rows(self):
        return [''.join(r) for r in self.g]
    def kotlin(self, indent='            '):
        return '\n'.join(f'{indent}"{r}",' for r in self.rows())
    def show(self):
        print('\n'.join(self.rows()))

def ring(m, cx, cy, r, ch, gaps=(), width=0.55):
    """A ring of [ch] at radius [r]; [gaps] are (from, to) angles in degrees (0 = east, 90 = south) left open."""
    import math
    for y in range(m.h):
        for x in range(m.w):
            d = math.hypot(x - cx, y - cy)
            if abs(d - r) > width: continue
            a = math.degrees(math.atan2(y - cy, x - cx)) % 360
            if any((a - g0) % 360 <= (g1 - g0) % 360 for g0, g1 in gaps): continue
            m.g[y][x] = ch
    return m

def current(m, pts, r=0.8):
    """A current along a polyline: each cell gets the direction of its segment (8 2 6 4 = N S E W)."""
    for (a, b) in zip(pts, pts[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        ch = ('6' if dx > 0 else '4') if abs(dx) >= abs(dy) else ('2' if dy > 0 else '8')
        m.line(a[0], a[1], b[0], b[1], ch, r)
    return m

def seal_lava(m):
    """Lava never meets the sea: any lava tile beside open water becomes basalt rock."""
    hits = []
    for y in range(m.h):
        for x in range(m.w):
            if m.g[y][x] != 'L': continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                xx, yy = x + dx, y + dy
                if not (0 <= xx < m.w and 0 <= yy < m.h) or m.g[yy][xx] == '~':
                    hits.append((x, y)); break
    for x, y in hits: m.g[y][x] = '#'
    return m

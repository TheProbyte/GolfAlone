"""python3 batch.py <out> <seeds> [themes...]: candidates plus a twin of each with its helpers stripped."""
import sys
from gen2 import make, block, THEMES
out, n = sys.argv[1], int(sys.argv[2])
themes = sys.argv[3:] or list(THEMES)
SIZES = __import__('os').environ.get('SIZES', 'SML')
HELPERS = 'YyXKV13B'
with open(out, 'w') as f:
    k = 0
    for theme in themes:
        for seed in range(n):
            for size in SIZES:
                h = make(theme, seed, size, teach=(seed < 4))
                if not h: continue
                id_ = f'{theme}-{size}-{seed}'
                f.write(block(id_, h)); k += 1
                rows = h.m.rows()
                if any(c in HELPERS for r in rows for c in r):
                    f.write(block(id_ + '~plain', h, [''.join('.' if c in HELPERS else c for c in r) for r in rows]))
print(k, 'candidates')

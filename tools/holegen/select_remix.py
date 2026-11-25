"""Picks one proven rework per hole of courses 1-9 and writes their second rounds.

python3 select_remix.py cand_remix.txt <shipped tolerance file> [--force]

A rework is kept when the bot proves it, the coast is untouched, its best is the same as the
original's or one more, and it's less forgiving than the original (tolerance: the share of
near-miss shots that still make par). Of those, the one nearest a target a little below the
original's tolerance wins. Each new hole keeps the original's tide, crabs, scenery and wind.
"""
import os, re, sys
from remix import parse

COURSES = [  # id, file, object, II mood, style
    ('cove', 'Holes.kt', 'Holes.COVE', 'GOLDEN', ''),
    ('marsh', 'Saltmarsh.kt', 'Saltmarsh.COURSE', 'NIGHT', ''),
    ('light', 'Lighthouse.kt', 'Lighthouse.COURSE', 'STORM', ''),
    ('lagoon', 'Lagoon.kt', 'Lagoon.COURSE', 'DUSK', ''),
    ('wreck', 'Wreck.kt', 'Wreck.COURSE', 'NIGHT', ''),
    ('ember', 'Ember.kt', 'Ember.COURSE', 'NIGHT', 'ember'),
    ('frost', 'Frost.kt', 'Frost.COURSE', 'STARRY', 'frost'),
    ('temple', 'Temple.kt', 'Temple.COURSE', 'DUSK', ''),
    ('bluff', 'Bluff.kt', 'Bluff.COURSE', 'GOLDEN', ''),
]
OBJ = dict(cove='CoveII', marsh='SaltmarshII', light='LighthouseII', lagoon='LagoonII', wreck='WreckII',
           ember='EmberII', frost='FrostII', temple='TempleII', bluff='BluffII')
NAMES = dict(
    cove=['LAST LIGHT', 'THE NARROWS', 'DEADWOOD', 'ROCK POOLS', 'PALM SHADOW', 'HIGH SADDLE'],
    marsh=['HIGH WATER', 'CREEK MOUTH', 'MUDFLATS', 'NIGHT HERON', 'SALT PANS', 'SPRING TIDE'],
    light=['STORM LAMP', 'BREAKERS', 'THE BEACON', 'GALE BUOYS', 'CRAB SHELF', 'WRECKERS LIGHT'],
    lagoon=['RED LAGOON', 'SUNSET SHOALS', 'THE DRIFT', 'EMBER REEF', 'TURTLE NIGHT', 'LAST CURRENT'],
    wreck=['GRAVEYARD', 'BROKEN KEEL', 'THE BILGE', 'POWDER ROOM', 'LOST CARGO', 'DAVY JONES'],
    ember=['ASHFALL', 'BLACK GLASS', 'MAGMA RUN', 'CINDER CONE', 'FIRE FOUNTAIN', 'THE FURNACE'],
    frost=['POLAR NIGHT', 'BLACK ICE', 'SNOWBLIND', 'FROST GIANT', 'THE GLACIER', 'NORTHERN STAR'],
    temple=['DUSK SHRINE', 'IDOL EYES', 'THE VAULT', 'SERPENT STAIR', 'LOST GATE', 'SUN ALTAR'],
    bluff=['GOLDEN GALE', 'CLIFF EDGE', 'THE DROP', 'HARVEST MILL', 'SHEPHERD PATH', 'THE SUMMIT'],
)
GAME = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../app/src/main/java/com/theprobyte/golfalone/game')


def load(path):
    blocks, res = {}, {}
    for txt in open(path).read().split('== ')[1:]:
        lines = txt.rstrip('\n').split('\n')
        at = lines.index('rows:')
        blocks[lines[0].strip()] = (dict(l.split('=', 1) for l in lines[1:at] if '=' in l), lines[at + 1:])
    for l in open(path + '.results'):
        id_, rest = l.split(' ', 1)
        if 'error=' in rest: continue
        res[id_] = dict(kv.split('=') for kv in rest.split())
    return blocks, res


def choose(course, n, orig_best, orig_tol, B, R):
    """The rework for hole [n], and how it was chosen: 'harder' (clearly less forgiving), 'tight'
    (no more forgiving than an already tight original), or 'closest' (the least forgiving there is)."""
    ids = [i for i in B if i.startswith(f'{course}-{n}-r')]
    ok = []
    for i in ids:
        r = R.get(i)
        if not r or r.get('coast') != 'true' or float(r['stray']) > 3 or 'tol' not in r: continue
        b, t = int(r['best']), float(r['tol'])
        if r.get('dry') == '0' or r.get('flat') == '0': continue  # needs the tide or a ramp for par
        if orig_best <= b <= orig_best + 1 and t >= 0.35: ok.append(i)
    tol = lambda i: float(R[i]['tol'])
    edits = lambda i: len(B[i][0].get('kinds', '').split(','))
    harder = [i for i in ok if tol(i) <= min(orig_tol - 0.05, 0.85)]
    if harder:
        target = max(0.45, orig_tol - 0.2)
        return min(harder, key=lambda i: (abs(tol(i) - target), -edits(i))), 'harder'
    tight = [i for i in ok if orig_tol <= 0.65 and tol(i) <= orig_tol]
    if tight: return min(tight, key=lambda i: (tol(i), -edits(i))), 'tight'
    if ok: return min(ok, key=lambda i: (tol(i), -edits(i))), 'closest'
    return None, None


def main():
    cands, shipped = sys.argv[1], sys.argv[2]
    force = '--force' in sys.argv
    B, R = load(cands)
    tol = {}
    for l in open(shipped):
        k, par, best, t = l.split()
        tol[k] = (int(best.split('=')[1]), float(t.split('=')[1]))
    for course, fname, base, mood, style in COURSES:
        obj = OBJ[course]
        out_path = os.path.join(GAME, obj + '.kt')
        if os.path.exists(out_path) and not force:
            print(course, 'II exists; pass --force to replace it'); continue
        holes = parse(os.path.join(GAME, fname))
        out, names, report = [], [], []
        for n, (kname, name, par, extra, rows) in enumerate(holes, 1):
            ob, ot = tol[f'{course}-{n}']
            pick, how = choose(course, n, ob, ot, B, R)
            if pick is None:
                report.append(f'{n}: none'); continue
            props, new_rows = B[pick]
            b, t = int(R[pick]['best']), R[pick]['tol']
            edits = props.get('kinds', '').replace('_', ' ').replace(',', ', ')
            kn = f'{obj.upper()}_{n}'
            names.append(kn)
            body = '\n'.join(f'            "{r}",' for r in new_rows)
            out.append(f'''    /** {name}, reworked ({edits}; {how}): best {b} (was {ob}), tolerance {t} (was {ot:.2f}). ({pick}) */
    val {kn} = Hole(
        name = "{NAMES[course][n - 1]}",
        par = {b + 1},
{extra}        rows = listOf(
{body}
        ),
    )
''')
            report.append(f'{n}: {pick} best {ob}->{b} tol {ot:.2f}->{t} ({how})')
        if len(names) != len(holes):
            print(course, 'incomplete:', report); continue
        title = re.search(r'Course\("' + course + r'", "([^"]+)"', open(os.path.join(GAME, fname)).read()).group(1)
        style_arg = f', style = "{style}"' if style else ''
        open(out_path, 'w').write(f'''package com.theprobyte.golfalone.game

import com.theprobyte.golfalone.render.Mood

/**
 * {title.title()} II: the same islands, reworked to be harder (tools/holegen/remix.py): the cup
 * moved and sometimes the green shrunk round it, the tee sometimes further back, and the
 * course's own hazards beside the line. Only land became other land, so every coast is the
 * original's. Each hole keeps its tide, crabs, scenery and wind.
 */
object {obj} {{

{chr(10).join(out)}
    val COURSE = Course("{course}2", "{title} II", mood = Mood.{mood}{style_arg},
        base = {base}, holes = listOf({', '.join(names)}))
}}
''')
        print(course, 'par', sum(int(R[l.split()[1]]['best']) + 1 for l in report if 'none' not in l), report)


if __name__ == '__main__':
    main()

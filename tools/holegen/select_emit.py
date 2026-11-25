"""Pick nine proven, varied holes per theme from the batch results and write the Kotlin courses."""
import os, random, sys
from gen2 import THEMES

def load(prefix):
    blocks, res = {}, {}
    for txt in open(prefix).read().split('== ')[1:]:
        lines = txt.rstrip('\n').split('\n')
        at = lines.index('rows:')
        props = dict(l.split('=', 1) for l in lines[1:at] if '=' in l)
        blocks[lines[0].strip()] = (props, lines[at + 1:])
    for l in open(prefix + '.results'):
        id_, rest = l.split(' ', 1)
        res[id_] = dict(kv.split('=') for kv in rest.split())
    return blocks, res

B, R = {}, {}
for p in ('cand_both.txt',):
    if os.path.exists(p) and os.path.exists(p + '.results'):
        b, r = load(p); B.update(b); R.update(r)

GAPS = {'ventgap', 'kickgap', 'portalgap'}
OBJ = dict(oasis='Oasis', mushroom='Mushroom', carnival='Carnival', sky='Skyreach', clock='Clockwork',
           mangrove='Mangrove', crystal='Crystal', autumn='Autumn', zen='Zen', starfall='Starfall')
NAMES = dict(
    oasis=['FIRST MIRAGE', 'DATE PALMS', 'SINKING SANDS', 'CARAVAN ROAD', 'THE WELLSPRING', 'DUNE SEA', 'SALT PAN', 'SCORPION PASS', 'THE GREAT OASIS'],
    mushroom=['SPORE HOP', 'TOADSTOOL RING', 'GLOWCAP', 'PUFFBALL PATH', 'THE MYCELIUM', 'FAIRY STEPS', 'MOSS HOLLOW', 'MOREL MAZE', 'ELDER CAP'],
    carnival=['STEP RIGHT UP', 'PINBALL ALLEY', 'THE MIDWAY', 'BIG TOP', 'HALL OF BELLS', 'TICKET BOOTH', 'HELTER SKELTER', 'GRAND CAROUSEL', 'LAST RIDE'],
    sky=['LEAP OF FAITH', 'CLOUD STEPS', 'THE UPDRAFT', 'KITE RUN', 'FALLING ISLES', 'HIGH WIRE', 'THUNDERHEAD', 'THE STAIRWAY', 'SKYREACH'],
    clock=['TICK', 'TOCK', 'MAINSPRING', 'ESCAPEMENT', 'COGWORKS', 'THE PENDULUM', 'BELL TOWER', 'GEAR TRAIN', 'MIDNIGHT'],
    mangrove=['LILY STEPS', 'ROOT TANGLE', 'BLACKWATER', 'HERON REACH', 'THE NARROWS', 'MUDSKIPPER', 'CROCODILE CREEK', 'TIDE ROOTS', 'THE MAZE'],
    crystal=['PULL OF THE DEEP', 'GEODE', 'QUARTZ VEIN', 'ECHO CHAMBER', 'AMETHYST HALL', 'LODESTONE', 'PRISM POOL', 'THE DARK DRIFT', 'HEART OF STONE'],
    autumn=['FIRST FALL', 'ACORN WALK', 'MAPLE ROW', 'THE LEAF PILE', 'BRAMBLE GATE', 'OWL HOLLOW', 'HARVEST MOON', 'FOX RUN', 'THE OLD OAK'],
    zen=['RAKED STONE', 'KOI POND', 'MOON BRIDGE', 'THE TEAHOUSE', 'BAMBOO GROVE', 'STEPPING STONES', 'LANTERN PATH', 'CHERRY FALL', 'STILLNESS'],
    starfall=['WISHING STAR', 'COMET TAIL', 'THE ORBIT', 'MOONWELL', 'NEBULA', 'STARDUST', 'ECLIPSE', 'SHOOTING STARS', 'THE FIRMAMENT'],
)
DOCS = dict(fork='a fork either side of a hazard', quick='quicksand that swallows a ball that stops in it', sandfield='a scatter of bunkers', pond='a pond beside the line',
    posts='palms in the way', slope='a tilted green', ventgap='a gap the vents puff you over', bumpers='bumpers that kick the ball away',
    vines='a vine patch that grabs the ball', kick='a kicker that throws the ball north', boost='a boost pad', mill='a windmill guarding the gap in a wall',
    deckgap='a water gap with a narrow deck across', conveyor='a moving belt across the fairway', kickgap='a drop that only a kicker throws you over',
    portalgap='a gap crossed by a portal pair', ridge='a windy ridge', bridge='a gap with a narrow bridge', pulse='a pulse wall that swaps with every shot',
    gate='a wall with a gate that a plate opens', tunnel='a wall with a tunnel under it', walls='broken walls', lily='a channel crossed on lily pads that sink',
    stream='a stream that carries the ball sideways', mud='mud', logs='logs across the line', wells='a gravity well that bends the roll',
    leaves='leaf piles', trees='trees in the way', gravel='raked gravel', stones='standing stones')
HILL = 'Y'

def ok(id_):
    r = R.get(id_)
    if not r or r['coast'] != 'true' or float(r['stray']) > 3 or int(r['ms']) > 2500: return False
    b = int(r['best'])
    if b < 2 or b > 4: return False
    kinds = set(B[id_][0].get('kinds', '').split(','))
    tw = R.get(id_ + '~plain')
    if kinds & GAPS and tw and tw['best'] != '0' and int(tw['best']) <= b: return False
    return True

def pick(theme):
    rnd = random.Random(theme)
    ids = [i for i in B if i.startswith(theme + '-') and '~' not in i and ok(i)]
    seed = lambda i: int(i.split('-')[2])
    sig = THEMES[theme]['sig']
    teach = [i for i in ids if seed(i) < 4 and i.split('-')[1] in 'SM' and int(R[i]['best']) == 2 and sig in B[i][0]['kinds']]
    first = sorted(teach, key=lambda i: (i.split('-')[1] != 'S', seed(i)))[0]
    pars = [4, 3, 5, 3, 4, 4, 3]
    rnd.shuffle(pars)
    pars.append(5)
    chosen, seen_kinds, used_seeds = [first], set(B[first][0]['kinds'].split(',')), {(first.split('-')[1], seed(first))}
    for par in pars:
        pool = [i for i in ids if int(R[i]['best']) == par - 1 and i not in chosen and seed(i) >= 4]
        if not pool: pool = [i for i in ids if int(R[i]['best']) == par - 2 and i not in chosen and seed(i) >= 4]
        def score(i):
            k = set(B[i][0]['kinds'].split(','))
            return (len(k - seen_kinds) + 0.5 * len(k) + (0.6 if 'fork' in k else 0), sig in k, rnd.random())
        best = max(pool, key=score)
        chosen.append(best); seen_kinds |= set(B[best][0]['kinds'].split(','))
    return chosen

def kotlin(theme, chosen):
    T = THEMES[theme]
    out, names = [], []
    for n, id_ in enumerate(chosen):
        props, rows = B[id_]
        par = int(R[id_]['best']) + 1
        kinds = [k for k in props['kinds'].split(',') if k]
        doc = 'Past ' + ' then '.join(DOCS[k] for k in kinds) + '.' if kinds else 'Tee to green.'
        kn = f'{OBJ[theme].upper()}_{n + 1}'
        names.append(kn)
        extra = ''
        if 'wind' in props: extra += f"        windDeg = {float(props['wind'])}f,\n"
        if 'mills' in props:
            ms = []
            for m in props['mills'].split(';'):
                v = m.split(',')
                ms.append(f'Windmill({int(float(v[0]))}, {int(float(v[1]))}, length = {float(v[2])}f, omega = {float(v[3])}f, phase = {float(v[4])}f)')
            extra += f"        windmills = listOf({', '.join(ms)}),\n"
        body = '\n'.join(f'            "{r}",' for r in rows)
        out.append(f'''    /** {doc[0].upper() + doc[1:]} ({id_}) */
    val {kn} = Hole(
        name = "{NAMES[theme][n]}",
        par = {par},
{extra}        rows = listOf(
{body}
        ),
    )
''')
    style = T['style']
    return f'''package com.theprobyte.golfalone.game

import com.theprobyte.golfalone.render.Mood

/**
 * {T['name'].title()}: nine holes laid out by the seeded generator (tools/holegen) and kept
 * only where the bot proved them: par is its best plus one, and any gap's helper is really needed.
 */
object {OBJ[theme]} {{

{chr(10).join(out)}
    val COURSE = Course("{theme}", "{T['name']}", mood = Mood.{T['mood']}, style = "{style}",
        holes = listOf({', '.join(names)}))
}}
'''


# ---------------------------------------------------------------- second rounds (--hard)

HARD_MOOD = dict(oasis='DESERT_NIGHT', mushroom='SPORELIGHT', carnival='NEON', sky='SUNSET_SKY', clock='RUST',
                 mangrove='MIST', crystal='DEEP_CAVE', autumn='FIRST_FROST', zen='MOON_GARDEN', starfall='AURORA')
NAMES_II = dict(
    oasis=['SANDSTORM', 'DRY WELL', 'THE SINKHOLE', 'NOMAD ROAD', 'SALT CARAVAN', 'BONE DUNES', 'MOON MIRAGE', 'VIPER PASS', 'THE LAST WELL'],
    mushroom=['SPORE STORM', 'INKCAP', 'THE ROT', 'BRACKET STEPS', 'DEATHCAP', 'FUNGAL DEEP', 'GLOW ROT', 'TRUFFLE RUN', 'THE OLD RING'],
    carnival=['NIGHT SHIFT', 'NEON ALLEY', 'THE GHOST TRAIN', 'DODGEMS', 'HOUSE OF MIRRORS', 'TOP PRIZE', 'THE BIG WHEEL', 'LOOP THE LOOP', 'CLOSING TIME'],
    sky=['RED SKY', 'DUSK DIVE', 'THE CORNICE', 'WIND SHEAR', 'SKY BRIDGE', 'TALON ROCK', 'LAST LIGHT', 'THE SPIRE', 'EVENING STAR'],
    clock=['SEIZED', 'RUSTED GEARS', 'THE ESCAPEMENT', 'BROKEN SPRING', 'COUNTERWEIGHT', 'THE CHIME', 'OVERWOUND', 'DEAD BEAT', 'THE LAST HOUR'],
    mangrove=['FOG BANK', 'THE SNARL', 'SUNKEN ROOTS', 'GATOR HOLE', 'MIST POOL', 'DEAD WATER', 'THE TANGLE', 'EEL CREEK', 'THE HEART'],
    crystal=['THE DEEP', 'BLACK GEODE', 'THE CHASM', 'LODE LINE', 'SHARD FIELD', 'UNDERTOW', 'OBSIDIAN EYE', 'THE ABYSS', 'CORE'],
    autumn=['FROSTBITE', 'BARE BRANCH', 'THE HOLLOW OAK', 'FROZEN BROOK', 'SLEET', 'COLD HARVEST', 'WOLF TRACK', 'THE THAW', 'WINTER GATE'],
    zen=['MOONSTONE', 'NIGHT KOI', 'THE STILL POOL', 'SHADOW BRIDGE', 'BELL TOWER', 'SILENT STEPS', 'PAPER LANTERN', 'MOON GATE', 'THE VOID'],
    starfall=['AURORA', 'SOLAR WIND', 'THE COMET', 'DARK MATTER', 'SUPERNOVA', 'MILKY WAY', 'EVENT HORIZON', 'STAR NURSERY', 'THE GREAT BEYOND'],
)
TOL = (0.40, 0.75)

def ok_hard(id_):
    r = R.get(id_)
    if not r or r['coast'] != 'true' or float(r['stray']) > 3 or 'tol' not in r: return False
    t = float(r['tol'])
    if not (TOL[0] <= t <= TOL[1]): return False
    kinds = set(B[id_][0].get('kinds', '').split(','))
    tw = R.get(id_ + '~plain')
    b = int(r['best'])
    if kinds & GAPS and tw and tw['best'] != '0' and int(tw['best']) <= b: return False
    return 2 <= b <= 5

def pick_hard(theme):
    rnd = random.Random(theme + '2')
    sig = THEMES[theme]['sig']
    ids = [i for i in B if i.startswith(theme + '-H') and '~' not in i and ok_hard(i)]
    best = lambda i: int(R[i]['best'])
    kinds = lambda i: [k for k in B[i][0]['kinds'].split(',') if k]
    # Hole 1: a par 4 with the signature and one more challenge; the closer a par 5.
    pars = [3, 4, 4, 4, 5, 5, 5]
    rnd.shuffle(pars)
    pars = [4] + pars + [5]
    chosen, seen = [], set()
    for n, par in enumerate(pars):
        def fits(i, b):
            if i in chosen or best(i) != b: return False
            return n != 0 or (sig in kinds(i) and len(set(kinds(i))) >= 2)
        pool = [i for i in ids if fits(i, par - 1)] or [i for i in ids if fits(i, par - 2)]
        def score(i):
            k = set(kinds(i))
            return (2 * (sig in k) + len(k - seen) + 0.5 * len(k) - float(R[i]['tol']), rnd.random())
        pick = max(pool, key=score)
        chosen.append(pick); seen |= set(kinds(pick))
    return chosen

def kotlin_hard(theme, chosen):
    T = THEMES[theme]
    obj = OBJ[theme] + 'II'
    out, names = [], []
    for n, id_ in enumerate(chosen):
        props, rows = B[id_]
        par = int(R[id_]['best']) + 1
        ks = [k for k in props['kinds'].split(',') if k]
        doc = 'Past ' + ' then '.join(DOCS[k] for k in ks) + '.' if ks else 'Tee to green.'
        kn = f'{OBJ[theme].upper()}_II_{n + 1}'
        names.append(kn)
        extra = ''
        if 'wind' in props: extra += f"        windDeg = {float(props['wind'])}f,\n"
        if 'mills' in props:
            ms = []
            for m in props['mills'].split(';'):
                v = m.split(',')
                ms.append(f'Windmill({int(float(v[0]))}, {int(float(v[1]))}, length = {float(v[2])}f, omega = {float(v[3])}f, phase = {float(v[4])}f)')
            extra += f"        windmills = listOf({', '.join(ms)}),\n"
        body = '\n'.join(f'            "{r}",' for r in rows)
        out.append(f"""    /** {doc[0].upper() + doc[1:]} ({id_}, tolerance {R[id_]['tol']}) */
    val {kn} = Hole(
        name = "{NAMES_II[theme][n]}",
        par = {par},
{extra}        rows = listOf(
{body}
        ),
    )
""")
    return f"""package com.theprobyte.golfalone.game

import com.theprobyte.golfalone.render.Mood

/**
 * {T['name'].title()} II: the harder second round, opened by finishing a round of {T['name'].title()}.
 * Laid out by the generator's hard setting (tools/holegen) and kept only where the bot proved
 * it: par is its best plus one, and every hole is less forgiving than the course average
 * (tolerance {TOL[0]}-{TOL[1]}: the share of near-miss shots that still make par).
 */
object {obj} {{

{chr(10).join(out)}
    val COURSE = Course("{theme}2", "{T['name']} II", mood = Mood.{HARD_MOOD[theme]}, style = "{T['style']}",
        base = {OBJ[theme]}.COURSE, holes = listOf({', '.join(names)}))
}}
"""

def emit_hard(themes, force):
    for theme in themes:
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../app/src/main/java/com/theprobyte/golfalone/game', f'{OBJ[theme]}II.kt')
        if os.path.exists(path) and not force:
            print(theme, 'II exists; pass --force to replace it'); continue
        ch = pick_hard(theme)
        print(theme + '2', [(i, int(R[i]['best']) + 1, R[i]['tol']) for i in ch], 'par', sum(int(R[i]['best']) + 1 for i in ch))
        open(path, 'w').write(kotlin_hard(theme, ch))

if __name__ == '__main__':
    args = sys.argv[1:]
    if '--hard' in args:
        force = '--force' in args
        cands = [a.split('=', 1)[1] for a in args if a.startswith('--cands=')]
        for p in (cands[0].split(',') if cands else []):
            b, r = load(p); B.update(b); R.update(r)
        emit_hard([a for a in args if not a.startswith('--')] or list(THEMES), force)
        sys.exit(0)
    for theme in (sys.argv[1:] or THEMES):
        ch = pick(theme)
        print(theme, [(i, int(R[i]['best']) + 1) for i in ch], 'par', sum(int(R[i]['best']) + 1 for i in ch))
        open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../app/src/main/java/com/theprobyte/golfalone/game', f'{OBJ[theme]}.kt'), 'w').write(kotlin(theme, ch))

# Hole generator (courses 10-19)

Lays out holes from a seed, has the bot prove them, and writes the Kotlin courses.

```
python3 batch.py cand_both.txt 24                        # S, M and L holes for every theme
SIZES=X python3 batch.py cand_x.txt 30 && cat cand_x.txt >> cand_both.txt   # long four-bend holes
cd ../.. && ./gradlew testReleaseUnitTest --tests '*CandidateProofTest' -Pcandidates=$PWD/tools/holegen/cand_both.txt
cd tools/holegen && python3 select_emit.py [theme ...]   # writes app/.../game/<Course>.kt
```

- `gen2.py` paints a winding island from tee to green with the theme's challenges across it
  (gaps with a kicker, vent or portal over them, pulse walls, gates, forks round a hazard,
  guarded greens). Each candidate also gets a twin with its helpers stripped.
- `CandidateProofTest` writes `<file>.results`: the bot's best, and whether the drawn coast
  matches the physics.
- `select_emit.py` keeps holes the bot finishes in 2-4 (par = best + 1), where the drawn coast
  matches physics, and where a gap really needs its helper (the twin can't do it as well). It
  picks nine per course: hole 1 brings in the course's mechanic with no other challenge, then a mix of par 3s, 4s and
  5s, favouring holes with more going on.

**The committed `.kt` course files are the source of truth.** `select_emit.py` overwrites them.
The shipped courses came from a run before the theme seed was made stable (it used Python's
per-process `hash()`), so the candidate ids in their comments name that run, and a rerun now
gives a different (but repeatable) batch. Don't regenerate a shipped course unless you mean to
replace it.

## Second rounds

**Courses 10-19** (`hard=True`):
```
HARD=1 SIZES=SMLX python3 batch.py cand_hard.txt 36 oasis mushroom ...   # narrower, busier, smaller greens
cd ../.. && ./gradlew testReleaseUnitTest --tests '*CandidateProofTest' -Pcandidates=$PWD/tools/holegen/cand_hard.txt -Ptolerance=1
cd tools/holegen && python3 select_emit.py --hard --cands=cand_hard.txt [--force] [theme ...]   # writes <Course>II.kt
```
The hard setting gives every stretch a challenge (a band across the fairway plus a scatter on
the tee side of long stretches), a narrower fairway with side bays and a rough border 1-3 tiles
wide, a smaller green with the cup off centre by its guard, and more slopes. `select_emit.py
--hard` keeps holes whose bot best is 2-5 and whose **tolerance** is 0.40-0.75: the share of
near-miss shots (one step off in angle or power, for each shot of the bot's plan) that still
make par. The shipped first rounds average 0.81. Hole 1 is a par 4 with the course's signature
and one more challenge; the closer is a par 5.

**Courses 1-9** (`remix.py`): the same islands, reworked. Only land becomes other land, so the
coast can't change: the cup moves on the green (sometimes shrinking it), the tee may move
back, and the course's own hazards go beside the line, never on crab patrols, tidal sand,
scenery or windmill sweeps.
```
python3 remix.py ../../app/src/main/java/com/theprobyte/golfalone/game/Lighthouse.kt light 30 > cand_remix.txt
(prove as above, with -Ptolerance=1, and -PshippedTolerance=<file> for the originals' figures)
python3 select_remix.py cand_remix.txt <shipped tolerance file> [--force]   # writes <Course>II.kt
```
A rework is kept when its best is the original's or one more and its tolerance is lower than
the original's, both measured at best + 1 (`-PshippedTolerance=<file> -PatBest=1`). It must
also make par without the tide or ramps (the proof reports `dry=` and `flat=`).

The committed `*II.kt` files are the source of truth for the second rounds, as for the first:
the generator has changed since (logs and lone rocks are kept off the shore), so a rerun gives
different holes.

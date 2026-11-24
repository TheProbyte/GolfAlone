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

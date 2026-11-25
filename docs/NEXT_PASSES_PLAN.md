# Golf Alone: the next passes (levels, visuals, animations, performance)

> **Status:** built (passes 0-9). What shipped differently from the plan is listed at the end.
>
> **Recommendation in one line:** give every course a harder **second round**, with its own course id,
> instead of adding holes to the shipped courses. Before that, spend two short passes on
> measurement and load stalls, because twice the holes means twice the work for the tests and the
> terrain builder.
>
> A figure marked **Evidence** was read from the code or from the last test run. One marked **Guess**
> is an estimate. No frame time below has been measured on a phone yet.

## 0. Decisions (the first option is the recommended default)

| # | Decision | Recommended | Alternative |
|---|---|---|---|
| D1 | More holes per course, or two versions? | **Two versions.** Each course gets a harder second round, as a separate `Course` with its own id. | Grow the courses to 18 holes, or grow courses 1-9 to 9 holes. |
| D2 | Is the second round part of the unlock chain? | **No.** It opens when you finish a round of its own first version. The chain to Starfall stays as it is. | Chain through both versions, which doubles the cost of reaching later courses. |
| D3 | What opens it? | **Finishing a round at any score**, the way courses open today. | Finishing at or under a target score. |
| D4 | Do courses 1-9 grow to nine holes? | **No.** Their first versions stay at six holes, and their second rounds are six reworked holes. | Paint three more holes per course by hand. |
| D5 | What is it called? | Working name "II" (`MIRAGE OASIS II`), with its new time of day under the name. The final name is the user's call. | "Back tees", "Late round". |

---

## 1. Levels

### 1.1 Why two versions

| Question | More holes per course | A harder second round (recommended) |
|---|---|---|
| Can the generator and the bot carry it? | Yes for courses 10-19. Not for 1-9: the brush scripts for courses 2-9 aren't in the repo (`tools/` holds only `holegen`), and `gen2.challenge` has no tide, crabs, barrels, planks, ledges, ice, obsidian or shallows. | Courses 10-19: new holes from the same generator, with a difficulty setting. Courses 1-9: a small remix tool edits the committed maps, and the bot proves them again. |
| Saves | A new hole count makes the old `course_<id>` bests meaningless and leaves `run_<id>_scores` short (`Progress.courseBest`, `runScores`). | A new id (`oasis2`) gets its own `best_`, `course_`, `run_` and `stat_` keys, with no migration. Every key in `Progress` already carries the course id. |
| Cost to reach | Opening Starfall takes 18 rounds and 135 holes today (`GameView.courseOpen` chains on the list order). 18-hole courses would roughly double that. | Nothing is added to the chain. Each second round is one finished round away. |
| Does it get harder? | It adds length, not difficulty. | Difficulty is the point, and it is measured (1.4). |
| Driftwood Cove | Adding holes changes its par and best. | `Holes.COVE` is untouched, so `GoldenShotsTest` is unaffected. |

For courses 10-19 the second round can't reuse the same islands. The shipped files came from a
generator run made before the seed was stable, and `select_emit.py` overwrites `<Course>.kt`.
Their second rounds are new islands in the same theme.

### 1.2 What ships

| Courses | Second round | Holes | Par (the bot's best + 1, as now) |
|---|---|---|---|
| 10-19 (generated) | Nine new holes per theme, from `gen2.make(..., hard=True)` | 90 | About 39: one par 3, four par 4s and four par 5s. A par 6 closer only if the first one-theme test run produces one. The first versions are par 34. |
| 2-9 (painted) | The same six islands, reworked by `remix.py`: cup and tee moved, hazards added | 48 | Whatever the bot proves; expect 0-1 more per hole. |
| 1 (Driftwood Cove) | Six reworked holes in new `Hole` objects, picked from remix candidates and tuned by hand | 6 | As proven. |

That is 144 new holes, 288 in all. These counts are targets: how many usable holes the generator
gives at the hard setting is unknown, so pass 2 starts with a one-theme test run.

### 1.3 Generator changes (`tools/holegen`)

| # | Today | Hard setting |
|---|---|---|
| G1 | `make`: the fairway brush has a radius of 2.3-4.0 tiles (2.2-2.9 on size X). | 1.8-2.6, never under 2 tiles of fairway. |
| G2 | `make`: one challenge per stretch (`zip(kinds, stretch)`), and one stretch left empty (`nb` challenges for `nb + 1` stretches). | Every stretch gets a challenge. Long stretches get a pair, from a whitelist of pairs: a band across the fairway (gap, wall, stream) plus a scatter (posts, bumpers, bunkers, wells, mud) on the tee side. |
| G3 | `make`: green radii 3.4-4.6 by 2.8-3.4, the cup dead centre, a 0.3 chance of slopes. | Radii 2.6-3.4 by 2.2-2.8, the cup off centre towards the guard, a 0.7 chance of slopes. |
| G4 | `make`: one guard beside the green, with a 0.75 chance. | Always one, and one on both sides half the time. |
| G5 | `make`: `fork` is weighted twice in the pool. | Weighted once; the course's signature mechanic appears in at least six holes. |
| G6 | `select_emit.ok`: keeps holes where the bot's best is 2-4, and rejects any that take over 2500 ms to prove. | Keeps bests of 3-5 (2 for one par 3). The time limit is raised after P7, because it currently throws out deep holes. |
| G7 | `select_emit.pick`: hole 1 is a teaching hole with one challenge. | No teaching hole; hole 1 is a par 4 with the signature mechanic and one other challenge. |
| G8 | `select_emit` writes `<Course>.kt`. | Writes `<Course>II.kt`, and refuses to overwrite an existing file without `--force`. The chosen candidate ids and the seed are committed, so a rerun gives the same course. |

### 1.4 Measuring "harder"

Par stays the bot's best plus one, so par can't show how hard a hole is. `CandidateProofTest`
gains one more number per hole, its **tolerance**:
- For each shot in the bot's plan, try the eight neighbouring shots: one step of angle, one of
  power, or both.
- The tolerance is the share of those shots after which `ShotPlanner` can still finish within par.

Run it first over the 144 shipped holes for a baseline. Then set the hard range from that: lower
than the first versions, with a floor so that no hole needs one exact shot.

`HoleCompletableTest` keeps its rules for every new hole: par is reachable, ramps and the tide are
never needed, and no par 4 or 5 can be aced. `Holes.everyHole` must include the second rounds.

### 1.5 The remix tool for courses 1-9 (`tools/holegen/remix.py`, new)

- It reads the map rows from the committed `.kt` file. Each new hole keeps the original's
  `tideStart`, `crabs`, `lighthouse`, `lanterns`, `windmills` and `windDeg`.
- Its first version only turns land into other land, so the coast can't change:
  - move the cup within the green, or shrink the green round a new cup;
  - move the tee;
  - add the course's own hazards beside the line (mud, buoys, coral, vents, snow, vines, walls).
- It never edits a crab's patrol tiles or a tidal tile.
- Candidates go through `CandidateProofTest` (best, coast, stray, tolerance) and the
  `HolePreviewTest` PNGs, and a person picks from them. Reworked hand-made holes need an eye, and
  this is the main quality risk of the pass.

### 1.6 Unlocking, progress and the title

| # | Change | Where |
|---|---|---|
| L1 | `Course` gains `base: Course?` and `theme` (the first version's id). `Holes.COURSES` stays at 19, so the unlock chain, the 19 dots and the ten-tap switch test are unchanged. `Holes.course(id)` also finds second rounds. | `game/Holes.kt` |
| L2 | A second round is open when `courseBestOf(base.id) > 0`. | `GameView.courseOpen` |
| L3 | The title's course card gets a two-part chip (I / II) under the name. When the second round is locked, it reads "FINISH <course> TO OPEN". A dot gains a mark once its second round is finished. | `TitleScreen.drawTitle`, a new `B_VARIANT` |
| L4 | The first finished round of a course announces its second round as well as the next course. | `GameView.nextHole` (`opened`) |
| L5 | Life and sound follow `course.theme`, not `course.id`. | `loadHole` (`ambient.reset(terrain, course.id)`), `Sfx` |
| L6 | The Book already follows the chosen course, so second rounds get their own stats and postcards. | `BookScreen.kt` |

**Moods:**
- Courses 1-9 take an existing mood for their second round (for example Saltmarsh at `NIGHT`,
  Lighthouse in `STORM`), so they need no new palette work.
- Courses 10-19 need care, because `Palette.own` is keyed by `Mood`: a new mood for Oasis would lose
  the desert colours. Add a `ramps: Mood?` field to `Mood`, and read `own[m.ramps ?: m]` in
  `Palette.setMood`. `PaletteMoodTest` loops over `Mood.values()`, so it checks every new mood
  automatically.

---

## 2. Visuals

| # | What's weak today | Next |
|---|---|---|
| V1 | **Islands are strips.** `gen2.make` draws one line with a round brush, and `Map.border` adds exactly one tile of rough all round. | In the generator, for new holes only: side lobes and coves, a beach on south shores, a rough border 1-3 tiles wide, and meadows where a stretch widens. All of this changes the coast, so `CandidateProofTest` checks it. Shipped holes aren't reshaped, because that would change how they play. |
| V2 | **Banks are shared.** Only the ember, sky and frost styles have their own bank face in `Terrain.basePixel`; the other generated courses get the default sand bank. | A bank per style: sandstone (desert), roots (mushroom, mangrove), pilings (carnival), brass (clock), rock (crystal), cut stone (zen), earth (autumn). Banks are outside the 3 px coast check by design. |
| V3 | **Pieces are simple glyphs.** The kicker is a grey tile with a gold arrow (`Terrain.padPixel`), the boost pad a bolt (`boostPixel`), the well three rings (`wellPixel`) and the portals two rings (`Bodies.draw`). Pulse walls and gates are flat rectangles, tunnels two ovals, and windmill blades a line of pixels. | Sprites drawn once from text grids in `Sprites` (style rule R8), with a skin per `hole.style`: a spring board, chevrons, a swirl, a brass piston in Clockwork, a growing crystal in the Caverns. They rebuild for each mood through `Sprites.b()` and appear in `SpriteSheetTest`. This is also performance fix P5. |
| V4 | **Trees are shared.** `GameWorld.drawWorld` draws a cactus on the desert course and an autumn crown on a palm trunk; every other course gets the palm. `Sprites.underCanopy` uses the palm's outline. | A tree per style, each with its own canopy outline: a giant mushroom, a striped lamp post, a wind-bent pine, a brass pylon, a mangrove on stilt roots, a crystal spire, a cherry tree. The trunk stays inside the post's radius, so the physics doesn't change. |
| V5 | **Bushes are shared.** `Terrain.decorate` stamps one 4×3 bush on every course. | Scenery per style. Nothing may look like a hazard; for example no leaf heaps in Autumn Woods, where leaf piles are a hazard. |
| V6 | **The sea is one tile.** `Terrain.buildSea` is flat `DEEP` with crest dashes, repeating every 78×72 px on every course. The foam in `waterPixel` reaches the same distance all round an island. | Sea detail per course in the tile: star reflections (Starfall), small ice floes (Frost), longer crests (storm), glowing specks (Caverns). A sparse second swell layer hides the repeat. More foam on the windward shore. Nothing offshore is brighter than `SEA[3]` or bigger than the ball, so it never reads as land. |
| V7 | **The sky is haze.** `Terrain.skyAt` is one colour with cloud blobs. | Two cloud layers, the far one moving at half the camera's speed. About 28 more bitmap draws a frame (Guess). |

**Risk for V6 and V7:** the glide between holes keeps the sea pattern aligned using `SEA_H`
(`GameView.updateGlide`). Any new layer must repeat on that same height, or the water jumps
mid-glide.

---

## 3. Animations

- Everything decorative runs on `steppedTime` (15 Hz), so the game can still drop to 30 fps when
  idle (`RedrawPolicy`).
- Decorative animation is off under reduce motion. Anything that shows a change of state still
  shows its end state at once under reduce motion.

### Feel (shows you what the shot did)

| # | Item | Cost per frame |
|---|---|---|
| A1 | **The ball squashes** for one frame when it hits a wall or bumper. `Sprites.ball` has spin, a ghost and lift but no squash today. | One sprite swap. |
| A2 | **Pulse walls rise and sink** over 0.2 s when the shot ends, instead of swapping at once. | One sprite per wall tile, as now. |
| A3 | **Gates sink** when their plate is pressed; lily pads dip and leave bubbles; thin ice breaks in two frames. | A few sprites for 0.3 s. |
| A4 | **Kicker boards spring** when they throw the ball (`onKick`); boost chevrons flash (`onBoost`). | One sprite each. |
| A5 | **Portals:** a ring closes at the way in and opens at the way out. This is drawing only; the physics still moves the ball at once. | Two rings for 0.15 s. |
| A6 | **Quicksand swallows the ball** with its own sinking frames. Check what `onSplash` shows for quicksand now. | One sprite. |
| A7 | **The cup:** the ball circles the rim on a lip-out, and the flag dips as the ball drops. | Nothing extra. |

### Decoration

| # | Item | Cost per frame |
|---|---|---|
| A8 | **Pieces move when idle:** lights chase round the carnival bumpers, cogs turn in Clockwork, crystals glint. | Sprite frames only. |
| A9 | **Life for courses 10-19:** falling petals (Zen), falling leaves (Autumn), shooting stars (Starfall), spores (Mushroom). Today these courses reuse the crabs, fireflies, fish and sheep (`Ambient.reset`). | Under 150 pixels a frame, held there by the budget test (M2). |
| A10 | **Transitions:** an "open" stamp for the second round on the final scorecard; scores count up on the scorecard. | UI layer only, while it runs. |
| A11 | **The hole card:** the flag you earned runs up its pole; the round's strip of hole scores fills in one by one. | UI layer only. |

Not planned: hints on the course, a loft shot, or any slow motion beyond the ace and eagle finish
the game already has.

---

## 4. Performance

### 4.1 Measure first

| # | Instrument | What it answers |
|---|---|---|
| M1 | **A second page on the frame overlay** (`FrameOverlay.drawFrameTimes`), laid out to fit one screenshot. It shows: time per part of the frame (sea, map, bodies, life, effects, UI, final draw); pixels, rectangles and bitmaps drawn this frame (counters in `Pix`); how long this hole's terrain and water took to build; the longest main-thread stall on this hole; memory in use. | Where a frame's time goes on the phone. |
| M2 | **`DrawBudgetTest`** (Robolectric) draws one frame at the tee and one at the cup on every hole, and checks the `Pix` counters stay under a budget. Counts are exact, where timings in CI are noisy. | Stops a new piece or course quietly adding a thousand pixel draws. |
| M3 | **A performance lap** run from the overlay: the camera pans along each hole of the course, and the page lists the worst and 95th-percentile draw time per hole. | One screenshot per course. It measures drawing only, not a rolling ball. |
| M4 | **An honest start-up time.** `createdAt` is set after `course`, whose initialiser builds all 144 `Hole` objects (`Holes.COURSES by lazy`), so the start-up time leaves that out. Start the clock in `MainActivity.onCreate` instead. | Matters more at 288 holes. |

### 4.2 What the code shows

| # | Slow path | Fix | Expected win (Guess) |
|---|---|---|---|
| P1 | **Water frames can be built on the main thread.** `loadHole` only prepares water for the *next* hole. For the hole being loaded, `drawWorld` calls `Terrain.water(wf)`, which runs `buildWater`: 8 frames of up to 98,560 pixels each on a 22×70 map, with a `sin` call per pixel in `waterPixel`. This happens on the first hole after launch, after a course switch, on Continue and on any practice jump. | Build the water in the builder task before the hole is shown, and take the `sin` out of the per-pixel loop. | Removes the largest single stall; perhaps 100-300 ms on a phone. |
| P2 | **`loadHole` waits on `terrains[i].get()`.** All of a course's maps queue on one thread. Practising hole 9 just after a course switch, or Continue on a late hole at launch (`ready` only waits for hole 1), freezes the UI until every earlier map is built. One map takes at most about 100 ms on the desktop JVM (`HolePreviewTest`: 30 s for 144 holes, each built twice). | Build the wanted hole first, and hold the fade until it's ready. | A freeze of 1-3 s becomes a slightly longer fade. |
| P3 | **Nothing in `Bodies.draw` is skipped when off-screen.** Every loop walks all its tiles on a 70-row map, though about 50 rows are on screen. | Skip tiles outside the view. | About 30% fewer piece draws on long holes. |
| P4 | **The plank loop scans every tile, every frame, on every hole:** 1,540 `plankAt` calls on a 22×70 map, even when it has no planks. | Check `plankCount` first, and loop over `tilesOf(Tile.PLANK)`. | Small, but free. |
| P5 | **One `drawRect` per pixel.** `Pix.px` draws a rectangle per pixel. `buoy` and `barrel` shade every pixel in loops; each windmill blade is about 130 pixel draws, with trigonometry; belts and ridge wind draw a dozen pixels per tile. | Turn them into sprites (V3), and pre-draw windmill blades at 16 angles. | The largest draw-time win on Carnival, Clockwork and Skyreach. |
| P6 | **Allocation every frame:** `arrayOf(Tile.PULSE_A, Tile.PULSE_B)` in `Bodies.draw`, and a new `RedrawPolicy.State` from `redrawState()` twice a frame. The UI layer also redraws on every title frame (`uiNeedsRedraw` is always true on the title). | Create the array once; reuse the state; redraw the title only when the 15 Hz clock moves. | Fewer garbage-collection pauses; about half the idle cost of the title. |
| P7 | **The bot is slow in tests.** `HoleCompletableTest` takes 75 s: 52 s proving the 144 holes one after another (50 s of it in the planner; the slowest hole takes 2.3 s), and 23 s of the random barrel test. It calls `solve(k)` from scratch for each k up to par. | First save all 144 bot bests to a file. Then search once up to par, and prove holes in parallel. `Physics` stays untouched. | About 2× from the single search and about 3× from four cores. The single search can change which shots it keeps, which is why the bests are saved first. |
| P8 | **The UI playthrough is the biggest part of CI:** 162 s, of which `theNewCourses` is 129 s. `HolePreviewTest` (30 s) builds every map twice. | Split `theNewCourses` so Gradle can run the parts at once; build each map once in the preview test. | Tests in CI drop from about 276 s to about 150 s. |
| P9 | **Memory.** Each `Terrain` keeps its per-pixel masks (`land`, `bank`, `kind`, `edge`, `pond`, `nearPond`, `lava`) for all nine holes: about 1.8 MB for a 22×70 hole, plus about 3 MB of water frames for two holes. | Measure with M1 first. If it matters, keep the masks only for the current and next holes. | Unknown until measured. |

Without P7 and P8, 288 holes would take the proof and preview tests from about 80 s to over 160 s,
or more if the hard holes need deeper searches.

---

## 5. Order of work

Each pass ships on its own. Passes 0-2 are worth doing first: they make everything after them
measurable and cheaper, and pass 2 finds out how many usable hard holes the generator gives.

| # | Pass | Scope | Risks | Checked by |
|---|---|---|---|---|
| 0 ✅ | **Measurement and CI time** | M1-M4, P7, P8. | The single search changes a bot best. | The saved bests file is unchanged; `GoldenShotsTest` passes. The user screenshots the new overlay page on Palm Grove, Grand Carousel and a Skyreach hole, as the baseline. |
| 1 ✅ | **Stalls and slow paths** | P1-P4, P6. | Water appearing a frame late. | `DrawBudgetTest`; a new test that a practice jump never builds water on the main thread; a performance lap on the phone, before and after. |
| 2 ✅ | **Second rounds: the groundwork and one course** | L1-L6, `Mood.ramps`, G1-G8, the tolerance number, and **Mirage Oasis II** only. | Too few usable holes at the hard setting; "harder" feeling unfair. | `HoleCompletableTest`, `PaletteMoodTest`, `HolePreviewTest`, `ProgressTest` for the new unlock, and a UI playthrough of the I / II chip. The user plays Oasis II and says whether it's hard in the right way. |
| 3 ✅ | **The other nine generated second rounds** | Generate, prove, pick and emit them, with the V1 shapes. | CI time; names and moods for nine courses. | As pass 2, plus PNGs of all 81 holes. |
| 4 ✅ | **Second rounds for courses 1-9** | `remix.py`; Cove II tuned by hand. | Reworked hand-made holes that play worse than the originals. | As pass 2; `GoldenShotsTest` shows the Cove is untouched; a check on the phone per course. |
| 5 ✅ | **Sprites for the pieces** | V3 and P5 together: Carnival first as a trial, then the rest. | A piece that no longer reads as what it is. | The `SpriteSheetTest` sheet, `DrawBudgetTest` with a lower budget, screenshots, then the phone. |
| 6 ✅ | **Trees, bushes and banks** | V2, V4, V5. | Scenery that looks like an obstacle; the see-through ball under new canopies. | Screenshots per course; `HolePreviewTest` (the coast is unchanged). |
| 7 ✅ | **Sea and sky** | V6, V7. | Sea alignment during the glide; detail offshore read as land. | The glide checks in `UiPlaythroughTest`; `PaletteMoodTest`; the phone. |
| 8 ✅ | **Feel animations** | A1-A7. | Timing that hides a change of state. | Screenshots mid-animation; reduce motion checked in the playthrough; the phone. |
| 9 ✅ | **Decoration and life** | A8-A11. | The idle frame rate creeping up. | `RedrawPolicyTest`, `DrawBudgetTest`, a performance lap. |

**Files most passes touch:**
- `tools/holegen/gen2.py` and `select_emit.py`
- `GameView.kt`, `GameWorld.kt`, `TitleScreen.kt` and `FrameOverlay.kt`
- `render/Bodies.kt`, `Terrain.kt`, `Sprites.kt`, `Palette.kt` and `Mood.kt`
- `game/Holes.kt` and `ShotPlanner.kt`
- the tests `HoleCompletableTest`, `CandidateProofTest` and `UiPlaythroughTest`

---

## What shipped, and what didn't

- **Second rounds:** all 19, outside the unlock chain, as D1-D4 recommended. Courses 10-19 are
  par 38-39 (nine holes each); courses 1-9 are par 18-22 (six holes each). Par is the bot's
  best plus one, so a reworked hole can have a lower par than its original while being tighter.
  Most reworks are measurably less forgiving. A few are harder only because they can no longer
  be aced (best 1 to 2), and one (Temple Cay II, hole 3) measures slightly more forgiving. The
  hole comments say which.
- **Not judged yet:** whether the second rounds feel hard in the right way. That needs playing
  on the phone.
- **Physics fix found on the way:** the drop after a splash could put the ball inside a wall
  edge, log or lone rock on the shore. It now keeps clear. One pinned best moved, so Dead Water
  (Mangrove Maze II, hole 6) is par 6.
- **Not built:**
  - M3, the camera's performance lap. The overlay's second block covers the same questions
    from a screenshot.
  - P9 (memory): the overlay shows heap, and nothing has been changed.
  - A7's flag dip and A11 (the hole card's flag and strip). The scorecard does fill in hole by
    hole.
- **Trees per style (V4)** show only where a hole has trees (palm tiles). Few generated holes
  do, so the new pine, lamp post, gear pylon, crystal spire and cherry tree are rare for now.
  The mangrove, cactus and autumn trees are common.
- **No phone timings yet.** Every figure here is a draw count or a JVM time. The overlay
  (long-press the logo) shows the per-phase split on the phone.

# Golf Alone: polish pass (efficiency, beauty, fun, QoL)

> **Status:** plan only. No rule changes in this pass: `GoldenShotsTest` and the planner's
> best scores must stay identical throughout. Strike or reorder anything by ID.
>
> **Board:** [`docs/style/time-of-day-board.png`](style/time-of-day-board.png) shows three moods
> as 1:1 crops of a real frame. It's **mood only**: a brightness filter. The game would swap
> colour ramps instead.

Rows say **evidence** when measured or seen, and **guess** when it's a bet.

---

## E. Efficiency (battery and startup, not draw time: drawing is already about 1.4 ms)

| # | Item | Why |
|---|---|---|
| E1 | **Fix the instrument first.** The overlay keeps its numbers in ring buffers and updates the text only twice a second (no sorting or formatting every frame). It shows a **worst-on-this-hole** figure, so one screenshot answers the question. Add the platform's `FrameMetrics` listener for bitmap upload (sync), GPU and total time. | **Evidence:** the overlay sorts 120 values and formats strings every frame, so its own cost may be your 10 ms p95. DRAW misses upload and GPU time, and two full bitmaps re-upload every frame. Palm Grove is still unmeasured. |
| E2 | **Idle throttle.** At rest (no touch, ball still, no sheet, fade or banner moving), draw at **30 fps** instead of 120. Touch wakes it in the same frame. Every at-rest animation is stepped to a rate that suits it: water 6 fps, flag 4, palms on pose changes, clouds and gull in whole-pixel steps, and the ball's ring and text pulses at about 15 steps a second. The policy is a pure function (`frameInterval(state)`) with its own unit test. | **Evidence:** it redraws at the panel's top rate forever, even on the title screen. The continuous animations are the ball ring, slope glints, gull, logo bob and pulsing hints. |
| E3 | **Startup:** measure time to first frame on the phone first, then pick a fix. Either build hole 1 off the main thread behind a simple splash, or cache the baked maps. | **Evidence:** First Light's ground takes 79 ms on a desktop JVM (more on a phone), and it's built on the main thread before the first frame. |
| E4 | **No allocations per frame:** the icon tint is cached per colour, HUD strings rebuild only when their values change, the tee-marker offsets become a constant, and the UI layer is redrawn and re-uploaded only when it changes (the old E1). | **Evidence:** `Sprites.icon` creates a colour filter on every draw, `"SHOTS $strokes"` is rebuilt every frame, and the tee markers use `intArrayOf(-7, 6)`. |
| E5 | **Ready for a second course:** save keys gain the course id (`best_cove_3`, not `best_3`), with a one-time migration of your current saves. `GameView` (1,661 lines) is split into a screen per file so later changes are safer. | **Evidence:** bests and unlocks are keyed by hole number alone, and the course is hard-coded. |

## B. Beauty

| # | Item | Why |
|---|---|---|
| B1 | **Time of day as a real ramp swap** per course (Driftwood Cove: see decision 1). `Palette` becomes per-course ramps instead of a singleton, and everything baked from it is rebuilt: sprites, the sea tile, cloud shadows, dissolves and thumbnails. **Acceptance:** an automated contrast check that fairway, rough, green, sand, water and the ball all stay distinct (in the dawn mockup the grass types drift together). | **Evidence** for the risk: the board's dawn panel. **Guess** for the payoff. |
| B2 | **The sun on the water:** a path of glints on the side the light comes from, drifting with the crests (stepped, 6 fps). | **Guess.** |
| B3 | **Ambient life**, stepped, away from the playing line, and off under reduce motion: crabs scuttling on beaches, butterflies near flower patches, fish rings in ponds, and a gull that sometimes lands on a wall. | **Guess.** It's scenery under rule R10, so none of it may look like an obstacle. |
| B4 | **Leftovers:** a 4-frame splash sprite, a landing dust puff sprite, and a stamped result on the hole card (BIRDIE lands like a rubber stamp, with a thump). | **Evidence:** carried over from the art pass. |
| B5 | **Between holes, glide the camera over the sea** to the next island instead of dissolving (a light version of the connected island), and give the title screen a slow pan across the course. Off under reduce motion. | **Guess.** |

## F. Fun (feel only, no rule changes)

| # | Item | Why |
|---|---|---|
| F1 | **Celebrations in tiers.** A hole-in-one or an eagle gets a slow-motion final roll, a camera punch-in, rising gulls and a longer jingle. A birdie gets the stamp and a sparkle. Par stays modest. | **Guess.** It's the cheapest delight available. |
| F2 | **More feel in the shot:** a 2-frame club swoosh at the ball, a trail that warms with power, and "SO CLOSE!" when the ball stops within a ball-width of the cup or lips out. | **Guess.** |
| F3 | **A stats sheet:** rounds, aces, birdies, best and average per hole, longest drive and jumps landed. It reads from records kept from now on. | **Guess.** It turns the course best into something to chase. |
| F4 | **Postcards:** finishing a hole under par earns a small pixel postcard of it (its map, your flag and your score), kept in a book on the title screen. | **Guess.** It collects what you've already done, not a new rule. |

## Q. QoL

| # | Item | Why |
|---|---|---|
| Q3 | **Volume sliders** for effects and surf (carried over). | **Evidence:** they're toggles today. |
| Q5 | **First-time tips** for sand, slopes, logs and ponds, one line each, shown once near the feature (like the ramp tip). | **Evidence:** only ramps have one. |
| Q7 | **Drag sensitivity** (short, normal, long pull), for small or large hands. | **Guess.** |
| Q8 | **A quick retry in practice:** a long press on the pause chip restarts the hole. | **Guess.** |
| Q9 | **Hide the status bar** (full-screen play). See decision 2. | **Evidence:** your screenshot shows the clock and icons over the course. |

## Parked (not in this pass)

- **The loft shot:** it waits on how you want to choose a loft.
- **Tightening pars:** it needs your scores, not the bot's.
- **New gameplay:** the tide (M3), wind, crabs as obstacles, and the second course.

---

## Milestones

| # | Milestone | Gate |
|---|---|---|
| **V1** | **E1 (the instrument), E2 (idle throttle), and B1 on First Light only** | On your phone: is it the right mood, does it feel as smooth, and what are the numbers on Palm Grove? |
| V2 | The rest of efficiency (E3–E5) | Golden shots unchanged, and saves migrate. |
| V3 | Beauty (B1 for all six holes, then B2–B5) | |
| V4 | Fun (F1–F4) | |
| V5 | QoL (Q3, Q5, Q7–Q9) | |

**Question for you:** which moment felt best so far, and which felt flat? (Your "barely touching
the water" note produced the best fix yet.)

# Golf Alone: water, trees and new features

> **Status:** you chose launch ramps (D1), W1 water with palm A (D2), and ramps and loft before
> the tide (D3), plus the penalty drop near where the ball went in (Q0d).
> - **Q0 is built.** The splash follows the drawn coast, and only once the whole ball is in. A slow
>   ball teeters (in a test, shots at 40–48% power towards the sea teeter, 49% and up go in). The
>   drop is near the entry point, and a wet band slows rolls.
> - **The slice is built:** W1 water at 8 frames, the tiling sea beyond the map, palm A on the
>   leaf ramp with swaying fronds and a canopy-shaped shadow, and the draw-time readout (Q6).
> - **Waiting on your phone check before step 2.** You said you're not fully sold on the palm and
>   grass yet.
>
> Strike or reorder anything by ID. The order of work lives in one
> place now: the **Roadmap** at the top of [PLAN.md](PLAN.md).
>
> **Candidates:** [`docs/style/water-trees-board.png`](style/water-trees-board.png) shows real
> engine frames at 1:1, made by `art/ArtCandidatesTest` with the game's own Terrain masks,
> Palette and Sprite code. The water moves in [`water-today.gif`](style/water-today.gif),
> [`water-w1.gif`](style/water-w1.gif) and [`water-w2.gif`](style/water-w2.gif).

---

## 1. Audit (measured at 1:1)

### Water

| # | Problem | Evidence |
|---|---|---|
| a | **Most of the sea is a flat fill.** The screen outside the hole's map is one solid colour, with no glints or waves. | **57%** of the screen on Driftwood, **52%** on The Saddle, 43% on The Spit, 36% on First Light, 18% on Tide Pools and Palm Grove. |
| b | **Open water inside the map is nearly empty too:** one tone with sparse 2 px glints. | 1:1 crops on the board. |
| c | **The depth bands use a 4×4 ordered dither**, which reads as a checkered ring rather than water. | The board's "today" column. |
| d | **The shore is a thin static foam line.** Nothing laps up the beach. | `waterPixel`, 4 frames. |
| e | **Ponds are drawn exactly like the sea.** | First Light, Tide Pools, The Saddle. |
| f | **The bank face is flat brown**, with no waterline or grass lip. | 1:1 crops. |

### Trees and objects

| # | Problem |
|---|---|
| g | **The palm canopy is eight rasterised lines.** It's spiky and blotchy, has no light or shade side, and its greens match the fairway, so it doesn't stand out. |
| h | **The palm's shadow is a flat oval**, and its sway shifts the whole sprite by a pixel instead of moving the fronds. |
| i | **A lone `#` rock draws as a square wall block**, not a boulder. |
| j | **There's no decoration beyond tufts and shells.** Bushes, reeds and beach rocks are missing. |

### Measuring

| # | Problem |
|---|---|
| k | **The frame readout (long-press the logo) can't set a budget.** It records the time *between* frames, not how long a frame takes to draw. |
| l | **Memory:** all six maps load at start, each with 4 full-map water frames: **5.9 MB**. Going to 8 frames would be **10.6 MB**. |

---

## 2. Three decisions (recommended defaults first)

| # | Decision | Options |
|---|---|---|
| **D1** | **What "ramps" means** | **Launch ramps** *(recommended)*: wooden ramps that throw the ball into the air, over water and low walls. This needs an airborne ball, the same engine work as the loft shot (E1). Or **raised ground**: slopes and terraces you roll up, with no airborne ball. |
| **D2** | **Water and palm** | **W1 Crests with palm A** *(recommended)*: clean depth bands, travelling wave crests, and a shore wash that laps up the beach and leaves wet sand. Or **W2 Caustics with palm B**: a coarser dithered gradient, rippling light in the shallows, and a rounder palm. It's livelier but busier. |
| **D3** | **Order** | **Airborne ball, then ramps and loft, before the tide** *(recommended)*, because tide holes get better with loft over flooded flats. Or the tide first. |

---

## 3. Art rules added this pass

| # | Rule |
|---|---|
| R9 | **Objects are hand-drawn string-grid sprites, with variants.** Trees, rocks and bushes are drawn by hand (2–3 variants each) and baked once. Ground and water stay procedural. |
| R10 | **Decoration never looks like an obstacle.** Anything that blocks the ball has a dark outline and casts a shadow. Decoration (tufts, flowers, shells, reeds, small pebbles) has neither and is at most 4 px. |
| R11 | **Leaves get their own deeper, cooler ramp**, so canopies stand out from the fairway at a glance. |
| R12 | **Water animates at 8 frames** (about 6 fps), and the sea beyond the map is a tiling texture of the same water. |

## 4. Art work

| # | Item |
|---|---|
| A1 | **The sea beyond the map:** a 64×64 tiling texture with the chosen water's open-sea motion, scrolled with the camera. That's 131 KB for 8 frames, and it fixes (a). |
| A2 | **The chosen water inside the map** (W1 or W2), at 8 frames, with the wash and wet sand on beaches. Built **lazily for the current and next hole only**, about 3.5 MB at worst instead of 10.6 MB. |
| A3 | **Ponds read as calm inland water:** a darker, still tone, a slower ripple, lily pads and reeds at the edges (decoration, under R10). |
| A4 | **Banks:** a grass lip overhanging the top edge, two bands of strata, and a wet dark line at the waterline. |
| A5 | **Palms:** the chosen canopy, plus two variants (leaning, small). Sway uses 3 frame poses of the fronds instead of shifting the sprite. The shadow is the canopy's own silhouette, dithered one ramp step down. |
| A6 | **Boulders:** a lone `#` draws as a round outlined boulder sprite (2 variants). Wall runs stay as built. |
| A7 | **Decoration (R10):** low bushes and flower patches in the rough, reeds by ponds, beach rocks and starfish on sand, all placed on a jittered grid and kept off fairway centre lines. |
| A8 | **Driftwood:** bleached grey-tan, with knots and split ends. |
| A9 | **Small effects:** a splash sprite (4 frames) instead of particles alone, a landing dust puff, and a flag that ripples over 3 frames. |
| A10 | **Time of day** (P10, carried over): the palette per course, dawn for the Cove. |

## 5. Features

| # | Feature | Notes |
|---|---|---|
| F1 | **Airborne ball** (engine) | The ball gets height `z` and vertical speed `vz` with gravity. In the air it has no friction or slope, and it passes over water, sand and walls lower than it. It lands with a bounce plus a dust puff or splash. This touches: landing on a solid tile (bounce off its top, or drop back if too low); the cup (no drop while airborne; you can fly straight over it); the planner (the same search with more steps, so time it); and the **3/4 view**, where "up the screen" is also north, so height must read clearly. The plan for that: the shadow always stays on the ground, the ball grows 1 px at the top of its flight, and it lands with a puff. |
| F2 | **Launch ramps** (if D1 = ramps) | Directional ramp tiles in the ASCII maps (letters to be chosen). A rolling ball that crosses one is launched at an angle set by its speed. Graphically they're wooden ramps with a shadow (R10). The planner proves every hole that uses them. |
| F3 | **Loft shot** (was E1) | Pull past an inner ring and the shot becomes a loft: it flies on F1 and gives up distance for clearing hazards. |
| F4 | **The tide** (M3, unchanged) | Saltmarsh's signature mechanic. |
| F5 | **Later, unchanged:** wind, crabs, Lighthouse Point, the connected island (D3), endless and daily holes, and the ghost of your best round. | |

## 6. QoL (from known rough edges, kept short)

### Q0. Forgiving water (your top annoyance: "barely touching the water edge was boom, a fail")

**Why it happens:** the splash check uses the hidden tile grid, but the coast is drawn smoothed.
The drawn sand reaches up to **2.5 px** past the physics edge on every hole (`HolePreviewTest`
measures it). The ball's centre only has to cross that invisible line for an instant splash, and
then it goes back to where you hit from, so any distance gained is lost as well.

| # | Fix |
|---|---|
| Q0a | **What you see is what counts.** The splash uses the same smoothed coastline that's drawn. The mask moves into the pure-Kotlin `game` package, and both Physics and Terrain use it. |
| Q0b | **The whole ball has to be in.** It splashes only once its centre is a full radius (2.5 px) past the drawn edge. Touching the foam line is still in play. |
| Q0c | **Teetering.** A slow ball (under about 40 px/s) that reaches the edge wobbles and stops on the lip, with a little "phew" tick. |
| Q0d | **Penalty drop near where it went in** *(recommended)*: +1 stroke, and the ball is placed on the shore one ball-width back from where it crossed the edge. This is real golf's penalty-area relief. The alternative is to keep today's "back to the last spot". |
| Q0e | **Wet sand.** A 3 px wet band at the water's edge has heavier friction, so long rolls tend to die on the beach. The planner re-proves every par. |

| # | Item | Why |
|---|---|---|
| Q1 | **Quitting while the ball rolls** finishes the shot first, so the ball is saved where it stops. | Today the stroke is kept but the ball goes back to its last lie. |
| Q2 | **Tap while the ball rolls to fast-forward it** (3×). | A long roll can take up to 18 s. |
| Q3 | **Volume sliders** for effects and surf, instead of on/off. | Arrow Unwind already has them to reuse. |
| Q4 | **Between holes in about 1.2 s:** shorter fades and the card taking input sooner. | It's about 1.7 s today. |
| Q5 | **First-time tips** for ramps, slopes, sand and lofts. One line each, shown once. | New mechanics are coming. |
| Q6 | **A draw-time readout:** the frame overlay also shows how long each frame takes to draw. | Needed to set the E3 budget, under (k). |

**What annoyed you while playing?** That decides what joins this list. Anything you name goes
in ahead of Q2–Q5.

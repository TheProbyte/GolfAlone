# Golf Alone: pixel-art style and thumb-first UI

> **Status:** plan only. This pass comes **before M3** (the tide). Strike or reorder
> anything by ID.
>
> **Style board:** [`docs/style/style-board.png`](style/style-board.png) is 1080 px wide, so on a
> Pixel one square is one game pixel at true size. The "today" crops are real 1:1 screenshots;
> everything else on the board is a mockup drawn outside the engine
> (`docs/style/styleboard.py`).

Driftwood Cove plays well. This pass is about how it looks and how it feels to hold. The goals:
- art with a consistent style, not "pixels made in code"
- every menu reachable with one thumb
- fewer taps between shots

---

## 1. Audit: what's wrong today (measured)

### Pixel art (judged from 1:1 crops)

| # | Problem | Where |
|---|---|---|
| a | Texture is per-pixel random noise, so rough, sand and stone cracks look like static. | `Terrain.kt` uses a hash per pixel |
| b | Shadows are tile-shaped rectangles (the dark bands under rocks and walls). | `groundPixel` shadow rule |
| c | The ball is 4 px (about 9 dp) and gets lost on the light greens. | `Sprites.ball` |
| d | There are no colour ramps. Shade is "multiply by 0.74", so every shadow is a muddier copy and the greens blur together. | `Palette.shade` |
| e | The coast is smooth, but green, fairway and sand edges are still tile staircases. | `Terrain` |
| f | The flat view gives no sense of height: islands look like stickers on the sea. | everywhere |
| g | In the 3×5 font, **N reads as D** ("DEW BEST"), and M, W and H crowd together. It's too small for headings. | `PixelFont` |
| h | The UI is navy on a navy sea. Panels are low-contrast and generic. | all screens |

### Touch targets (1 logical px = 2.29 dp on a 1080-wide Pixel; Material asks for 48 dp or more)

| Control | Hit area | Centre, % down the screen | Verdict |
|---|---|---|---|
| Pause | 48 × 43 dp | **3%** (top-left corner) | worst reach on the screen |
| Look at green | 48 × 43 dp | **3%** (top-right corner) | worst reach |
| Title: Play course | 293 × 59 dp | 52% | a stretch |
| Title: Practise | 293 × 46 dp | 62% | below 48 dp |
| Title: Sound / Buzz | 155 × **41** dp | 92% | too short |
| Hole select: back | 59 × **37** dp | **4%** | too short, too high |
| Hole select: top row of cards | 185 × 251 dp | 9–37% | hard to reach |
| Pause menu buttons | 256 × 46 dp | 40% | mid-screen, below 48 dp |
| Card: Next hole | 311 × 50 dp | 58% | OK, could be lower |
| Practice card: Retry / Next / Holes | 94 × 50 dp | 54% | OK |

### Flows: taps and time

| Flow | Today |
|---|---|
| Launch to first shot | 1 tap (Play), about 1 s of fade. On long holes, a 1.6 s flyover (tap to skip). |
| Holed to next shot | 1.1 s sink, 0.5 s card wait, 1 tap at 58%, 0.56 s fade: **about 2.2 s and 1 tap**, plus 1.6 s on long holes. |
| Practise a hole | 2 taps, the second often in the top third. |
| Restart from pause | 2 taps, the first in the top-left corner. |

---

## 2. Three decisions (recommended defaults first)

| # | Decision | Options | What it costs |
|---|---|---|---|
| **D1** | Art direction | **B: a 3/4 view.** Islands get a 3 px bank on south shores, walls get a top face and a front face, and logs read as cylinders. *(Recommended.)* Or **A:** keep the flat view and only clean it up. | The front faces are drawn *inside* the wall tiles, so playable ground is untouched. Banks are drawn on water pixels, so the coast check is extended to cover them. No map changes. |
| **D2** | UI theme | **B: scorecard paper and driftwood.** Cards and sheets are cream paper with ink text; main buttons are driftwood planks; the HUD strip stays dark for contrast. *(Recommended.)* Or **A:** keep the navy panels, refined. | Every screen is re-laid out anyway for the thumb-first work below, so B costs little extra. |
| **D3** | Pixel scale and ball | **Keep 6× (180 px wide)** with a **6 px outlined ball**, and physics radius 2 → 2.5 px. *(Recommended.)* Or **7× (154 px wide)**, chunkier. | 6×: the pars are re-proven automatically by `HoleCompletableTest`, and nothing is redrawn. 7×: the holes are 160 px wide, so it needs horizontal scrolling or all six maps redrawn at 18 tiles or fewer. |

The thumb-first layout isn't a taste call; it's in either way.

---

## 3. Style rules (all procedural, so they work on any ASCII map)

| # | Rule |
|---|---|
| R1 | Every material has a 4–7 step **hue-shifted ramp** (sea, grass, sand, stone, wood, paper). Shadows step down the ramp, going cooler; highlights step up, going warmer. Nothing is ever darkened by multiplying. |
| R2 | One light, from the **top left**. Objects cast their mask offset by (2, 2), one ramp step down, with checker-dithered edges. |
| R3 | **Clusters, never noise.** Tufts, pebbles, shells and daisies sit on a jittered grid, at most one per 5×5 cell, each a 2–4 px shape. Large fills stay flat apart from mowing stripes. |
| R4 | **Objects get outlines** (the darkest ramp step): ball, flag, palms, logs, and the base of walls. Ground never does. |
| R5 | **Every ground edge is smoothed** like the coast (blurred masks), with a 1 px transition colour (collar, lip, beach). Physics stays on tiles, and a test keeps every drawn edge within 3 px of them. |
| R6 | **3/4 depth (if D1 = B).** South-facing shores get a 3 px bank, and walls are a 5 px top plus a 3 px face, all inside their own tiles or over water. |
| R7 | **Animation is stepped.** World art animates at 4–8 fps (water, flags, palms). The UI may glide, but always lands on whole pixels. |
| R8 | **Sprites are string grids in code** (one character per palette colour), baked into one atlas at start-up. A `SpriteSheetTest` renders the whole atlas to PNG for review. |

---

## 4. Pixel-art work

| # | Item |
|---|---|
| P1 | **Palette:** `Palette.kt` becomes ramps plus `darker(c)` / `lighter(c)` helpers (R1). |
| P2 | **Texture (R3):** tufts in the rough, sparse fairway daisies, wind ripples and shells on the sand, and stone as clean blocks with mortar lines. |
| P3 | **Smoothed inner edges (R5):** green collars and bunker lips. The coastline test is extended to cover every tile boundary. |
| P4 | **Shadows (R2)** for walls, palms, the flag, the ball and logs. |
| P5 | **Depth (R6, if D1 = B):** island banks with a shadow line on the water, wall faces, and logs with end caps. |
| P6 | **Ball:** 6 px, outlined, with a rolling seam and an offset shadow. Redraw the trail and the cup drop to match. |
| P7 | **Water:** one continuous foam line, a dashed ripple ring, depth bands blended with ordered dither, and sparkles on the sunlit side. |
| P8 | **Green readability:** slopes shown as faint grain streaks running downhill, instead of the block gradients. Checker mowing on greens. |
| P9 | **Sprite atlas (R8)** for the ball, flag, palms, gulls and icons. |
| P10 | **Time of day** (was V8), built on P1's ramps: dawn for the Cove now, the other palettes in M3 and M4. |

## 5. Type

| # | Item |
|---|---|
| T1 | A **5×7 proportional font** (narrow I, 1, ! and .) for headings, buttons and the HUD. The longest string, "PULL BACK ANYWHERE TO AIM", is 149 of 180 px, so everything fits. |
| T2 | Keep 3×5 for dense grids (the scorecard), with **N, M and W redrawn** so they can't be misread. |
| T3 | A `TextFitTest` that checks every string against its box on a 360 dp-wide screen. |

## 6. Thumb-first UI

Layout rules:
- The main action sits lowest on the screen and biggest: full width, at least **56 dp** tall.
- Secondary actions are at least 48 dp.
- Nothing you tap sits in the top 25% of the screen.
- Nothing sits inside the bottom gesture zone.
- Everything works with either thumb.

| # | Item |
|---|---|
| U1 | **Bottom sheets** for every menu (pause, cards, scorecard, settings). They slide up, and tapping outside dismisses or resumes where that's safe. |
| U2 | **Title:** **PLAY** (or **CONTINUE** if a round is in progress) at about 75% of the way down, with **HOLES** and **SETTINGS** in a row below. |
| U3 | **Hole select as a carousel.** One hole's map fills the top, you swipe left or right anywhere to change hole, and **PLAY** sits at the bottom. Locked holes show as a silhouette with a padlock. |
| U4 | **In-play HUD:** the top strip is information only (hole, par, shots, score). **Pause becomes a 55 dp chip** in the bottom-left, just above the gesture zone. It fires only on a clean tap; a drag that starts on it becomes a shot, so it never steals the aim. A Back gesture also pauses. |
| U5 | **Hold still to peek** replaces the look button. Press without moving for 0.35 s and the camera glides to the cup; let go and it comes back, with no stroke taken. Moving past the cancel ring aims as usual. |
| U6 | **Hole card as a compact sheet.** **Tap anywhere to continue** after 0.4 s. NEXT HOLE is big at the bottom; Retry and Holes are secondary, in practice only. |
| U7 | **Faster between holes:** the sink takes 0.7 s instead of 1.1 s, the card rises straight away, and the flyover plays only the first time you see a hole in a session. That gets holed-to-next-shot from about 2.2 s to about 1.2 s. |
| U8 | **Settings sheet** (was U9): sound, vibration, left-handed (mirrors the pause chip), and reduce motion (no shake, no flyover). |
| U9 | **Resume a round** (was U10): the round is saved after every hole, and the title shows CONTINUE. |
| U10 | **Restart during a round:** by default it's hidden in a round and only offered in practice, so the course best can't be gamed. Say if you'd rather keep it. |
| U11 | *Maybe:* a power ring around the ball, and a faint "cup detent" (a tick when the aim points exactly at the cup). Off by default; decide after trying S1. |

## 7. Rendering cost (secondary)

| # | Item |
|---|---|
| E1 | A glyph atlas: one bitmap per character instead of a rect per pixel. The UI layer is redrawn only when it changes. |
| E2 | A frame-time readout (long-press the title, as in Arrow Unwind). |
| E3 | A budget of under 4 ms per frame on a Pixel, checked on Palm Grove, the heaviest hole. |

---

## 8. Milestones

| # | Milestone | You get |
|---|---|---|
| **S1** | **Vertical slice:** hole 1 and the hole card in the new style (P1, P2, P4, P5 or not per D1, P6, T1, U6) | An APK to judge the look on your phone before anything else is converted. |
| S2 | All terrain and sprites (P3, P7–P10) on all six holes, pars re-proven for the bigger ball | The whole course in the new style. |
| S3 | Every screen thumb-first (U1–U10), `TextFitTest`, UI playthrough updated | New menus, faster flow. |
| S4 | Polish and E1–E3 | Smooth and measured. |

Then on to **M3, Saltmarsh and the tide**.

# Golf Alone: visuals, UI and the first course

> **Status:** pass 1 (the ★ rows) is done. Rows without a star come later. Strike or reorder
> anything by ID. Where the build differs from this plan:
> - Tide Pools ended up **par 5**, not 4. At 62 rows it's the one hole that really scrolls, and the
>   perfect planner bot needs 4. Course par is 21.
> - The flyover (V7) and the look button (U6) only appear on holes whose green is out of sight
>   from the tee, so only on Tide Pools for now. On short holes the camera never moves.
> - The coastline is drawn smoothed (rounded corners), but physics stays on the tile grid, so
>   the two can differ by about 3 px at corners.

Hole 1 plays well, so this pass turns one hole into a **course**. The look stays the same
(crisp pixels on a small island, a quiet mood), with more life on the ground, real menus and
six hand-made holes.

---

## V. Visual improvements

| # | | Item | Detail |
|---|---|---|---|
| V1 | ★ | **Cloud shadows** | Two or three soft, dithered shadows drift slowly across the course, the way they do on a real links course. |
| V2 | ★ | **Ground detail** | Flowers in the rough, and shells and pebbles on the beaches, placed by hash so each hole always looks the same. Daisy specks on the fairway edges. |
| V3 | ★ | **A ball that rolls** | The ball's shading turns with the distance it has travelled, so you can see it spin and slow down. |
| V4 | ★ | **Pixel dissolve transitions** | A 4×4 ordered-dither wipe between screens and holes, instead of a hard cut. |
| V5 | ★ | **Palms and driftwood** | Palms cast swaying shadows, and their canopies sit over the ball, which shows through as a faint ghost. Driftwood logs have wood grain and rounded ends. |
| V6 | ★ | **Impact feel** | A one-pixel screen nudge on hard wall hits and splashes. A dust puff when the ball stops in rough. |
| V7 | ★ | **Hole flyover** | Each hole opens with the camera on the flag, then glides back to the tee while the banner shows. Tap to skip. |
| V8 | | **Time-of-day palettes** | Dawn for Driftwood Cove, noon for Saltmarsh, dusk into night for Lighthouse Point, by swapping palettes. |
| V9 | | **Weather** | Light rain streaks and puddle ripples on some holes, and wind streaks once wind (E5) exists. |
| V10 | | **Wildlife** | Crabs (E6) scuttling, a heron in the shallows, fish rings in ponds. |

## U. UI elements

| # | | Item | Detail |
|---|---|---|---|
| U1 | ★ | **Pixel buttons that press** | Buttons drop a pixel while held and fire on release, with a tick and a click. This replaces the plain hit rects. |
| U2 | ★ | **New title screen** | The logo over the island, then **PLAY COURSE**, **HOLES**, and the sound and vibration toggles. Shows your course best. |
| U3 | ★ | **Hole select** | Six cards, each with a pixel thumbnail of its hole, its par and your best flag. A hole unlocks once you finish the one before it. |
| U4 | ★ | **Pause menu** | A pause button in the HUD. The menu has Resume, Restart, sound and vibration, and Quit to menu. The game freezes underneath. |
| U5 | ★ | **Better HUD** | Pause button, `HOLE 2/6`, par, shots, and your running score in a course round (`E`, `+1`, `-2`). |
| U6 | ★ | **Look at the green** | Hold the flag icon in the HUD and the camera glides to the cup, then returns when you let go. Long holes need this. |
| U7 | ★ | **Hole card** | The result word and flag, plus a strip of the round so far. The buttons are **NEXT HOLE**, or **RETRY** / **NEXT** in practice. |
| U8 | ★ | **Course scorecard** | After hole 6: a full card with par, your score and a flag for each hole, your total against par, and NEW BEST. |
| U9 | | **Settings page** | Volumes, reduce motion and left-handed HUD. For now, sound and vibration toggles cover it. |
| U10 | | **Resume a round** | Saves a course round in progress so it survives the app closing. |

## M. Maps: Driftwood Cove (6 holes, rolling only)

Every hole is checked by `HoleCompletableTest`: the planner bot must finish it at par or
better with the real physics. The planner is a perfect shot-maker, so par is usually set at
the planner's best score plus one.

| Hole | Name | Par | The idea | New element |
|---|---|---|---|---|
| 1 | **First Light** | 3 | Round the rocks and the tide pool, up to a walled green. | (the current hole) |
| 2 | **The Spit** | 3 | A long, thin finger of land between two seas. Thread the narrow neck, or lay up short of it. | Precision |
| 3 | **Driftwood** | 3 | A bowl ringed with driftwood. The direct line is blocked, so bank shots get you there. | Bouncy driftwood logs (`w`) |
| 4 | **Tide Pools** | 4 | A long hole that scrolls, past a string of pools, onto an island green reached by a narrow causeway. | Scrolling hole, look-at-green |
| 5 | **Palm Grove** | 4 | A dogleg through a grove of palms. Thread the trunks, or go round them. | Palm trunks (`P`) as round posts |
| 6 | **The Saddle** | 3 | A green on a ridge, with slopes falling away on both sides. Speed matters more than line. | Big slopes |

Saltmarsh (the tide, E2) and Lighthouse Point (loft, E1) follow as in [PLAN.md](PLAN.md),
milestones M3 and M4.

## Build order for pass 1

1. **Engine:** driftwood restitution, palm posts, more tiles. Physics and planner tests for each.
2. **Maps:** the five new holes, each passing the planner at par. A preview test renders every hole to PNG.
3. **Course flow:** course round, practice from hole select, unlocks, best scores per hole and per course.
4. **UI:** buttons (U1), title (U2), hole select (U3), pause (U4), HUD (U5), look (U6), cards (U7, U8).
5. **Visuals:** V1 to V7.
6. **UI playthrough test** of a full course round, then CI and the APK.

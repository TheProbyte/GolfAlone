# Golf Alone: the next nine courses

> **Status:** courses 2–9 are **built**: Saltmarsh Flats, Lighthouse Point, Glass Lagoon, Wreck
> Bay, Ember Isle, Frostbound Fjord, Temple Cay and Windward Bluffs, each with course select,
> per-shot hole state, and its own mood, life and sound. Only the finale (course 10) is still a
> plan. Pars below are what was built (the bot's best without tricks, plus one).
> Life as built: herons and frogs (Saltmarsh); fireflies, crickets and a foghorn (Lighthouse);
> fish and lapping water (Lagoon); rain, thunder and crabs (Wreck); rising embers and a rumble
> (Ember); falling snow, seals and wind (Frostbound); parrots, butterflies and birdsong (Temple);
> sheep and gusts (Windward). The Book's hole table follows the course chosen on the title.

## The shape of it

- **Ten courses**, each with its own theme, time of day, wildlife and sound.
- **Each course adds one or two new things**, taught safely on its first hole, then mixed
  with what came before. By course 10 you've seen everything.
- **Every course mixes hole lengths:**
  - **S**: fits on one screen (par 2–3).
  - **M**: about one and a half screens (par 3–4).
  - **L**: two screens or more, with a flyover the first time (par 4–5).
- Courses 2–9 have 6 holes each. The finale has 9 holes, so 57 new holes in all.
- **Unlocking:** finish a full round of a course (any score) to open the next one. Practice
  unlocks hole by hole, as now.

## Rules every new mechanic follows

These are the rules that made the water fair, applied to everything new:

1. **Nothing is random.** Anything that changes does so once per stroke, or replays exactly
   the same way every shot. The same shot always gives the same result.
2. **You can read it before you shoot.** Anything that will change shows its next state,
   always on screen: a tide gauge, a wet sheen on sand that floods after this shot, crab
   footprints on their next tile, cracks in the ice. (Holding still stays the peek.)
3. **New hazards work like the water:**
   - you're only in once the whole ball is in
   - a slow ball teeters on the edge
   - the drop is near where you went in
4. **The bot proves par on every hole**, including the holes where things change per shot
   (the planner tracks that state and counts splash penalties). On tidal holes it also proves
   par with the tide always in, and a fuzz test checks no barrel can ever lock a hole.
5. **The first hole of each course teaches its new thing** with nothing else in the way.

---

## Course 2: Saltmarsh Flats (golden hour)

**New: the tide.** Every stroke moves the tide one step, low to high and back, over 6 strokes.
A gauge in the corner shows it, and holding the gauge shows the next step as a ghost.
- **Tidal sand:** dry at low tide, water at high tide.
- **Mud:** very heavy. The ball stops almost dead, with no bounce.
- **Boardwalks:** narrow plank bridges across the creeks. The sides are open water.

**Life and sound:** herons standing in the shallows, swaying reeds and fiddler crabs. Frogs,
distant gulls, and the slosh of water as the tide turns.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Low Water | S | 3 | A dry loop round the creek, or straight up the flats while the tide's out. |
| 2 | The Boardwalk | M | 4 | A walk over the creek that turns at a platform: two clean putts along the planks. |
| 3 | Mudflat | S | 3 | The cup in a ring of mud, open on the far side: only a soft putt holds. |
| 4 | Creek Bends | L | 4 | A snaking creek; while the tide's out the flats cut its bends. |
| 5 | Heron's Rest | M | 4 | Two islands: the causeway at low tide, or the long boardwalk round. |
| 6 | Spring Tide | L | 5 | A bay of flats: cross it while the tide's out, or walk the shore. |

Built as planned, par 23. Mud plugs a ball that rolls into it. A ball the tide covers is lifted
to the nearest dry spot for free.

## Course 3: Lighthouse Point (night)

**New: bumper buoys and hermit crabs.**
- **Bumper buoys:** round red-and-white buoys that kick the ball away faster than it came, like
  pinball bumpers, with a bell.
- **Hermit crabs:** they move one tile per stroke along a track you can see. They block like a
  wall, and their next step shows as a faint footprint.
- **Night:** a dark palette with pools of lantern light. A lamp on the flag means the cup is
  always easy to see. The lighthouse beam sweeps for atmosphere only and never hides anything.

**Life and sound:** moths round the lanterns and bioluminescent surf. Crickets, a far-off
foghorn, and the buoys' bells.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Harbour Lights | S | 3 | A low wall shields the cup: bank off the buoy round its end. |
| 2 | Crab Walk | M | 3 | A gap to the west of the rocks, with a crab patrolling it. |
| 3 | The Spiral | M | 5 | Rings of wall round the lighthouse, their gaps on alternate sides. |
| 4 | Buoy Field | S | 3 | A field of bumper buoys: chaos, or one clean line through. |
| 5 | Keeper's Garden | M | 4 | A walled garden: in by the south gate past one crab, or the east gate past another. |
| 6 | Long Night | L | 4 | The whole headland: buoys, a crab and rocks, up to the lighthouse. |

Built, par 22. Buoys kick the ball off faster than it came, up to six times a shot. The night is
a moonlit palette, only as dark as the contrast rule allows; lanterns and the beam add the rest.

## Course 4: Glass Lagoon (tropical noon)

**New: shallows and currents.**
- **Shallows:** clear turquoise water over sand that you **can** roll through. It drags hard
  but costs no stroke. Deep water still splashes, and the edge between the two is drawn clearly.
- **Currents:** channels where foam drifts along. A ball in one is carried along slowly, like a
  conveyor.
- **Coral heads:** knobbly rocks in the shallows to bank off.

**Life and sound:** turtles, the shadows of rays, and shoals of fish. Gentle lapping water.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Wade In | S | 3 | A shortcut through the shallows that costs speed, not a stroke. |
| 2 | Lazy River | M | 3 | A current that carries the ball round to the green, if you start it right. |
| 3 | Coral Gardens | M | 3 | Coral heads to thread in the shallows, round an island green. |
| 4 | The Rip | S | 3 | A current running out to sea across the fairway: cross it fast. |
| 5 | Turtle Beach | L | 4 | A long sandbar, with a current either side running opposite ways. |
| 6 | The Blue Hole | M | 3 | A ring of reef round a deep hole; a current carries you to the island green. |

Built, par 19. Currents drift faster than the teeter speed, so a rip really does carry you out.

## Course 5: Wreck Bay (storm)

**New: breakable planks and barrels.**
- **Cracked planks:** a hard hit smashes one open for the rest of the hole. A soft hit just
  bounces off.
- **Barrels:** heavy and round. They roll when the ball hits them and stay where they stop, so
  they can block a line or clear one.
- **The wreck's hull** forms the walls and corridors.

**Mood:** an overcast, grey-green storm, with rain streaks (off under reduce motion).

**Life and sound:** crabs sheltering under timbers and gulls riding the gusts. Rain, creaking
wood and distant thunder.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Washed Up | S | 3 | A fence of cracked planks: smash through, or go round the end. |
| 2 | Barrel Run | M | 3 | Two barrels in a corridor of hull timbers. |
| 3 | The Hold | M | 4 | Inside the wreck: zigzag between bulkheads; one door is cracked planks. |
| 4 | Gangplank | S | 3 | A single plank out to a green on the rocks. |
| 5 | Mast Fall | L | 4 | The fallen mast across the beach, a long rail to bank along, with barrels about. |
| 6 | Storm Surge | L | 4 | A wreck on the beach: round it either side, or smash through bow and bulkhead. |

Built, par 21. Barrels never come to rest in the cup, and sink in the sea.

## Course 6: Ember Isle (volcanic dusk)

**New: steam vents, obsidian and lava.**
- **Steam vents:** roll over one and it pops the ball into a short hop, the way ramps throw it,
  but straight up from a flat tile.
- **Obsidian:** black glass with almost no grip. The lightest touch glides a long way.
- **Lava:** a glowing hazard that works exactly like water.
- **Black-sand beaches.**

**Life and sound:** drifting embers and a smoking peak. A low rumble, plus a hiss from each vent.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Black Sand | S | 3 | A lava seam across the island; a vent either side of the straight line. |
| 2 | Glass Floor | M | 3 | Obsidian before the green, a low wall across it, lava behind: a feather touch. |
| 3 | Vent Hop | S | 3 | A wide lava channel: vent to an islet of vents, and on across. |
| 4 | Caldera | M | 3 | A crater rim round a lava lake; rim vents throw you to the island green. |
| 5 | The Flow | L | 4 | Two lava rivers, crossed by vents and an obsidian bridge. |
| 6 | Summit | L | 4 | Rings of rock round the peak: ramps over them, or walk round to their east gaps. |

Built, par 20. A vent throws the ball forward at least a few tiles, so even a soft putt clears a
seam; vents in a row chain. Lava never meets the sea (basalt seals it).

## Course 7: Frostbound Fjord (cold morning)

**New: ice, thin ice and snowbanks.**
- **Ice:** slick. The ball barely slows, so banks and angles matter.
- **Thin ice:** it cracks when the ball rolls over it, and turns to water on your next stroke.
  The cracks show before it goes.
- **Snowbanks:** soft. They swallow speed and stop the ball dead.

**Life and sound:** seals sliding off rocks and falling snow. Wind, and the creak and ping of ice.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | First Frost | S | 3 | One ice patch in front of the green. |
| 2 | Snow Fort | M | 3 | Snowbank walls round the green, open to the south-east. |
| 3 | Thin Ice | S | 3 | A thin-ice bridge straight over, or the long way round the west shore. |
| 4 | The Glacier | L | 4 | A winding tongue of ice with crevasses on the straight lines. |
| 5 | Seal Rocks | M | 5 | Ice floes among rocks, joined by narrow necks. |
| 6 | Northern Lights | L | 4 | Ice, thin ice and snow, all the way up. |

Built, par 22. Ice rolls about 1.6× as far as fairway. Every thin-ice hole has a second way
round, and a fuzz test checks thin ice can never strand the ball.

## Course 8: Temple Cay (jungle, golden light)

**New: switches and gates, and tunnels.**
- **Switch plates:** roll over one and a stone gate somewhere on the hole opens and stays open.
- **Tunnels:** the ball drops into a stone mouth and comes out of another. It keeps its speed
  and leaves in the exit's direction.
- **Vines:** overgrowth that acts like heavy rough.

**Life and sound:** parrots, monkeys in the canopy and butterflies. Birdsong and insects.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Gatekeeper | S | 3 | A wall shields the green; the plate off to one side opens its gate. |
| 2 | Two Mouths | M | 3 | A wall right across: a tunnel through it, or round its end through vines. |
| 3 | The Courtyard | M | 4 | A walled court and four plates: only the one with the sun opens the gate. |
| 4 | Serpent Steps | L | 5 | Terrace walls up the hill, crossed by ramps and a tunnel. |
| 5 | The Idol | S | 2 | A tiny shrine, the cup at the feet of the idol. |
| 6 | Lost City | L | 4 | Two walls, each with a gate and a tunnel. |

Built, par 21. Gates are sandstone carved with their group's glyph (sun or moon); their plate
carries the same glyph; decoys are plain stone.

## Course 9: Windward Bluffs (breezy noon, high cliffs)

**New: ledges, windmills and ridge wind.**
- **Ledges:** one-way drops. Roll off a terrace edge and you hop down to the level below.
  The only way back up is a ramp.
- **Windmills:** their blades turn while the ball rolls, starting from the same place every
  stroke, so a shot always plays the same.
- **Ridge wind:** on exposed, marked tiles the ball is pushed gently. Streaks show which way.
  The wind is the same all through a hole.

**Life and sound:** sheep, kites and gulls hanging in the wind. Gusts and a windmill's creak.

| # | Hole | Size | Par | Idea |
|---|---|---|---|---|
| 1 | Step Down | S | 3 | One ledge down to the green. |
| 2 | The Mill | M | 3 | Through the gap in the wall while the windmill turns. |
| 3 | Ridge Line | M | 3 | A narrow ridge with the wind across it, the sea either side. |
| 4 | Terraces | L | 4 | Four ledges stepping down to the green. |
| 5 | Sheepfold | S | 4 | A drystone fold round the green, its gate to the side. |
| 6 | Gale | L | 4 | A ledge, a windswept ridge, then a windmill in front of the green. |

Built, par 21. Ledges are drawn the same whichever way they drop, with a chevron pointing down,
so a one-way step always reads.

## Courses 10-19: nine holes each, generated and proven

Built as ten nine-hole courses (par 34 each) instead of one finale. The layouts come from a
seeded generator ([tools/holegen](../tools/holegen/README.md)): fixed, so every player gets the
same holes, but varied far beyond what hand-painting would allow. A hole is kept only if the bot
proves it: par is its best plus one, no par 4+ can be aced, the drawn coast matches the physics,
and a gap's helper (kicker, vent, portal) is really needed. Hole 1 of each course brings in
its mechanic with no other challenge beside it.

New mechanics: kickers (`Y y X K`), boost pads (`B`), portal pairs (`1 3`), quicksand (`Q`),
pulse walls that swap with every shot (`M R`, the lowered set drawn as a pulsing outline),
gravity wells (`@`), and the sky style, where the sea is open air (a fall costs a stroke).

| # | Course | Mood | Signature |
|---|---|---|---|
| 10 | Mirage Oasis | desert | quicksand |
| 11 | Mushroom Hollow | twilight | vent gaps, mushroom bumpers |
| 12 | Carnival Pier | evening | pinball bumpers, boosts, belts |
| 13 | Skyreach | open sky | kicker gaps over the void |
| 14 | Clockwork Ruins | bronze | pulse walls, gates |
| 15 | Mangrove Maze | swamp | sinking lily pads |
| 16 | Crystal Caverns | cave | gravity wells |
| 17 | Autumn Woods | autumn | leaf piles, streams |
| 18 | Zen Garden | sakura | raked gravel, bridges |
| 19 | Starfall Atoll | starry night | a little of everything |

Each of these moods brings its own grass and water ramps before its light is graded on top
(`Palette.own`), still held to the surface-distance check in `PaletteMoodTest`.

---

## Built once, before course 2

| # | Foundation | Why |
|---|---|---|
| F0.1 | **Course select:** the title shows the current course's card in its own colours. Swipe or use the arrows to change course. A locked course says what opens it. | Ten courses need a way in. |
| F0.2 | **Per-stroke hole state:** tide level, crab positions, smashed planks, barrel positions, cracked ice and open gates, advanced once per stroke. Physics reads it, saves keep it, and the planner searches through it. | It covers rule 1 and rule 4 for every course at once. |
| F0.3 | **Moving bodies:** kinematic ones (crabs, windmill blades) and pushable ones (barrels) in the physics. | Courses 3, 5 and 9. |
| F0.4 | **The preview (built differently):** always-on signs instead of a hold-to-preview: the tide gauge, a wet sheen on sand about to flood, crab footprints. | Rule 2. |
| F0.5 | **New moods:** golden, night, noon, storm, dusk and cold, each passing the contrast test. | One per course. |
| F0.6 | **Per-course life and sound:** ambient creatures and a sound bed for each course. | |
| F0.7 | **The Book per course:** stats and postcards with a tab for each course. | |

## Order of work

1. Foundations F0.1, F0.2, F0.4 and F0.5, then **Saltmarsh Flats**. Check it on your phone.
2. **Lighthouse Point** (adds F0.3 for crabs).
3. One course at a time after that, with a phone check after each. Within a course, the
   mechanic and its teaching hole come first, then the other five holes.
4. **Starfall Atoll** last, with the connected island.

Pars are set from your real scores once a course has been played.

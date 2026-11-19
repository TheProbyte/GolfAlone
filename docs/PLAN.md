# Golf Alone: game plan

> **Status:** M0 and M1 done: the scaffold and one playable hole (Driftwood Cove 1). Next up is M2.
> The IDs (A1, C3, …) are there so you can say "drop C4, move E2 into v1".

A top-down, 2D pixel-art golf game for Android (built and tested for a Pixel), installed as
a sideloaded APK. You play alone on a small tidal island at the edge of the sea. Pull back
anywhere on the screen to aim, let go to shoot. There are no lives, no timers and no
currencies, only strokes, par and the tide.

---

## 1. What carries over from ArrowGame

ArrowGame is plain Kotlin on a custom `View` and `Canvas`, with no engine or libraries and
everything drawn in code. That approach suits a small pixel-art game well, so most of the
infrastructure comes across.

| ArrowGame file | In Golf Alone | Notes |
|---|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, Gradle wrapper | **Reuse as-is** | AGP 8.13 / Kotlin 2.2, compileSdk 35, minSdk 26, R8 on release. |
| `.github/workflows/build-apk.yml` | **Reuse** | Tests, then `assembleRelease`, then publishes `GolfAlone.apk` to the `latest` release on every push. |
| Sideload keystore pattern | **Reuse the pattern, with a new key** | New `golfalone-sideload.keystore` and new `applicationId` `com.theprobyte.golfalone`, so both games install side by side. |
| `Haptics.kt` | **Reuse and extend** | Same primitive-composition approach. Adds new events: tension ticks, impact, sand, cup drop. |
| `audio/AudioEngine.kt` | **Reuse as-is** | Low-latency AudioTrack thread that idles at rest. |
| `audio/Synth.kt` | **Reuse and extend** | `tok`, `click`, `air`, `mallet` and `glass` map straight onto golf sounds. The filtered-noise source is enough to add splash, sand scuff and surf voices. |
| `audio/Music.kt`, `Sfx.kt` | **Adapt** | Same event-to-note idea. A cup drop resolves the hole's chord, and the ambience becomes waves and wind. |
| `Progress.kt` | **Adapt** | SharedPreferences, but storing a best score per hole instead of a solved flag. |
| `MainActivity.kt` | **Adapt** | Same single-Activity screen swapping, edge-to-edge, high refresh rate and back handling. Screens: menu, course select, hole, scorecard, settings. |
| `ui/Widgets.kt`, `IconView.kt` | **Partly** | Sliders and toggles carry over, restyled as pixel art. |
| `UiPlaythroughTest.kt` (Robolectric + screenshots) | **Reuse the harness** | Drives real shots through `MotionEvent`s and saves screenshots with `-Pshots=`. |
| `LevelGeneratorTest.kt` | **Same idea** | Becomes `HoleCompletableTest`: a bot proves every hole can be finished (see D). |
| `render/Camera.kt` | **Rewrite, keeping the springs** | Golf needs a follow-cam with a look-ahead while aiming, not pinch-zoom on a static board. |
| `GameView.kt`, `render/BoardLayer.kt`, `ArrowShape.kt`, `game/*` | **Do not carry over** | The vector white-on-black renderer and tap rules are specific to the arrow puzzle. |

---

## 2. Signature hook: **the tide**

The idea is that every stroke advances the tide by one step.

- Each hole has a tide cycle, for example 6 strokes from low to high and back.
- **Low tide** exposes sandbars, rock paths and shortcuts. **High tide** floods them into
  water hazards and opens channels that a rolling ball can float along.
- A small pixel tide gauge in the corner shows the current level. Holding your finger on
  the gauge previews the next tide state as a ghost overlay.

This turns each hole into a small puzzle as well as a skill test. You can take the safe
route now, or lay up for one stroke so the sandbar is exposed for your next shot. Par is
set so that reading the tide is how you score under it.

**Done when:** at least one hole in every course can only be birdied by timing the tide,
and the gauge preview makes the next state obvious before you shoot.

---

## 3. Core feel (needed in every version)

### A. The shot (drag and pull back)

| # | Item | Detail |
|---|---|---|
| A1 | **Drag from anywhere** | Put your finger anywhere and pull back, like a slingshot. The ball is never under your thumb. The shot goes opposite to the drag. |
| A2 | **Power** | Power scales with drag length and is clamped at about 38% of screen width. The curve is slightly eased so short putts get finer control. |
| A3 | **Cancel** | Drag back within about 14dp of where you started, and the aim fades out and the shot is cancelled. No stroke is spent. |
| A4 | **Aim preview** | A dotted pixel line shows the direction and the first ~40% of the roll. It doesn't show bounces, so the skill stays in your hands. The dots get denser and warmer in colour as power rises. |
| A5 | **Tension feedback** | A haptic tick at every 10% of power, and a soft rising `click` pitch. At full power the dots pulse and a firmer tick plays. |
| A6 | **Release** | The club flash is a 2-frame pixel swoosh. A `tok` plus an impact haptic, both scaled by power. |
| A7 | **Gesture-nav safety** | Pixel phones use gesture navigation, which fires on touches that *start* at the screen edge. Drags that start inside `systemGestureInsets` are ignored, and the course is framed so the ball never needs aiming from the edge. Exclusion rects cover the left and right edges during an active drag. |

**Done when:** you can putt a 1-tile tap-in and a full-length drive with the same gesture,
and you never trigger Back or Home by accident.

### B. Physics (`game/`, pure Kotlin, deterministic)

| # | Item | Detail |
|---|---|---|
| B1 | **Fixed timestep** | Physics runs at 240 Hz in a pure-Kotlin `game/Physics.kt` with no Android types. Rendering interpolates between steps, so a shot is identical at 60 and 120 Hz and can be unit-tested. |
| B2 | **Rolling** | Ball position and velocity, with friction taken from the tile under the ball. |
| B3 | **Walls** | Circle-versus-tile collisions with a restitution value per material: stone is bouncy, wood is dull, hedges are soft. |
| B4 | **Slopes** | Slope tiles carry a direction and strength and add acceleration. They're drawn as subtle pixel shading, not arrows. |
| B5 | **The cup** | Captured below a speed threshold. Faster balls lip out, deflected around the rim, which feels great when it happens. |
| B6 | **Water** | Costs +1 stroke and returns the ball to its last resting spot, with a splash and a ripple ring. |
| B7 | **No randomness in the sim** | Wind, crabs and so on are driven by the stroke counter and a hole seed, so replays and the completability bot are exact. |

### C. Pixel-art rendering

| # | Item | Detail |
|---|---|---|
| C1 | **Low-res buffer** | Everything draws into an offscreen bitmap about **180 logical pixels wide**, with the height taken from the screen's aspect ratio. It's scaled up by an **integer** factor with filtering off: 6× on a 1080-wide Pixel, 8× on 1440. The scale is computed from the real screen width, never hardcoded. |
| C2 | **Sprites in code** | Sprites are defined as tiny string grids with a palette character per colour, like ArrowGame draws everything in code. They're baked into bitmaps once at load, so there's no asset pipeline. The tile size is 8 px. |
| C3 | **Smooth camera** | The world is drawn at whole logical pixels, and the buffer is offset by a sub-pixel amount at screen scale, so scrolling is smooth with no shimmer. |
| C4 | **Restricted palette** | About 24 colours. Each course has a time of day (dawn, noon, dusk, night) made by swapping palettes, not redrawing. |
| C5 | **Ball height** | Lofted balls (E1) are drawn with a separate shadow that shrinks and grows. |
| C6 | **Ambient life** | Animated water tiles at 4 frames, swaying grass tufts, foam lines at the shore, gulls passing overhead. All of it is cheap and tile-based. |

**Done when:** the frame stays under 4 ms on a Pixel, and every pixel is crisp and square.

### D. Holes and courses

| # | Item | Detail |
|---|---|---|
| D1 | **Hand-authored holes** | Holes are ASCII tile maps stored as Kotlin strings (`#` wall, `.` fairway, `,` rough, `s` sand, `~` water, `t` tidal sand, `O` cup, `T` tee, …). Each has a par, a tide cycle and a time of day. Hand-authoring is recommended over generation, because good golf holes need a designer's eye. |
| D2 | **Launch content: 3 courses × 6 holes = 18** | *Driftwood Cove* (tutorial, roll only), *Saltmarsh* (sand, slopes, the tide), *Lighthouse Point* (loft shots, wind, the night palette). |
| D3 | **Connected course** | Holes flow into each other on one continuous island. When the ball drops, the cup pushes it out, and the camera glides along a path to the next tee. There are no loading screens. |
| D4 | **`HoleCompletableTest`** | For every hole, a bot searches shot angles and powers (beam search, a few hundred simulated shots per stroke) using the real physics. It asserts the hole can be finished in at most par + 2 strokes and that par itself is reachable. This runs in CI like ArrowGame's generator test. |
| D5 | **Scoring** | Strokes against par, with a pixel scorecard after each course. Each hole earns a flag: a white flag for finishing, a gold flag at or under par, and a red pennant for a hole-in-one. Best scores are saved. |

---

## 4. Mechanics, tiered

### E. v1 (ships with the 18 holes)

| # | Mechanic | How it plays |
|---|---|---|
| E1 | **Roll versus loft** | Inside the inner ring of the drag, the shot is a roll. Pull past the ring, which clicks with a haptic, and it becomes a **loft**: the ball leaves the ground, its shadow separates, it clears walls and water, then lands and rolls on. Loft trades distance for control. Unlocked in course 3. |
| E2 | **The tide** | See section 2. |
| E3 | **Sand** | Heavy friction. A ball in sand can't loft, only roll. |
| E4 | **Driftwood bumpers** | Logs that bounce harder than stone and give a satisfying `tok`. |
| E5 | **Wind** | Shown by drifting pixel streaks and swaying flags. It only pushes lofted balls, and it changes each stroke in a fixed pattern you can read. |
| E6 | **Hermit crabs** | They scuttle one tile per stroke along a fixed path. They block the ball, and nudge it sideways if they reach it. They give the course some life. |

### F. Later (good ideas, not in v1)

| # | Mechanic | How it plays |
|---|---|---|
| F1 | **Lighthouse beam** | At night, the sweeping beam lights up a hidden path. Only lit tiles are visible. |
| F2 | **Tide pools** | A ball resting in one is carried by a slow current to another pool. |
| F3 | **Gates and shells** | Hitting a shell switch opens a sea gate somewhere else on the hole. |
| F4 | **Sea-glass collectibles** | One per hole, off the main line. They're optional, and collecting them all unlocks a palette. |
| F5 | **Message in a bottle** | A replay of your best round, shown as a ghost ball. |
| F6 | **Endless island** | Seeded, generated holes checked by the D4 bot. Added after the hand-made course proves the tile set. |
| F7 | **Daily hole** | The same seed for everyone, on the device only. |

---

## 5. Polish and feel (folded into each milestone)

- **Ball:** a squash frame on wall hits, a dust puff on landing, sand spray, grass clippings
  at high speed.
- **Cup:** the flag wiggles as the ball nears it. The ball circles the rim, drops with
  a `mallet` note that resolves the hole's chord, and a 3-tick haptic plays. A hole-in-one
  adds a slow-motion final roll and a `glass` chime.
- **Camera:** it leads in the aim direction while you drag, follows the ball with a spring,
  and snaps a little closer on near-misses.
- **Sound:** surf and wind ambience, with no music by default (it's a lonely island). There's
  an optional soft pad, as in ArrowGame. Sounds are panned left and right by where they
  happen on screen.
- **Settings:** effects and ambience volume, vibration, reduce motion, and left-handed
  gauge placement.

---

## 6. Milestones

| # | Milestone | You get |
|---|---|---|
| M0 | **Scaffold** | Build, CI and keystore copied over. The APK installs and shows a pixel title screen. `GolfAlone.apk` is on the `latest` release. |
| M1 | **One playable hole** | Pixel renderer (C1–C3), drag shot (A1–A7), rolling physics with walls and the cup (B1–B5), haptics. **Installable and fun on your Pixel.** |
| M2 | **Driftwood Cove** | 6 holes, D3 transitions, scorecard, save progress, sound, menu, `HoleCompletableTest`. |
| M3 | **Saltmarsh and the tide** | E2, E3, E4, slopes, the tide gauge and its preview. |
| M4 | **Lighthouse Point** | E1 loft, E5 wind, E6 crabs, the night palette. All 18 holes. |
| M5 | **Polish pass** | Section 5, a screenshot playthrough test, and performance checks on the device. |

**Verification:** the Android SDK isn't installed in this cloud container, but Google's
download server is reachable, so the SDK can be installed here to build and run unit and
Robolectric tests locally. CI then builds the release APK on every push. Feel and haptics
can only be judged on your phone.

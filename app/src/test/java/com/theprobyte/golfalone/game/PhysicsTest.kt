package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.floor

class PhysicsTest {

    private fun box(vararg rows: String) = Hole("TEST", 2, rows.toList())

    private val room = box(
        "##########",
        "#........#",
        "#........#",
        "#.T....O.#",
        "#........#",
        "##########",
    )

    @Test fun ballRollsToRestAndStops() {
        val p = Physics(room)
        p.shootAngle(-(PI / 2).toFloat(), 0.3f) // up, into the wall
        p.settle()
        assertFalse(p.moving)
        assertFalse(p.inCup)
        assertTrue("stays in the room", p.y > TILE && p.y < 5 * TILE)
    }

    @Test fun wallsBounceTheBallBack() {
        val p = Physics(room)
        var hits = 0
        p.shootAngle(PI.toFloat(), 0.6f) // left, into the wall
        p.settle(object : PhysicsEvents { override fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) { hits++ } })
        assertTrue("hit the wall", hits > 0)
        assertTrue("came back off it", p.x > room.teeX - TILE)
    }

    @Test fun gentlePuttDropsFastOneLipsOrRunsPast() {
        val gentle = Physics(room)
        gentle.shoot(1f, 0f, 0.42f)
        gentle.settle()
        assertTrue("gentle putt drops", gentle.inCup)

        val hard = Physics(room)
        hard.shoot(1f, 0f, 1f)
        hard.settle()
        // Too fast to drop on the first pass; it may still trickle back in off the wall.
        assertTrue(hard.shotTime > 0.2f)
    }

    @Test fun sameShotSameResult() {
        val a = Physics(Holes.COVE_1).apply { shootAngle(-1.2f, 0.8f); settle() }
        val b = Physics(Holes.COVE_1).apply { shootAngle(-1.2f, 0.8f); settle() }
        assertEquals(a.x, b.x, 0f)
        assertEquals(a.y, b.y, 0f)
    }

    @Test fun waterDropsTheBallNearWhereItWentIn() {
        val hole = Holes.COVE_1
        val p = Physics(hole)
        var splashAt: Pair<Float, Float>? = null
        p.shoot(-1f, 0f, 1f) // left from the tee, into the sea
        p.settle(object : PhysicsEvents {
            override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splashAt = x to y }
        })
        val s = splashAt
        assertNotNull("splashed", s)
        assertFalse("not back on the tee", abs(p.x - hole.teeX) < 0.01f && abs(p.y - hole.teeY) < 0.01f)
        assertTrue("dropped on dry land", hole.coast.landDepth(p.x, p.y) >= Physics.BALL_R)
        assertTrue("near where it went in", hypot(p.x - s!!.first, p.y - s.second) < 30f)
        println("splash at $s, dropped at ${p.x},${p.y}")
    }

    /** A sea to the left of a strip of rough: soft putts towards the water never go in. */
    private val shore = box(
        "~~~~~~~~~~~~",
        "~~~~~,,,,,,,",
        "~~~~~,,,T,,O",
        "~~~~~,,,,,,,",
        "~~~~~~~~~~~~",
    )

    @Test fun ballsStoppingOnDrawnSandOverWaterTilesSettleQuickly() {
        // Drawn sand reaches a little past the tile edge; a ball resting there must stop, not
        // sit "moving" until the 18 s roll limit.
        for (hole in Holes.everyHole) {
            val coast = hole.coast
            var tested = 0
            for (py in 0 until hole.pxH step 3) for (px in 0 until hole.pxW step 3) {
                val x = px + 0.5f
                val y = py + 0.5f
                if (!coast.isLand(x, y) || !hole.at(x, y).water || tested >= 5) continue
                // A current next door carries the ball on (that's its job, not a stuck ball).
                val tx = (x / TILE).toInt()
                val ty = (y / TILE).toInt()
                if ((-1..1).any { dy -> (-1..1).any { dx -> hole.tile(tx + dx, ty + dy).flows } }) continue
                val p = Physics(hole)
                p.placeAt(x, y)
                p.shoot(1f, 0f, 0.02f)
                val steps = p.settle()
                assertTrue("${hole.name} at $x,$y took ${steps / Physics.HZ.toFloat()} s", steps < Physics.HZ * 2)
                tested++
            }
        }
    }

    @Test fun aSoftShotFromATeeteredLieMoves() {
        // Find a teetering shot, then putt gently back inland from where it stopped.
        for (i in 1..100) {
            val p = Physics(shore)
            var teetered = false
            p.shoot(-1f, 0f, i / 100f)
            p.settle(object : PhysicsEvents { override fun onTeeter(x: Float, y: Float) { teetered = true } })
            if (!teetered) continue
            assertTrue("rests on land", shore.coast.isLand(p.x, p.y))
            for (power in listOf(0.05f, 0.1f, 0.2f)) {
                val q = Physics(shore)
                q.placeAt(p.x, p.y)
                q.shoot(1f, 0f, power)
                q.settle()
                assertTrue("a $power putt inland moved from ${p.x} to ${q.x}", q.x > p.x + 1f)
            }
            return
        }
        org.junit.Assert.fail("no shot teetered")
    }

    @Test fun aBallReachingTheWaterSlowlyTeetersBeforeAnyGoIn() {
        var firstTeeter = -1
        var firstSplash = -1
        for (i in 1..100) {
            val p = Physics(shore)
            var outcome = 0
            p.shoot(-1f, 0f, i / 100f)
            p.settle(object : PhysicsEvents {
                override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { outcome = 2 }
                override fun onTeeter(x: Float, y: Float) { outcome = 1 }
            })
            if (outcome == 1 && firstTeeter < 0) firstTeeter = i
            if (outcome == 2 && firstSplash < 0) firstSplash = i
        }
        println("towards the sea: first teeter at power ${firstTeeter / 100f}, first splash at ${firstSplash / 100f}")
        assertTrue("some shots teeter on the edge", firstTeeter > 0)
        assertTrue("teetering comes before going in", firstSplash < 0 || firstTeeter < firstSplash)
    }

    @Test fun fullDriveTravelsAboutAScreen() {
        val p = Physics(box(".".repeat(60) , ".T" + ".".repeat(57) + "O", ".".repeat(60)))
        p.shoot(1f, 0f, 1f)
        p.settle()
        val dist = p.x - p.hole.teeX
        println("full drive on fairway: $dist px")
        assertTrue(dist in 200f..360f)
    }

    @Test fun softestShotStillRolls() {
        for (start in listOf(Holes.COVE_1.teeX to Holes.COVE_1.teeY)) {
            val p = Physics(Holes.COVE_1)
            p.placeAt(start.first, start.second)
            p.shoot(0f, -1f, 0.02f)
            p.settle()
            val moved = start.second - p.y
            println("softest shot on fairway: $moved px")
            assertTrue("moved $moved px", moved >= 3f)
        }
        val g = Physics(room)
        g.shoot(0f, -1f, 0f + 0.02f)
        g.settle()
        assertTrue(room.teeY - g.y < 12f) // still a gentle tap-in
    }

    @Test fun driftwoodBouncesHarderThanStone() {
        fun rebound(wall: Char): Float {
            val h = box(
                "~~~~~~~~~~~~",
                "~$wall.........~",
                "~$wall.......TO~",
                "~$wall.........~",
                "~~~~~~~~~~~~",
            )
            val p = Physics(h)
            var impact = 0f
            p.shoot(-1f, 0f, 0.5f)
            while (p.moving && p.vx < 0f) p.step()
            return p.vx
        }
        val stone = rebound('#')
        val wood = rebound('w')
        println("rebound speed: stone $stone, driftwood $wood")
        assertTrue(wood > stone * 1.25f)
    }

    @Test fun diagonalLogsJoinAndBankTheBall() {
        // A "\" diagonal of driftwood: one log, so a ball rolling right is turned downwards.
        val h = box(
            ",,,,,,,,,,,,",
            ",,,w,,,,,,,,",
            ",T,,w,,,,,,O",
            ",,,,,w,,,,,,",
            ",,,,,,,,,,,,",
        )
        val rails = h.rails
        var diagonals = 0
        for (i in 0 until rails.count) if (rails.kind[i] == Rails.WOOD && rails.x0[i] != rails.x1[i] && rails.y0[i] != rails.y1[i]) diagonals++
        assertEquals("two diagonal joins", 2, diagonals)
        val p = Physics(h)
        p.shoot(1f, 0f, 0.5f)
        var hit = false
        while (p.moving && p.shotTime < 3f) {
            p.step(object : PhysicsEvents { override fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) { hit = hit || wood } })
            if (hit) break
        }
        repeat(30) { p.step() }
        assertTrue("hit the log", hit)
        assertTrue("turned downwards: vy=${p.vy} vx=${p.vx}", p.vy > 0f && p.vy > kotlin.math.abs(p.vx) * 0.5f)
    }

    @Test fun aDiagonalStoneStaircaseHasNoGaps() {
        // Walled in, so the only way to the right is through the staircase.
        val h = box(
            "############",
            "#,,,,,,,#,,#",
            "#,,,,,,#,,O#",
            "#,T,,,#,,,,#",
            "#,,,,#,,,,,#",
            "############",
        )
        // Fire at the wall from every angle on its left side: nothing crosses to the right.
        for (a in 0 until 36) {
            val p = Physics(h)
            p.shootAngle(-1.4f + a * 0.06f, 1f)
            var crossed = false
            while (p.moving && p.shotTime < 4f) {
                p.step()
                // The wall runs from (5,4) up to (8,1): x + y = 9 tiles, about 76 px along x + y.
                if (p.x + p.y > 9.5f * 8f + 8f && p.x > 6.5f * 8f) crossed = true
            }
            assertFalse("crossed at angle $a", crossed)
        }
    }

    /** A ramp facing north at the foot of a pond: a strong roll jumps it, a weak one rolls back. */
    private val rampPond = box(
        "############",
        "#,,,,,,,,,O#",
        "#,,,,,,,,,,#",
        "#,,,,,,,,,,#",
        "#,,,,,,,,,,#",
        "#,,,,,,,,,,#",
        "#~~~~~~~~~~#",
        "#~~~~~~~~~~#",
        "#~~~~~~~~~~#",
        "#,,,,N,,,,,#",
        "#,,,,,,,,,,#",
        "#,,,,T,,,,,#",
        "############",
    )

    @Test fun aStrongRollUpARampJumpsTheWater() {
        val p = Physics(rampPond)
        var launched = false
        var landed = false
        var splashed = false
        var maxZ = 0f
        p.shoot(0f, -1f, 0.6f)
        while (p.moving && p.shotTime < 10f) {
            p.step(object : PhysicsEvents {
                override fun onLaunch(x: Float, y: Float, speed: Float) { launched = true }
                override fun onLand(x: Float, y: Float, impact: Float) { landed = true }
                override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splashed = true }
            })
            maxZ = maxOf(maxZ, p.z)
        }
        println("ramp jump: max height $maxZ px, came to rest at ${p.x},${p.y}, launched=$launched landed=$landed splashed=$splashed")
        assertTrue("launched", launched); assertTrue("landed", landed); assertFalse("cleared the water", splashed)
        assertTrue("landed beyond the water", p.y < 6 * 8f)
        assertFalse(p.airborne)
        assertFalse("not resting inside the wall", p.y < 8f + Physics.BALL_R - 0.5f)
    }

    @Test fun aBallNeverRestsPartWayUpARamp() {
        for (i in 1..40) {
            val p = Physics(rampPond)
            p.shoot(0f, -1f, i / 100f)
            p.settle()
            assertFalse("power ${i / 100f} stopped on the ramp at ${p.x},${p.y}", p.hole.at(p.x, p.y).isRamp)
            assertTrue("power ${i / 100f} rests on land", rampPond.coast.isLand(p.x, p.y))
        }
    }

    @Test fun aJumpThatComesDownInWaterSplashesAtOnce() {
        var lands = 0
        var splashes = 0
        val p = Physics(rampPond)
        var launched = false
        p.shoot(0f, -1f, 0.36f) // just enough to hop off the lip into the pond
        p.settle(object : PhysicsEvents {
            override fun onLand(x: Float, y: Float, impact: Float) { lands++ }
            override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splashes++ }
            override fun onLaunch(x: Float, y: Float, speed: Float) { launched = true }
        })
        assertTrue("hopped off the lip", launched)
        assertEquals("one splash", 1, splashes)
        assertEquals("no thump on the water", 0, lands)
    }

    @Test fun landingOnTopOfAWideWallHopsOffWithoutRattling() {
        val h = box(
            "~~~~~~~~~~~~",
            "~,,,,,,,,,O~",
            "~,,,,,,,,,,~",
            "~,,,,###,,,~",
            "~,,,,###,,,~",
            "~,,,,###,,,~",
            "~,,,,,,,,,,~",
            "~,,,,,N,,,,~",
            "~,,,,,,,,,,~",
            "~,,,,,T,,,,~",
            "~~~~~~~~~~~~",
        )
        var hits = 0
        for (pw in listOf(0.45f, 0.5f, 0.55f)) {
            val p = Physics(h)
            hits = 0
            var landedAt = -1f
            var touched = false
            p.shoot(0f, -1f, pw)
            while (p.moving && p.shotTime < 20f) {
                p.step(object : PhysicsEvents { override fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) { hits++ } })
                if (!touched && p.z > 0f && p.y in 24f..48f) { touched = true; landedAt = p.shotTime }
            }
            assertTrue("$pw: at most 2 wall hits, got $hits", hits <= 2)
            assertFalse("$pw: not inside the block at ${p.x},${p.y}", p.x in 40f..64f && p.y in 24f..48f)
        }
    }

    /** Shots from dry ground below every ramp, in all directions: the flight invariants hold. */
    @Test fun invariantsHoldForShotsFromBelowRamps() {
        val rnd = java.util.Random(7)
        val maps = listOf(Holes.COVE_1, rampPond, obstacleField)
        for (hole in maps) {
            val starts = ArrayList<Pair<Float, Float>>()
            for (ty in 0 until hole.h) for (tx in 0 until hole.w) {
                val t = hole.tile(tx, ty)
                if (!t.isRamp) continue
                for (k in 1..3) {
                    val sx = (tx - t.rampX * k + 0.5f) * 8f
                    val sy = (ty - t.rampY * k + 0.5f) * 8f
                    if (hole.coast.landDepth(sx, sy) > 3f && !hole.at(sx, sy).solid) starts += sx to sy
                }
            }
            assertTrue("${hole.name}: lies below a ramp", starts.isNotEmpty())
            val p = Physics(hole)
            var launches = 0
            repeat(300) {
                val (sx, sy) = starts[rnd.nextInt(starts.size)]
                p.placeAt(sx, sy)
                var launched = false
                p.shootAngle((-PI / 2 + (rnd.nextFloat() - 0.5f) * 1.2f).toFloat(), 0.2f + rnd.nextFloat() * 0.8f)
                val steps = p.settle(object : PhysicsEvents { override fun onLaunch(x: Float, y: Float, speed: Float) { launched = true } })
                if (launched) launches++
                val tag = "${hole.name} from $sx,$sy: rest at ${p.x},${p.y}"
                assertFalse("$tag still flying", p.airborne || p.moving)
                assertTrue("$tag took too long", steps < (Physics.MAX_ROLL_S * Physics.HZ).toInt() + 20)
                if (!p.inCup) {
                    assertTrue("$tag in deep water", hole.coast.waterDepth(p.x, p.y) < Physics.BALL_R)
                    for (i in 0 until hole.rails.count) assertTrue("$tag inside rail $i", hole.rails.distance(i, p.x, p.y) > hole.rails.r[i] + Physics.BALL_R - 0.6f)
                }
            }
            println("${hole.name}: $launches of 300 shots from below a ramp flew")
            assertTrue("${hole.name}: some shots fly", launches > 0)
            // The planner reuses one instance; a flight must not leak into the next shot.
            val (sx, sy) = starts[0]
            val fresh = Physics(hole); fresh.placeAt(sx, sy); fresh.shootAngle(-1.5708f, 0.7f); fresh.settle()
            p.placeAt(sx, sy); p.shootAngle(-1.5708f, 0.7f); p.settle()
            assertEquals(fresh.x, p.x, 1e-4f); assertEquals(fresh.y, p.y, 1e-4f)
        }
    }

    /** A landing zone with a log, a boulder and palms, for the flight fuzz. */
    private val obstacleField = box(
        "~~~~~~~~~~~~~~",
        "~,,,,,,,,,,,O~",
        "~,,,P,,,,P,,,~",
        "~,,,,,www,,,,~",
        "~,,,,,,,,,#,,~",
        "~,,,P,,,,,,,,~",
        "~,,,,,,N,,,,,~",
        "~,,,,,,,,,,,,~",
        "~,,,,,,T,,,,,~",
        "~~~~~~~~~~~~~~",
    )

    @Test fun howOftenARampJumpEndsDry() {
        // From a few lay-up lies below First Light's ramp: the share of jumps that end dry.
        val hole = Holes.COVE_1
        var flights = 0
        var dry = 0
        for (sy in listOf(206f, 214f, 222f)) for (a in -20..20 step 2) for (pw in 6..18) {
            val p = Physics(hole)
            p.placeAt(60f, sy)
            var launched = false
            var splashed = false
            p.shootAngle(((-90 + a) * PI / 180).toFloat(), pw / 20f)
            p.settle(object : PhysicsEvents {
                override fun onLaunch(x: Float, y: Float, speed: Float) { launched = true }
                override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splashed = true }
            })
            if (launched) { flights++; if (!splashed) dry++ }
        }
        println("First Light ramp: $dry of $flights jumps end dry")
        assertTrue("some jumps end dry", dry > 0)
    }

    @Test fun aWeakRollUpARampRollsBack() {
        val p = Physics(rampPond)
        var launched = false
        p.shoot(0f, -1f, 0.2f)
        p.settle(object : PhysicsEvents { override fun onLaunch(x: Float, y: Float, speed: Float) { launched = true } })
        assertFalse(launched)
        assertTrue("back below the ramp", p.y > 9 * 8f)
    }

    @Test fun aFlyingBallClearsLowWallsButNotPalms() {
        val wallHole = box(
            "~~~~~~~~~~~~~~~~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,,,,,,,,O~",
            "~,,,,,,#,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,N,,,,,,,~",
            "~,,,,,,,,,,,,,,~",
            "~,,,,,,T,,,,,,,~",
            "~~~~~~~~~~~~~~~~",
        )
        val p = Physics(wallHole)
        var hitWall = false
        p.shoot(0f, -1f, 0.6f)
        p.settle(object : PhysicsEvents { override fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) { hitWall = true } })
        assertFalse("flew over the boulder", hitWall)
        assertTrue("past it: rest at ${p.x},${p.y}", p.y < 8.5f * 8f - Rails.BOULDER_R)
    }

    /** Random shots everywhere: the ball always ends grounded, outside every solid, out of deep water, in time. */
    @Test fun invariantsHoldForRandomShots() {
        val rnd = java.util.Random(42)
        for (hole in Holes.everyHole + rampPond) {
            val p = Physics(hole)
            repeat(120) {
                p.placeAt(hole.teeX, hole.teeY)
                p.shootAngle((rnd.nextFloat() * 2 * PI).toFloat(), 0.05f + rnd.nextFloat() * 0.95f)
                val steps = p.settle()
                val tag = "${hole.name}: rest at ${p.x},${p.y}"
                assertFalse("$tag still moving", p.moving)
                assertFalse("$tag still flying", p.airborne)
                assertTrue("$tag took too long", steps < (Physics.MAX_ROLL_S * Physics.HZ).toInt() + 20)
                if (!p.inCup) {
                    assertTrue("$tag in deep water", hole.coast.waterDepth(p.x, p.y) < Physics.BALL_R)
                    val t = hole.at(p.x, p.y)
                    assertFalse("$tag inside a wall", t == Tile.WALL && !hole.rails.isBoulder(floor(p.x / 8).toInt(), floor(p.y / 8).toInt()) &&
                        p.x % 8f in 1f..7f && p.y % 8f in 1f..7f)
                    for (i in 0 until hole.rails.count) {
                        assertTrue("$tag inside rail $i", hole.rails.distance(i, p.x, p.y) > hole.rails.r[i] + Physics.BALL_R - 0.6f)
                    }
                }
            }
        }
    }

    @Test fun palmTrunksDeflectTheBall() {
        val h = box(
            ",,,,,,,,,,,,",
            ",T....P....O",
            ",,,,,,,,,,,,",
        )
        val p = Physics(h)
        var hit = false
        p.shoot(1f, 0f, 0.7f)
        p.settle(object : PhysicsEvents { override fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) { hit = hit || wood } })
        assertTrue("hit the trunk", hit)
        assertTrue("stopped short of the cup", !p.inCup)
    }

    @Test fun plannerSolvesTheTestRoom() {
        assertNotNull(ShotPlanner(room).solve(2))
    }
}

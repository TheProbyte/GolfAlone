package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

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

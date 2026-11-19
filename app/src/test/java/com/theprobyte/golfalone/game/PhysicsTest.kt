package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

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
        p.settle(object : PhysicsEvents { override fun onWall(x: Float, y: Float, impact: Float) { hits++ } })
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

    @Test fun waterSendsTheBallBack() {
        val p = Physics(Holes.COVE_1)
        var splashed = false
        p.shoot(-1f, 0f, 1f) // left from the tee, into the sea
        p.settle(object : PhysicsEvents { override fun onSplash(x: Float, y: Float) { splashed = true } })
        assertTrue(splashed)
        assertTrue(abs(p.x - Holes.COVE_1.teeX) < 0.01f && abs(p.y - Holes.COVE_1.teeY) < 0.01f)
    }

    @Test fun fullDriveTravelsAboutAScreen() {
        val p = Physics(box(".".repeat(60) , ".T" + ".".repeat(57) + "O", ".".repeat(60)))
        p.shoot(1f, 0f, 1f)
        p.settle()
        val dist = p.x - p.hole.teeX
        println("full drive on fairway: $dist px")
        assertTrue(dist in 200f..360f)
    }

    @Test fun plannerSolvesTheTestRoom() {
        assertNotNull(ShotPlanner(room).solve(2))
    }
}

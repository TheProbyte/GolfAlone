package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The rules that change as you play: the tide, saved state, and the new ground. */
class HoleStateTest {

    private val flats = Hole("FLATS", 3, listOf(
        "~~~~~~~~~~~~~~",
        "~~~~~~~~~~~~~~",
        "~~,,,,,,,,,,~~",
        "~~,:::O::::,~~",
        "~~,,,,,,,,,,~~",
        "~~~tttttttt~~~",
        "~~~tttttttt~~~",
        "~~~tttttttt~~~",
        "~~~tttttttt~~~",
        "~~,,,,,,,,,,~~",
        "~~,....T...,~~",
        "~~,,,,,,,,,,~~",
        "~~~~~~~~~~~~~~",
        "~~~~~~~~~~~~~~",
    ), tideStart = 2)

    @Test fun theTideTurnsEveryThreeShots() {
        val st = HoleState(flats)
        val seen = (0 until 7).map { val h = st.high; st.advance(0f, 0f); h }
        assertEquals(listOf(false, true, true, true, false, false, false), seen)
    }

    @Test fun aBallTheTideCoversIsLiftedClearForFree() {
        val st = HoleState(flats)
        val ph = Physics(flats, st)
        // Rest the ball out on the flats while they're dry.
        ph.placeAt(7.5f * TILE, 6.5f * TILE)
        assertTrue(flats.coast.isLand(ph.x, ph.y))
        assertFalse(st.high)
        assertTrue("moved by the tide", ph.endShot())
        assertTrue(st.high)
        assertTrue("on dry land now", st.coast.landDepth(ph.x, ph.y) >= Physics.BALL_R)
        assertTrue("near where it lay", hypot(ph.x - 7.5f * TILE, ph.y - 6.5f * TILE) < 24f)
    }

    @Test fun stateSurvivesASave() {
        val crabHole = Hole("CRABS", 2, listOf(
            "~~~~~~~~~~",
            "~,,,,,,,,~",
            "~,:O::..,~",
            "~,....x.,~",
            "~,..b...,~",
            "~,...T..,~",
            "~,,,,,,,,~",
            "~~~~~~~~~~",
        ), crabs = listOf(Crab(2 to 4, 7 to 4)))
        val a = HoleState(crabHole)
        a.advance(0f, 0f); a.advance(0f, 0f)
        a.broken[0] = true
        a.bx[0] = 30.5f; a.by[0] = 41.25f
        val b = HoleState(crabHole)
        b.decode(a.encode())
        assertEquals(a.encode(), b.encode())
        assertEquals(a.key(), b.key())
        assertEquals(2, b.shots)
        assertTrue(b.broken[0])
        assertEquals(30.5f, b.bx[0])
    }

    @Test fun aBoardwalkHasNoWetEdges() {
        val walk = Hole("WALK", 3, listOf(
            "~~~~~~~~~",
            "~~,,,,,~~",
            "~~,:O:,~~",
            "~~,,.,,~~",
            "~~~~=~~~~",
            "~~~~=~~~~",
            "~~~~=~~~~",
            "~~~~=~~~~",
            "~~~~=~~~~",
            "~~,,.,,~~",
            "~~,.T.,~~",
            "~~,,,,,~~",
            "~~~~~~~~~",
        ))
        // A firm putt straight up the one-plank walk reaches the far end, dry.
        val ph = Physics(walk)
        var splashed = false
        ph.shootAngle((-Math.PI / 2).toFloat(), 0.45f)
        ph.settle(object : PhysicsEvents { override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splashed = true } })
        assertFalse("stayed on the planks", splashed)
        assertTrue("rolled the length of the walk", ph.y < 4 * TILE || ph.inCup)
    }

    @Test fun mudSwallowsARollingBall() {
        val bog = Hole("BOG", 3, listOf(
            "~~~~~~~~~",
            "~,,,,,,,~",
            "~,::O::,~",
            "~,.....,~",
            "~,mmmmm,~",
            "~,mmmmm,~",
            "~,mmmmm,~",
            "~,.....,~",
            "~,..T..,~",
            "~,,,,,,,~",
            "~~~~~~~~~",
        ))
        val ph = Physics(bog)
        ph.shootAngle((-Math.PI / 2).toFloat(), 0.7f)
        ph.settle()
        assertFalse(ph.inCup)
        assertEquals(Tile.MUD, bog.at(ph.x, ph.y))
    }
}

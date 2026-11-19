package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of courses 6–9: vents, obsidian, lava, ice, thin ice, snow, gates, tunnels, ledges, windmills, wind. */
class NewRulesTest {
    private val north = (-Math.PI / 2).toFloat()

    private class Log : PhysicsEvents {
        var splash = 0; var vent = 0; var crack = 0; var opened = 0; var tunnel = 0
        override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splash++ }
        override fun onVent(x: Float, y: Float) { vent++ }
        override fun onCrack(x: Float, y: Float) { crack++ }
        override fun onPlate(x: Float, y: Float, opened: Boolean) { if (opened) this.opened++ }
        override fun onTunnel(x0: Float, y0: Float, x1: Float, y1: Float) { tunnel++ }
    }

    /** A strip map: [mid] rows between a green at the top and a tee at the bottom. */
    private fun strip(vararg mid: String, windDeg: Float? = null, mills: List<Windmill> = emptyList()): Hole {
        val w = mid.first().length
        val sea = "~".repeat(w)
        val rows = listOf(sea, "~" + ",".repeat(w - 2) + "~", "~" + ":".repeat(w / 2 - 1) + "O" + ":".repeat(w - w / 2 - 2) + "~") +
            mid.toList() + listOf("~" + ".".repeat(w / 2 - 1) + "T" + ".".repeat(w - w / 2 - 2) + "~", "~" + ".".repeat(w - 2) + "~", sea)
        return Hole("TEST", 3, rows, windmills = mills, windDeg = windDeg)
    }

    @Test fun aVentHopsEvenASoftBallOverLava() {
        val h = strip("~,......,~", "~,......,~", "~LLLLLLLL~", "~,......,~", "~,......,~", "~,..V...,~", "~,......,~")
        val log = Log()
        val ph = Physics(h)
        ph.placeAt(4.5f * TILE, 9.5f * TILE)
        ph.shootAngle(north, 0.06f)
        ph.settle(log)
        assertEquals(1, log.vent)
        assertEquals("cleared the lava", 0, log.splash)
        assertTrue(ph.y < 5 * TILE)
    }

    @Test fun lavaIsAHazardLikeWater() {
        val h = strip("~,......,~", "~LLLLLLLL~", "~LLLLLLLL~", "~LLLLLLLL~", "~,......,~")
        val log = Log()
        val ph = Physics(h)
        ph.shootAngle(north, 0.5f)
        ph.settle(log)
        assertEquals(1, log.splash)
        assertTrue("dropped short of the lava, on land", h.coast.landDepth(ph.x, ph.y) > 0f)
    }

    @Test fun obsidianAndIceGlideFarFurtherThanGrass() {
        fun roll(ch: Char): Float {
            val row = "~," + ch.toString().repeat(6) + ",~"
            val h = strip(*Array(24) { row })
            val ph = Physics(h)
            ph.shootAngle(north, 0.12f)
            ph.settle()
            return h.teeY - ph.y
        }
        val grass = roll('.')
        assertTrue("obsidian ${roll('g')} vs grass $grass", roll('g') > grass * 2.5f)
        assertTrue("ice ${roll('i')} vs grass $grass", roll('i') > grass * 2f)
    }

    @Test fun thinIceCracksThenOpensAfterTheShot() {
        val h = strip("~,......,~", "~,......,~", "~jjjjjjjj~", "~jjjjjjjj~", "~,......,~")
        val st = HoleState(h)
        val log = Log()
        val ph = Physics(h, st)
        ph.shootAngle(north, 0.16f)
        ph.settle(log)
        assertTrue(log.crack > 0)
        assertEquals("safe on the way over", 0, log.splash)
        assertTrue(st.cracked.any { it } && st.thinOpen.none { it })
        ph.endShot()
        assertTrue("open now", st.thinOpen.any { it })
        // Back over the same line: in it goes.
        assertFalse(ph.inCup)
        ph.placeAt(h.teeX, 8.5f * TILE)
        ph.shootAngle(north, 0.25f)
        ph.settle(log)
        assertEquals(1, log.splash)
    }

    @Test fun snowStopsABallDead() {
        val h = strip("~,......,~", "~,nnnnnn,~", "~,nnnnnn,~", "~,......,~", "~,......,~")
        val ph = Physics(h)
        ph.shootAngle(north, 0.9f)
        ph.settle()
        assertEquals(Tile.SNOW, h.at(ph.x, ph.y))
    }

    @Test fun aPlateOpensItsGate() {
        // The gate row blocks the way until the plate beside the tee is pressed.
        val h = strip("~,......,~", "~###GG###~", "~###GG###~", "~,......,~", "~,......,~", "~,..p...,~", "~,......,~")
        val st = HoleState(h)
        val log = Log()
        val ph = Physics(h, st)
        ph.placeAt(4.5f * TILE, 10.5f * TILE)
        ph.shootAngle(0f, 0.05f) // nudge east, missing the plate
        ph.settle(log)
        assertFalse(st.gates[0])
        ph.placeAt(4.5f * TILE, 9.5f * TILE)
        ph.shootAngle(north, 0.5f) // over the plate, through the gate
        ph.settle(log)
        assertEquals(1, log.opened)
        assertTrue(st.gates[0])
        assertTrue("through the gate", ph.y < 4 * TILE || ph.inCup)
    }

    @Test fun aTunnelSendsTheBallOutTheOtherEnd() {
        val h = strip("~,......,~", "~,..)...,~", "~,..#...,~", "~########~", "~########~", "~,......,~", "~,..(...,~", "~,......,~")
        val log = Log()
        val ph = Physics(h)
        ph.placeAt(4.5f * TILE, 10.5f * TILE)
        ph.shootAngle(north, 0.2f)
        ph.settle(log)
        assertEquals(1, log.tunnel)
        assertTrue("came out north of the wall", ph.y < 5 * TILE)
    }

    @Test fun aLedgeIsOneWay() {
        // Rolling north, the ball drops off a north-facing ledge; rolling south, it bounces off the cliff.
        val h = strip("~,......,~", "~,......,~", "~AAAAAAAA~", "~,......,~", "~,......,~")
        val down = Physics(h)
        down.placeAt(4.5f * TILE, 7.5f * TILE)
        down.shootAngle(north, 0.25f)
        down.settle()
        assertTrue("dropped down past it", down.y < 5 * TILE)
        val up = Physics(h)
        up.placeAt(4.5f * TILE, 3.5f * TILE)
        up.shootAngle(-north, 0.4f)
        up.settle()
        assertTrue("held by the cliff", up.y < 5 * TILE)
    }

    @Test fun theWindPushesOnTheRidgeOnly() {
        val h = strip(*Array(10) { "~;;;;;;;;~" }, windDeg = 0f)
        val ph = Physics(h)
        ph.shootAngle(north, 0.3f)
        ph.settle()
        assertTrue("blown east: ${ph.x} from ${h.teeX}", ph.x > h.teeX + 4f)
    }

    @Test fun aWindmillPlaysTheSameEveryTime() {
        val mill = Windmill(4, 6, length = 14f, omega = 1.6f)
        val h = strip("~,......,~", "~,......,~", "~,......,~", "~,......,~", "~,......,~", "~,......,~", "~,......,~", mills = listOf(mill))
        fun shot(): Pair<Float, Float> {
            val ph = Physics(h)
            ph.shootAngle(north, 0.35f)
            ph.settle()
            return ph.x to ph.y
        }
        assertEquals(shot(), shot())
        // Some powers are knocked back by a blade, some get through.
        val ends = (1..12).map { p -> Physics(h).also { it.shootAngle(north, p / 12f); it.settle() }.y }
        assertTrue("a blade sometimes blocks", ends.any { it > 9 * TILE })
    }
}

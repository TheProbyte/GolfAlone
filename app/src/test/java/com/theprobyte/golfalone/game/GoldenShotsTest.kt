package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins rolling physics: fixed shots on every hole must end exactly where they did before
 * the airborne ball was added. A change here changes how every hole plays, so it should be
 * deliberate (then regenerate these numbers).
 */
class GoldenShotsTest {
    private class G(val hole: Int, val shot: Int, val x: Float, val y: Float, val splashes: Int, val holed: Boolean)

    private val shots = listOf(-1.5708f to 0.6f, -1.2f to 0.8f, -1.9f to 0.5f, -0.6f to 1.0f, -2.6f to 0.9f, -1.5708f to 0.15f)

    private val expected = listOf(
        G(0, 0, 84.0f, 270.98907f, 0, false),
        G(0, 1, 127.985405f, 146.86281f, 1, false),
        G(0, 2, 61.770794f, 260.2662f, 0, false),
        G(0, 3, 123.5276f, 232.95772f, 1, false),
        G(0, 4, 34.145065f, 230.00745f, 1, false),
        G(0, 5, 84.0f, 244.98448f, 0, false),
        G(1, 0, 92.0f, 165.13824f, 0, false),
        G(1, 1, 120.847015f, 153.80113f, 1, false),
        G(1, 2, 72.3623f, 170.5185f, 0, false),
        G(1, 3, 123.617615f, 206.36925f, 1, false),
        G(1, 4, 51.560844f, 203.67192f, 1, false),
        G(1, 5, 92.0f, 212.9845f, 0, false),
        G(2, 0, 76.0f, 155.70093f, 0, false),
        G(2, 1, 123.234535f, 126.94712f, 0, false),
        G(2, 2, 50.200428f, 91.419716f, 0, false),
        G(2, 3, 87.416985f, 96.971596f, 0, false),
        G(2, 4, 61.527737f, 85.94627f, 0, false),
        G(2, 5, 76.0f, 148.9845f, 0, false),
        G(3, 0, 92.0f, 405.46207f, 0, false),
        G(3, 1, 136.72298f, 352.96555f, 0, false),
        G(3, 2, 66.59264f, 393.62982f, 0, false),
        G(3, 3, 123.508644f, 446.4437f, 1, false),
        G(3, 4, 51.490643f, 443.62967f, 1, false),
        G(3, 5, 92.0f, 452.9846f, 0, false),
        G(4, 0, 108.0f, 324.65414f, 0, false),
        G(4, 1, 132.13397f, 293.9237f, 1, false),
        G(4, 2, 80.562f, 275.686f, 0, false),
        G(4, 3, 131.86597f, 339.67245f, 1, false),
        G(4, 4, 51.19184f, 321.82443f, 1, false),
        G(4, 5, 108.0f, 340.9846f, 0, false),
        G(5, 0, 84.000015f, 171.17685f, 1, false),
        G(5, 1, 109.73437f, 121.807335f, 1, false),
        G(5, 2, 78.3042f, 171.32782f, 1, false),
        G(5, 3, 129.42809f, 156.921f, 1, false),
        G(5, 4, 35.058838f, 158.55717f, 1, false),
        G(5, 5, 84.0f, 172.9845f, 0, false),
    )

    @Test fun groundShotsEndWhereTheyDid() {
        for (g in expected) {
            val hole = Holes.all[g.hole].withoutRamps() // ground physics only, on the maps as they were
            val p = Physics(hole)
            var splashes = 0
            val (a, pw) = shots[g.shot]
            p.shootAngle(a, pw)
            p.settle(object : PhysicsEvents { override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { splashes++ } })
            val tag = "${hole.name} shot ${g.shot}"
            assertEquals("$tag x", g.x, p.x, 1e-3f)
            assertEquals("$tag y", g.y, p.y, 1e-3f)
            assertEquals("$tag splashes", g.splashes, splashes)
            assertEquals("$tag holed", g.holed, p.inCup)
        }
    }

    @Test fun aReusedPhysicsMatchesAFreshOne() {
        // The planner reuses one instance for thousands of shots; nothing may leak between them.
        for ((hi, hole) in Holes.all.withIndex()) {
            val reused = Physics(hole)
            reused.shootAngle(-1.5708f, 1f) // leave it mid-flight or mid-roll first
            repeat(40) { reused.step() }
            for ((si, s) in shots.withIndex()) {
                reused.placeAt(hole.teeX, hole.teeY)
                reused.shootAngle(s.first, s.second)
                reused.settle()
                val fresh = Physics(hole)
                fresh.shootAngle(s.first, s.second)
                fresh.settle()
                assertEquals("hole $hi shot $si", fresh.x, reused.x, 1e-4f)
                assertEquals("hole $hi shot $si", fresh.y, reused.y, 1e-4f)
            }
        }
    }
}

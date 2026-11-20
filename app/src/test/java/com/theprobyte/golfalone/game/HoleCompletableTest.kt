package com.theprobyte.golfalone.game

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every hole can be finished at par with the real physics, and none is a gimme. */
class HoleCompletableTest {

    @Test fun everyHoleReachesParOrBetter() {
        for (hole in Holes.everyHole) {
            val start = System.currentTimeMillis()
            val planner = ShotPlanner(hole)
            var best: ShotPlanner.Plan? = null
            for (k in 1..hole.par) { best = planner.solve(k); if (best != null) break }
            println("${hole.name}: par ${hole.par}, planner best ${best?.strokes ?: "none"} " +
                "(${System.currentTimeMillis() - start} ms) ${best?.shots}")
            assertNotNull("${hole.name} can't be finished in par", best)
            // Ramps are shortcuts: the hole must still make par without them.
            if (hole.hasRamps) assertNotNull("${hole.name} needs its ramp for par", ShotPlanner(hole.withoutRamps()).solve(hole.par))
            // The tide is a shortcut too: with it always in, par must still be possible.
            if (hole.tidal) {
                val dry = (1..hole.par).firstNotNullOfOrNull { ShotPlanner(hole.withoutTide()).solve(it) }
                println("${hole.name}: with the tide always in, planner best ${dry?.strokes ?: "none"}")
                assertNotNull("${hole.name} needs the tide for par", dry)
            }
            // A perfect bot shouldn't ace a par 4.
            if (hole.par >= 4) assertTrue("${hole.name} is too easy", best!!.shots.size >= 2)
        }
    }

    /**
     * Barrels can't be undone, and a round has no restart, so no barrel may ever leave a hole
     * unfinishable: from states reached by random shots, the bot must still find the cup.
     */
    @Test fun barrelsNeverLockAHole() {
        val rnd = java.util.Random(11)
        // Barrels and thin ice both change for good: neither may ever strand the ball.
        for (hole in Holes.everyHole.filter { it.barrels.isNotEmpty() || it.thinCount > 0 }) {
            repeat(6) { trial ->
                val st = HoleState(hole)
                val ph = Physics(hole, st)
                repeat(1 + rnd.nextInt(3)) {
                    if (!ph.inCup) {
                        ph.shootAngle((rnd.nextDouble() * 2 * Math.PI).toFloat(), 0.2f + rnd.nextFloat() * 0.8f)
                        ph.settle()
                        ph.endShot()
                    }
                }
                if (ph.inCup) return@repeat
                val plan = (1..6).firstNotNullOfOrNull { ShotPlanner(hole, beam = 4).solveFrom(ph.x, ph.y, it, st) }
                assertNotNull("${hole.name} trial $trial: stuck at ${ph.x},${ph.y} (barrels ${st.encode()})", plan)
            }
        }
    }
}

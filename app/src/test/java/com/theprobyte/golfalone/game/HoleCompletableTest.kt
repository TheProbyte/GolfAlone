package com.theprobyte.golfalone.game

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every hole can be finished at par with the real physics, and none is a gimme. */
class HoleCompletableTest {

    @Test fun everyHoleReachesParOrBetter() {
        for (hole in Holes.all) {
            val start = System.currentTimeMillis()
            val planner = ShotPlanner(hole)
            var best: ShotPlanner.Plan? = null
            for (k in 1..hole.par) { best = planner.solve(k); if (best != null) break }
            println("${hole.name}: par ${hole.par}, planner best ${best?.shots?.size ?: "none"} " +
                "(${System.currentTimeMillis() - start} ms) ${best?.shots}")
            assertNotNull("${hole.name} can't be finished in par", best)
            // Ramps are shortcuts: the hole must still make par without them.
            if (hole.hasRamps) assertNotNull("${hole.name} needs its ramp for par", ShotPlanner(hole.withoutRamps()).solve(hole.par))
            // A perfect bot shouldn't ace a par 4.
            if (hole.par >= 4) assertTrue("${hole.name} is too easy", best!!.shots.size >= 2)
        }
    }
}

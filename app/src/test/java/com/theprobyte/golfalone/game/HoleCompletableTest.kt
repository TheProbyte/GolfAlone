package com.theprobyte.golfalone.game

import org.junit.Assert.assertNotNull
import org.junit.Test

/** Every hole can be finished at par with the real physics (and so well within par + 2). */
class HoleCompletableTest {

    @Test fun everyHoleReachesParOrBetter() {
        for (hole in Holes.all) {
            val start = System.currentTimeMillis()
            val plan = ShotPlanner(hole).solve(hole.par)
            println("${hole.name}: par ${hole.par}, planner ${plan?.shots?.size ?: "none"} " +
                "(${System.currentTimeMillis() - start} ms) ${plan?.shots}")
            assertNotNull("${hole.name} can't be finished in par", plan)
        }
    }
}

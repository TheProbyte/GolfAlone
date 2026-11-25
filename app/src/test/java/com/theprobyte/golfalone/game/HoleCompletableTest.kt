package com.theprobyte.golfalone.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every hole can be finished at par with the real physics, and none is a gimme. */
class HoleCompletableTest {

    /** Each hole's bot best when it was made: a change of physics or map that moves one shows up here. */
    private val pins: Map<String, Int> by lazy {
        javaClass.getResourceAsStream("/bests.txt")?.bufferedReader()?.readLines().orEmpty()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore(' ') to it.substringAfter(' ').trim().toInt() }
    }

    private class Proof(val key: String, val hole: Hole, val best: ShotPlanner.Plan?, val noRamps: Boolean, val dry: ShotPlanner.Plan?, val ms: Long)

    private fun prove(key: String, hole: Hole): Proof {
        val start = System.currentTimeMillis()
        val planner = ShotPlanner(hole)
        var best: ShotPlanner.Plan? = null
        for (k in 1..hole.par) { best = planner.solve(k); if (best != null) break }
        // Ramps are shortcuts: the hole must still make par without them.
        val noRamps = !hole.hasRamps || ShotPlanner(hole.withoutRamps()).solve(hole.par) != null
        // The tide is a shortcut too: with it always in, par must still be possible.
        val dry = if (hole.tidal) (1..hole.par).firstNotNullOfOrNull { ShotPlanner(hole.withoutTide()).solve(it) } else null
        return Proof(key, hole, best, noRamps, dry, System.currentTimeMillis() - start)
    }

    @Test fun everyHoleReachesParOrBetter() {
        val all = Holes.allCourses.flatMap { c -> c.holes.mapIndexed { i, h -> "${c.id}-${i + 1}" to h } }
        // Holes are independent, so they're proved side by side (each search is the same as alone).
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val proofs = try { all.map { (key, hole) -> pool.submit<Proof> { prove(key, hole) } }.map { it.get() } } finally { pool.shutdown() }
        val write = System.getProperty("golfalone.pins")
        if (write != null) {
            java.io.File(write).writeText("# The bot's best on each hole (course-hole best), checked by HoleCompletableTest.\n" +
                proofs.joinToString("") { "${it.key} ${it.best?.strokes ?: 0}\n" })
        }
        for (p in proofs) {
            val hole = p.hole
            val best = p.best
            println("${p.key} ${hole.name}: par ${hole.par}, planner best ${best?.strokes ?: "none"} (${p.ms} ms) ${best?.shots}")
            assertNotNull("${hole.name} can't be finished in par", best)
            assertTrue("${hole.name} needs its ramp for par", p.noRamps)
            if (hole.tidal) {
                println("${hole.name}: with the tide always in, planner best ${p.dry?.strokes ?: "none"}")
                assertNotNull("${hole.name} needs the tide for par", p.dry)
            }
            // A perfect bot shouldn't ace a par 4.
            if (hole.par >= 4) assertTrue("${hole.name} is too easy", best!!.shots.size >= 2)
            if (write == null) assertEquals("${p.key} ${hole.name}: the bot's best moved (pinned in bests.txt)", pins[p.key], best!!.strokes)
        }
    }

    /**
     * Barrels can't be undone, and a round has no restart, so no barrel may ever leave a hole
     * unfinishable: from states reached by random shots, the bot must still find the cup.
     */
    @Test fun barrelsNeverLockAHole() {
        // Barrels and thin ice both change for good: neither may ever strand the ball.
        val holes = Holes.everyHole.filter { it.barrels.isNotEmpty() || it.thinCount > 0 }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        // Each hole gets its own seeded stream, so the trials are the same however the holes are scheduled.
        val stuck = try {
            holes.mapIndexed { hi, hole -> pool.submit<String?> { strandedTrial(hole, java.util.Random(11L + hi * 7919L)) } }.map { it.get() }
        } finally { pool.shutdown() }
        for (s in stuck) assertTrue(s ?: "", s == null)
    }

    /** Six random openings on [hole]; the first one the bot can't finish from, or null. */
    private fun strandedTrial(hole: Hole, rnd: java.util.Random): String? {
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
            // A narrow search first (quick); a full one before calling the hole locked.
            val plan = (1..6).firstNotNullOfOrNull { ShotPlanner(hole, beam = 4).solveFrom(ph.x, ph.y, it, st) }
                ?: (1..8).firstNotNullOfOrNull { ShotPlanner(hole).solveFrom(ph.x, ph.y, it, st) }
            if (plan == null) return "${hole.name} trial $trial: stuck at ${ph.x},${ph.y} (barrels ${st.encode()})"
        }
        return null
    }
}

package com.theprobyte.golfalone

import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.HoleState
import com.theprobyte.golfalone.game.Physics
import com.theprobyte.golfalone.game.PhysicsEvents
import com.theprobyte.golfalone.game.ShotPlanner
import com.theprobyte.golfalone.game.Windmill
import com.theprobyte.golfalone.render.Terrain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Not part of the normal suite: with -Pcandidates=<file>, proves a batch of generated holes
 * (the bot's best, whether the drawn coast matches physics) and writes the results beside it.
 * The file holds blocks of "== id", "key=value" lines and the map rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateProofTest {
    @Test fun proveCandidates() {
        val path = System.getProperty("golfalone.candidates") ?: return
        val withTolerance = System.getProperty("golfalone.tolerance") != null
        val blocks = File(path).readText().split("== ").filter { it.isNotBlank() }
        // Candidates are independent: prove them side by side.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val lines = try {
            blocks.map { b -> pool.submit<String> { prove(b, withTolerance) } }.map { it.get() }
        } finally { pool.shutdown() }
        File("$path.results").writeText(lines.joinToString("") { it + "\n" })
    }

    /** With -PshippedTolerance=<file>: the tolerance of every shipped hole, as a baseline. */
    @Test fun shippedTolerance() {
        val out = System.getProperty("golfalone.shippedTolerance") ?: return
        val all = com.theprobyte.golfalone.game.Holes.allCourses.flatMap { c -> c.holes.mapIndexed { i, h -> "${c.id}-${i + 1}" to h } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val lines = try {
            all.map { (key, hole) ->
                pool.submit<String> {
                    val planner = ShotPlanner(hole)
                    val best = (1..hole.par).firstNotNullOfOrNull { planner.solve(it) }
                    // With -PatBest, at the bot's best + 1 (how second rounds are scored) instead of the hole's own par.
                    val par = if (System.getProperty("golfalone.atBest") != null && best != null) best.strokes + 1 else hole.par
                    "$key par=$par best=${best?.strokes ?: 0} tol=${if (best == null) "-" else "%.2f".format(tolerance(hole, best, par, planner))}"
                }
            }.map { it.get() }
        } finally { pool.shutdown() }
        File(out).writeText(lines.joinToString("") { it + "\n" })
    }

    private fun prove(block: String, withTolerance: Boolean): String {
        val lines = block.lines()
        val id = lines.first().trim()
        val at = lines.indexOfFirst { it.trim() == "rows:" }
        val props = lines.subList(1, at).filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val rows = lines.drop(at + 1).filter { it.isNotBlank() }
        val res = runCatching {
            val mills = props["mills"]?.split(';')?.filter { it.isNotBlank() }?.map { m ->
                val v = m.split(',').map { it.trim().toFloat() }
                Windmill(v[0].toInt(), v[1].toInt(), v[2], v[3], v[4])
            } ?: emptyList()
            val crabs = props["crabs"]?.split('|')?.filter { it.isNotBlank() }?.map { c ->
                com.theprobyte.golfalone.game.Crab(*c.split(':').map { p -> p.substringBefore(',').toInt() to p.substringAfter(',').toInt() }.toTypedArray())
            } ?: emptyList()
            val hole = Hole(id, 9, rows, windmills = mills, windDeg = props["wind"]?.toFloat(), tideStart = props["tide"]?.toInt() ?: 0, crabs = crabs)
            hole.style = props["style"] ?: ""
            val t = Terrain(hole)
            val (land, _, _) = t.masks()
            val coastOk = land.contentEquals(hole.coast.land)
            val m = t.mismatch()
            val start = System.currentTimeMillis()
            val planner = ShotPlanner(hole)
            var best: ShotPlanner.Plan? = null
            for (k in 1..7) { best = planner.solve(k); if (best != null) break }
            val ms = System.currentTimeMillis() - start
            val tol = if (withTolerance && best != null && coastOk && best.strokes in 2..6) " tol=${"%.2f".format(tolerance(hole, best, best.strokes + 1, planner))}" else ""
            // The rules HoleCompletableTest holds every hole to: par without the tide, and without ramps.
            val par = (best?.strokes ?: 0) + 1
            val dry = if (best != null && hole.tidal) " dry=${if ((1..par).any { ShotPlanner(hole.withoutTide()).solve(it) != null }) 1 else 0}" else ""
            val flat = if (best != null && hole.hasRamps) " flat=${if (ShotPlanner(hole.withoutRamps()).solve(par) != null) 1 else 0}" else ""
            "best=${best?.strokes ?: 0} coast=$coastOk stray=${m.maxOrNull()} ms=$ms$tol$dry$flat"
        }.getOrElse { "error=${it.message?.replace('\n', ' ')}" }
        return "$id $res"
    }

    private class Splashes : PhysicsEvents {
        var n = 0
        override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { n++ }
    }

    /**
     * How forgiving the hole is: for each shot of the bot's [plan], the share of its eight
     * neighbours (one step of angle and/or power off) after which the bot can still make [par].
     */
    private fun tolerance(hole: Hole, plan: ShotPlanner.Plan, par: Int, planner: ShotPlanner): Float {
        val aStep = (2 * Math.PI / 96).toFloat()
        val pStep = 1f / 18f
        val st = HoleState(hole)
        val ph = Physics(hole, st)
        var x = hole.teeX
        var y = hole.teeY
        var used = 0
        var ok = 0
        var tried = 0
        for (shot in plan.shots) {
            for (da in -1..1) for (dp in -1..1) {
                if (da == 0 && dp == 0) continue
                val s2 = HoleState(hole).also { it.copyFrom(st) }
                val p2 = Physics(hole, s2)
                p2.placeAt(x, y)
                p2.shootAngle(shot.angle + da * aStep, (shot.power + dp * pStep).coerceIn(0.05f, 1f))
                val sp = Splashes()
                p2.settle(sp)
                tried++
                val strokes = used + 1 + sp.n
                if (p2.inCup) { if (strokes <= par) ok++; continue }
                p2.endShot()
                val left = par - strokes
                if (left > 0 && planner.solveFrom(p2.x, p2.y, left, s2) != null) ok++
            }
            ph.placeAt(x, y)
            ph.shootAngle(shot.angle, shot.power)
            val sp = Splashes()
            ph.settle(sp)
            used += 1 + sp.n
            if (ph.inCup) break
            ph.endShot()
            x = ph.x; y = ph.y
        }
        return if (tried == 0) 1f else ok / tried.toFloat()
    }
}

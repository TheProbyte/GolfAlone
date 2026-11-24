package com.theprobyte.golfalone

import com.theprobyte.golfalone.game.Hole
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
        val out = StringBuilder()
        for (block in File(path).readText().split("== ").filter { it.isNotBlank() }) {
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
                val hole = Hole(id, 9, rows, windmills = mills, windDeg = props["wind"]?.toFloat())
                hole.style = props["style"] ?: ""
                val t = Terrain(hole)
                val (land, _, _) = t.masks()
                val coastOk = land.contentEquals(hole.coast.land)
                val m = t.mismatch()
                val start = System.currentTimeMillis()
                val planner = ShotPlanner(hole)
                var best: ShotPlanner.Plan? = null
                for (k in 1..7) { best = planner.solve(k); if (best != null) break }
                "best=${best?.strokes ?: 0} coast=$coastOk stray=${m.maxOrNull()} ms=${System.currentTimeMillis() - start}"
            }.getOrElse { "error=${it.message?.replace('\n', ' ')}" }
            out.append(id).append(' ').append(res).append('\n')
        }
        File("$path.results").writeText(out.toString())
    }
}

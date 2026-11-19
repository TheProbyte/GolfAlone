package com.theprobyte.golfalone.game

import kotlin.math.PI
import kotlin.math.floor

/**
 * Finds a way round a hole with the real physics: a beam search over a grid of shot
 * angles and powers, ranked by walking distance to the cup over land tiles.
 */
class ShotPlanner(private val hole: Hole, private val angles: Int = 96, private val powers: Int = 18, private val beam: Int = 6) {

    data class Shot(val angle: Float, val power: Float)
    data class Plan(val shots: List<Shot>)

    private class Node(val x: Float, val y: Float, val shots: List<Shot>, val score: Float)

    /** Walking distance (in tiles) from each tile to the cup, over non-water, non-wall tiles. */
    val distance: IntArray = IntArray(hole.w * hole.h) { Int.MAX_VALUE }.also { d ->
        val cx = floor(hole.cupX / TILE).toInt()
        val cy = floor(hole.cupY / TILE).toInt()
        val q = ArrayDeque<Int>()
        d[cy * hole.w + cx] = 0
        q.add(cy * hole.w + cx)
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val x = i % hole.w
            val y = i / hole.w
            for ((nx, ny) in arrayOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                if (nx !in 0 until hole.w || ny !in 0 until hole.h) continue
                val t = hole.tile(nx, ny)
                val j = ny * hole.w + nx
                if (t.solid || t.water || d[j] != Int.MAX_VALUE) continue
                d[j] = d[i] + 1
                q.add(j)
            }
        }
    }

    private fun score(x: Float, y: Float): Float {
        val tx = floor(x / TILE).toInt()
        val ty = floor(y / TILE).toInt()
        if (tx !in 0 until hole.w || ty !in 0 until hole.h) return Float.MAX_VALUE
        val d = distance[ty * hole.w + tx]
        if (d == Int.MAX_VALUE) return Float.MAX_VALUE
        val t = hole.tile(tx, ty)
        return d + if (t == Tile.SAND) 4f else if (t == Tile.ROUGH) 1.5f else 0f
    }

    /** The shortest plan found within [maxStrokes], or null. */
    fun solve(maxStrokes: Int): Plan? {
        val sim = Physics(hole)
        var frontier = listOf(Node(hole.teeX, hole.teeY, emptyList(), score(hole.teeX, hole.teeY)))
        repeat(maxStrokes) {
            val next = HashMap<Long, Node>()
            for (n in frontier) for (a in 0 until angles) for (p in 1..powers) {
                val shot = Shot((2 * PI * a / angles).toFloat(), p / powers.toFloat())
                sim.placeAt(n.x, n.y)
                sim.shootAngle(shot.angle, shot.power)
                sim.settle()
                val shots = n.shots + shot
                if (sim.inCup) return Plan(shots)
                val s = score(sim.x, sim.y)
                if (s == Float.MAX_VALUE) continue
                // One survivor per 4 px cell keeps the beam diverse.
                val key = (floor(sim.x / 4).toLong() shl 32) or (floor(sim.y / 4).toLong() and 0xffffffffL)
                val old = next[key]
                if (old == null || s < old.score) next[key] = Node(sim.x, sim.y, shots, s)
            }
            frontier = next.values.sortedBy { it.score }.take(beam)
            if (frontier.isEmpty()) return null
        }
        return null
    }
}

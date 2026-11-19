package com.theprobyte.golfalone.game

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Round-ended segments ("rails") that walls and logs are made of, shared by physics and
 * drawing so diagonal runs connect:
 * - **Driftwood** is entirely rails, joined tile centre to tile centre, diagonally where two
 *   logs only touch at a corner. A diagonal log banks the ball at 45°.
 * - **Stone** stays tile blocks, plus a rail across each diagonal gap, so a staircase of
 *   blocks reads (and plays) as one wall.
 * - **A lone stone** is a round boulder.
 */
class Rails(private val hole: Hole) {
    var count = 0; private set
    val x0 = FloatArray(MAX); val y0 = FloatArray(MAX)
    val x1 = FloatArray(MAX); val y1 = FloatArray(MAX)
    val r = FloatArray(MAX)
    val kind = ByteArray(MAX)
    /** Whether each end is the end of a log (gets a cut face) rather than a joint. */
    val openA = BooleanArray(MAX)
    val openB = BooleanArray(MAX)

    /** Rails near each tile (indices), for quick lookups. */
    private val cells: Array<IntArray>

    /** Single stone tiles drawn and played as boulders. */
    private val boulder = BooleanArray(hole.w * hole.h)

    init {
        val w = hole.w
        val h = hole.h
        fun t(x: Int, y: Int) = hole.tile(x, y)
        fun cx(x: Int) = (x + 0.5f) * TILE
        fun cy(y: Int) = (y + 0.5f) * TILE
        val degree = IntArray(w * h)
        val edges = ArrayList<IntArray>() // x0, y0, x1, y1, kind
        for (y in 0 until h) for (x in 0 until w) {
            when (t(x, y)) {
                Tile.WOOD -> {
                    if (t(x + 1, y) == Tile.WOOD) edges += intArrayOf(x, y, x + 1, y, WOOD.toInt())
                    if (t(x, y + 1) == Tile.WOOD) edges += intArrayOf(x, y, x, y + 1, WOOD.toInt())
                    for (dx in intArrayOf(-1, 1)) {
                        if (t(x + dx, y + 1) == Tile.WOOD && t(x + dx, y) != Tile.WOOD && t(x, y + 1) != Tile.WOOD) {
                            edges += intArrayOf(x, y, x + dx, y + 1, WOOD.toInt())
                        }
                    }
                }
                Tile.WALL -> {
                    for (dx in intArrayOf(-1, 1)) {
                        if (t(x + dx, y + 1) == Tile.WALL && t(x + dx, y) != Tile.WALL && t(x, y + 1) != Tile.WALL) {
                            edges += intArrayOf(x, y, x + dx, y + 1, STONE.toInt())
                        }
                    }
                    var alone = true
                    for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && t(x + dx, y + dy) == Tile.WALL) alone = false
                    if (alone) boulder[y * w + x] = true
                }
                else -> {}
            }
        }
        for (e in edges) { degree[e[1] * w + e[0]]++; degree[e[3] * w + e[2]]++ }
        for (e in edges) {
            add(cx(e[0]), cy(e[1]), cx(e[2]), cy(e[3]), if (e[4] == WOOD.toInt()) WOOD_R else STONE_R, e[4].toByte(),
                degree[e[1] * w + e[0]] == 1, degree[e[3] * w + e[2]] == 1)
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (t(x, y) == Tile.WOOD && degree[y * w + x] == 0) add(cx(x) - 3f, cy(y), cx(x) + 3f, cy(y), WOOD_R, WOOD, true, true)
            if (boulder[y * w + x]) add(cx(x), cy(y), cx(x), cy(y), BOULDER_R, BOULDER, true, true)
        }
        // Bucket rails by the tiles their (padded) bounds touch.
        val lists = Array(w * h) { ArrayList<Int>() }
        for (i in 0 until count) {
            val pad = r[i] + 4f
            val tx0 = floor((min(x0[i], x1[i]) - pad) / TILE).toInt().coerceAtLeast(0)
            val tx1 = floor((max(x0[i], x1[i]) + pad) / TILE).toInt().coerceAtMost(w - 1)
            val ty0 = floor((min(y0[i], y1[i]) - pad) / TILE).toInt().coerceAtLeast(0)
            val ty1 = floor((max(y0[i], y1[i]) + pad) / TILE).toInt().coerceAtMost(h - 1)
            for (ty in ty0..ty1) for (tx in tx0..tx1) lists[ty * w + tx] += i
        }
        cells = Array(w * h) { lists[it].toIntArray() }
    }

    private fun add(ax: Float, ay: Float, bx: Float, by: Float, rad: Float, k: Byte, oa: Boolean, ob: Boolean) {
        if (count >= MAX) return
        x0[count] = ax; y0[count] = ay; x1[count] = bx; y1[count] = by
        r[count] = rad; kind[count] = k; openA[count] = oa; openB[count] = ob
        count++
    }

    fun isBoulder(tx: Int, ty: Int): Boolean =
        tx in 0 until hole.w && ty in 0 until hole.h && boulder[ty * hole.w + tx]

    /** Rails that may touch tile ([tx], [ty]). */
    fun near(tx: Int, ty: Int): IntArray =
        if (tx < 0 || ty < 0 || tx >= hole.w || ty >= hole.h) EMPTY else cells[ty * hole.w + tx]

    /** Parameter (0..1) of the point on rail [i] closest to ([px], [py]). */
    fun along(i: Int, px: Float, py: Float): Float {
        val dx = x1[i] - x0[i]
        val dy = y1[i] - y0[i]
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return 0f
        return (((px - x0[i]) * dx + (py - y0[i]) * dy) / l2).coerceIn(0f, 1f)
    }

    /** Distance from ([px], [py]) to the centre line of rail [i]. */
    fun distance(i: Int, px: Float, py: Float): Float {
        val t = along(i, px, py)
        return hypot(px - (x0[i] + (x1[i] - x0[i]) * t), py - (y0[i] + (y1[i] - y0[i]) * t))
    }

    companion object {
        const val MAX = 512
        const val STONE: Byte = 0
        const val WOOD: Byte = 1
        const val BOULDER: Byte = 2
        const val WOOD_R = 3.5f
        const val STONE_R = 4f
        const val BOULDER_R = 4.5f
        private val EMPTY = IntArray(0)
    }
}

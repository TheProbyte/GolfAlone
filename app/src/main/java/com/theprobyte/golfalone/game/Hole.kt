package com.theprobyte.golfalone.game

import kotlin.math.floor

/** World units are logical pixels; a tile is 8×8. */
const val TILE = 8

/**
 * Ground types. [c] is constant rolling resistance (px/s²), [k] linear drag (1/s),
 * [slopeX]/[slopeY] the downhill direction on sloped green.
 */
enum class Tile(
    val solid: Boolean = false,
    val water: Boolean = false,
    val c: Float = 0f,
    val k: Float = 0f,
    val slopeX: Float = 0f,
    val slopeY: Float = 0f,
    /** Bounciness when [solid]. */
    val e: Float = 0.62f,
    /** Launch direction of a ramp (it rises that way), or 0. */
    val rampX: Float = 0f,
    val rampY: Float = 0f,
) {
    WATER(water = true),
    WALL(solid = true),
    WOOD(solid = true, e = 0.9f),
    FAIRWAY(c = 40f, k = 1.2f),
    ROUGH(c = 115f, k = 2.3f),
    GREEN(c = 28f, k = 0.9f),
    SAND(c = 330f, k = 4.5f),
    SLOPE_N(c = 28f, k = 0.9f, slopeY = -1f),
    SLOPE_S(c = 28f, k = 0.9f, slopeY = 1f),
    SLOPE_E(c = 28f, k = 0.9f, slopeX = 1f),
    SLOPE_W(c = 28f, k = 0.9f, slopeX = -1f),
    RAMP_N(c = 40f, k = 1.2f, rampY = -1f),
    RAMP_S(c = 40f, k = 1.2f, rampY = 1f),
    RAMP_E(c = 40f, k = 1.2f, rampX = 1f),
    RAMP_W(c = 40f, k = 1.2f, rampX = -1f);

    val isGreen get() = this == GREEN || slopeX != 0f || slopeY != 0f
    val isRamp get() = rampX != 0f || rampY != 0f
    val isLand get() = !water
}

/**
 * A hole parsed from an ASCII map:
 * `~` water, `#` stone, `.` fairway, `,` rough, `:` green, `s` sand,
 * `^ v < >` green sloping downhill that way, `T` tee (fairway), `O` cup (green),
 * `w` driftwood (bouncy), `P` a palm (a round trunk standing in the rough),
 * `N E S W` a wooden ramp that rises (and launches the ball) in that direction.
 */
class Hole(val name: String, val par: Int, val rows: List<String>) {
    val w: Int = rows.first().length
    val h: Int = rows.size
    val tiles: Array<Tile>
    val teeX: Float
    val teeY: Float
    val cupX: Float
    val cupY: Float
    /** Palm trunks: x, y pairs (px), each a post of radius [POST_R]. */
    val posts: FloatArray
    val pxW get() = w * TILE
    val pxH get() = h * TILE

    /** The drawn coastline, per pixel; physics and rendering share it. */
    val coast: Coast by lazy { Coast(this) }

    /** Logs, diagonal wall joins and boulders as round-ended segments; physics and rendering share them. */
    val rails: Rails by lazy { Rails(this) }

    init {
        require(rows.all { it.length == w }) {
            "$name: rows must all be $w wide: " + rows.withIndex().filter { it.value.length != w }.map { it.index }
        }
        var tee: Pair<Int, Int>? = null
        var cup: Pair<Int, Int>? = null
        val palms = ArrayList<Float>()
        tiles = Array(w * h) { i ->
            val x = i % w
            val y = i / w
            when (val ch = rows[y][x]) {
                '~' -> Tile.WATER
                '#' -> Tile.WALL
                '.' -> Tile.FAIRWAY
                ',' -> Tile.ROUGH
                ':' -> Tile.GREEN
                's' -> Tile.SAND
                'w' -> Tile.WOOD
                'P' -> { palms += (x + 0.5f) * TILE; palms += (y + 0.5f) * TILE; Tile.ROUGH }
                '^' -> Tile.SLOPE_N
                'v' -> Tile.SLOPE_S
                '>' -> Tile.SLOPE_E
                '<' -> Tile.SLOPE_W
                'N' -> Tile.RAMP_N
                'S' -> Tile.RAMP_S
                'E' -> Tile.RAMP_E
                'W' -> Tile.RAMP_W
                'T' -> { tee = x to y; Tile.FAIRWAY }
                'O' -> { cup = x to y; Tile.GREEN }
                else -> error("$name: unknown tile '$ch' at $x,$y")
            }
        }
        val t = requireNotNull(tee) { "$name: no tee" }
        val c = requireNotNull(cup) { "$name: no cup" }
        teeX = (t.first + 0.5f) * TILE
        teeY = (t.second + 0.5f) * TILE
        cupX = (c.first + 0.5f) * TILE
        cupY = (c.second + 0.5f) * TILE
        posts = palms.toFloatArray()
    }

    companion object {
        const val POST_R = 2.5f
    }

    /** The same hole with its ramps paved over (to prove par doesn't depend on them). */
    fun withoutRamps(): Hole = Hole(name, par, rows.map { r -> r.map { if (it in "NESW") '.' else it }.joinToString("") })

    val hasRamps: Boolean get() = tiles.any { it.isRamp }

    /** Off the map is open sea. */
    fun tile(tx: Int, ty: Int): Tile =
        if (tx < 0 || ty < 0 || tx >= w || ty >= h) Tile.WATER else tiles[ty * w + tx]

    fun at(x: Float, y: Float): Tile = tile(floor(x / TILE).toInt(), floor(y / TILE).toInt())
}

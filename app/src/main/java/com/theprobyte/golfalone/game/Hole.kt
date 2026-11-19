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
    /** Rollable water over sand (shallows and currents): land for the rules, drawn as water. */
    val shallow: Boolean = false,
    /** A current's direction (it carries the ball that way), or 0. */
    val flowX: Float = 0f,
    val flowY: Float = 0f,
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
    RAMP_W(c = 40f, k = 1.2f, rampX = -1f),
    /** Firm wet sand that the tide covers at high water (see [Tide]). */
    TIDAL(c = 60f, k = 1.6f),
    /** Thick mud: the ball stops almost dead. */
    MUD(c = 450f, k = 6f),
    /** Boardwalk or ship's deck: planks over water, with straight edges. */
    DECK(c = 36f, k = 1f),
    SHALLOWS(c = 200f, k = 3.5f, shallow = true),
    CURRENT_N(shallow = true, flowY = -1f),
    CURRENT_S(shallow = true, flowY = 1f),
    CURRENT_E(shallow = true, flowX = 1f),
    CURRENT_W(shallow = true, flowX = -1f),
    /** A wreck's timbers: a wooden wall. */
    HULL(solid = true, e = 0.7f),
    /** A cracked plank: a wooden wall that a hard hit smashes open (deck beneath). */
    PLANK(solid = true, e = 0.55f);

    val isGreen get() = this == GREEN || slopeX != 0f || slopeY != 0f
    val isRamp get() = rampX != 0f || rampY != 0f
    val isLand get() = !water
    val flows get() = flowX != 0f || flowY != 0f
    /** Made of wood (for sounds): driftwood, hull and planks. */
    val wooden get() = this == WOOD || this == HULL || this == PLANK
}

/** The tide: it moves one step per shot, low for [PERIOD]/2 shots, then high. */
object Tide {
    const val PERIOD = 6
    fun phase(p: Int) = Math.floorMod(p, PERIOD)
    fun high(p: Int) = phase(p) >= PERIOD / 2
}

/** A hermit crab's patrol: tiles walked one per shot, there and back. */
class Crab(vararg waypoints: Pair<Int, Int>) {
    val tiles: List<Pair<Int, Int>>
    init {
        val t = ArrayList<Pair<Int, Int>>()
        t += waypoints.first()
        for (k in 1 until waypoints.size) {
            var (x, y) = waypoints[k - 1]
            val (x1, y1) = waypoints[k]
            require(x == x1 || y == y1) { "crab paths go in straight lines" }
            while (x != x1 || y != y1) { x += Integer.signum(x1 - x); y += Integer.signum(y1 - y); t += x to y }
        }
        tiles = t
    }
    /** Steps in a full patrol (out and back). */
    val cycle get() = if (tiles.size <= 1) 1 else 2 * tiles.size - 2
    fun tileAt(step: Int): Pair<Int, Int> {
        val s = Math.floorMod(step, cycle)
        return tiles[if (s < tiles.size) s else cycle - s]
    }
}

/**
 * A hole parsed from an ASCII map:
 * `~` water, `#` stone, `.` fairway, `,` rough, `:` green, `s` sand,
 * `^ v < >` green sloping downhill that way, `T` tee (fairway), `O` cup (green),
 * `w` driftwood (bouncy), `P` a palm (a round trunk standing in the rough),
 * `N E S W` a wooden ramp that rises (and launches the ball) in that direction.
 *
 * Later courses add: `t` tidal sand, `m` mud, `=` deck or boardwalk, `-` shallows,
 * `8 2 6 4` a current flowing north, south, east or west (as on a number pad), `H` hull
 * timber, `x` a cracked plank, `k` a coral head (in shallows), `o` a bumper buoy (on fairway),
 * `D` a barrel on deck and `b` a barrel on fairway.
 */
class Hole(
    val name: String,
    val par: Int,
    val rows: List<String>,
    /** The tide's phase before the first shot (see [Tide]). */
    val tideStart: Int = 0,
    val crabs: List<Crab> = emptyList(),
    /** Scenery only: a lighthouse (its tile is drawn as the tower) and lantern posts, as tiles. */
    val lighthouse: Pair<Int, Int>? = null,
    val lanterns: List<Pair<Int, Int>> = emptyList(),
) {
    val w: Int = rows.first().length
    val h: Int = rows.size
    val tiles: Array<Tile>
    val teeX: Float
    val teeY: Float
    val cupX: Float
    val cupY: Float
    /** Palm trunks: x, y pairs (px), each a post of radius [POST_R]. */
    val posts: FloatArray
    /** Coral heads ([CORAL_R]) and bumper buoys ([BUOY_R]): x, y pairs (px). */
    val corals: FloatArray
    val buoys: FloatArray
    /** Barrels' starting places: x, y pairs (px). */
    val barrels: FloatArray
    /** For each tile, which plank (a joined run of cracked planks) it belongs to, or -1. */
    val plankOf: IntArray
    val plankCount: Int
    /** Whether the tide covers anything here. */
    val tidal: Boolean
    val pxW get() = w * TILE
    val pxH get() = h * TILE

    /** The drawn coastline, per pixel; physics and rendering share it. */
    val coast: Coast by lazy { Coast(this) }

    /** The coastline at high tide (tidal sand under water). */
    val floodedCoast: Coast by lazy { if (tidal) Coast(this, flooded = true) else coast }

    fun coastFor(high: Boolean): Coast = if (high) floodedCoast else coast

    /** Logs, diagonal wall joins and boulders as round-ended segments; physics and rendering share them. */
    val rails: Rails by lazy { Rails(this) }

    init {
        require(rows.all { it.length == w }) {
            "$name: rows must all be $w wide: " + rows.withIndex().filter { it.value.length != w }.map { it.index }
        }
        var tee: Pair<Int, Int>? = null
        var cup: Pair<Int, Int>? = null
        val palms = ArrayList<Float>()
        val coralList = ArrayList<Float>()
        val buoyList = ArrayList<Float>()
        val barrelList = ArrayList<Float>()
        fun centre(list: ArrayList<Float>, x: Int, y: Int) { list += (x + 0.5f) * TILE; list += (y + 0.5f) * TILE }
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
                't' -> Tile.TIDAL
                'm' -> Tile.MUD
                '=' -> Tile.DECK
                '-' -> Tile.SHALLOWS
                '8' -> Tile.CURRENT_N
                '2' -> Tile.CURRENT_S
                '6' -> Tile.CURRENT_E
                '4' -> Tile.CURRENT_W
                'H' -> Tile.HULL
                'x' -> Tile.PLANK
                'k' -> { centre(coralList, x, y); Tile.SHALLOWS }
                'o' -> { centre(buoyList, x, y); Tile.FAIRWAY }
                'D' -> { centre(barrelList, x, y); Tile.DECK }
                'b' -> { centre(barrelList, x, y); Tile.FAIRWAY }
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
        corals = coralList.toFloatArray()
        buoys = buoyList.toFloatArray()
        barrels = barrelList.toFloatArray()
        tidal = tiles.any { it == Tile.TIDAL }
        // Planks: each joined run of 'x' tiles breaks as one board.
        val group = IntArray(w * h) { -1 }
        var groups = 0
        for (i in 0 until w * h) if (tiles[i] == Tile.PLANK && group[i] < 0) {
            val q = ArrayDeque<Int>()
            group[i] = groups; q.add(i)
            while (q.isNotEmpty()) {
                val j = q.removeFirst()
                val jx = j % w
                val jy = j / w
                for ((nx, ny) in arrayOf(jx + 1 to jy, jx - 1 to jy, jx to jy + 1, jx to jy - 1)) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val k = ny * w + nx
                    if (tiles[k] == Tile.PLANK && group[k] < 0) { group[k] = groups; q.add(k) }
                }
            }
            groups++
        }
        plankOf = group
        plankCount = groups
        for (c in crabs) for ((cx, cy) in c.tiles) require(!tile(cx, cy).solid && !tile(cx, cy).water) { "$name: crab path crosses ($cx, $cy)" }
    }

    companion object {
        const val POST_R = 2.5f
        const val CORAL_R = 3.5f
        const val BUOY_R = 3.5f
        const val CRAB_R = 3f
        const val BARREL_R = 3.5f
    }

    /** The same hole with its ramps paved over (to prove par doesn't depend on them). */
    fun withoutRamps(): Hole = Hole(name, par, rows.map { r -> r.map { if (it in "NESW") '.' else it }.joinToString("") }, tideStart, crabs, lighthouse, lanterns)

    /** The same hole with the tide always in (to prove par doesn't depend on timing it). */
    fun withoutTide(): Hole = Hole(name, par, rows.map { r -> r.map { if (it == 't') '~' else it }.joinToString("") }, tideStart, crabs, lighthouse, lanterns)

    /** Whether a tile index is a plank (any state). */
    fun plankAt(tx: Int, ty: Int): Int = if (tx < 0 || ty < 0 || tx >= w || ty >= h) -1 else plankOf[ty * w + tx]

    val hasRamps: Boolean get() = tiles.any { it.isRamp }

    /** Tiles with a current on them (for drawing their drift). */
    val currentTiles: IntArray by lazy { tiles.indices.filter { tiles[it].flows }.toIntArray() }

    /** Off the map is open sea. */
    fun tile(tx: Int, ty: Int): Tile =
        if (tx < 0 || ty < 0 || tx >= w || ty >= h) Tile.WATER else tiles[ty * w + tx]

    fun at(x: Float, y: Float): Tile = tile(floor(x / TILE).toInt(), floor(y / TILE).toInt())
}

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
    /** A ledge's drop direction (one-way: you roll off it that way, it's a wall from below), or 0. */
    val dropX: Float = 0f,
    val dropY: Float = 0f,
    /** A steam vent: it throws a rolling ball into a hop. */
    val vent: Boolean = false,
    /** An exposed ridge: the hole's wind pushes the ball here. */
    val windy: Boolean = false,
    /** A kicker pad's direction: it throws the ball that way with a hop, or 0. */
    val kickX: Float = 0f,
    val kickY: Float = 0f,
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
    PLANK(solid = true, e = 0.55f),
    VENT(c = 40f, k = 1.2f, vent = true),
    /** Volcanic glass: almost no grip. */
    OBSIDIAN(c = 8f, k = 0.4f),
    /** A glowing hazard that works like water. */
    LAVA(water = true),
    ICE(c = 18f, k = 0.7f),
    /** Ice that cracks under a rolling ball and opens to water after the shot. */
    THIN_ICE(c = 18f, k = 0.7f),
    /** Soft snow: a ball rolling in stops dead. */
    SNOW(c = 500f, k = 8f),
    /** A switch plate (see Hole.plateOf): rolling over it opens its gates. */
    PLATE(c = 36f, k = 1f),
    /** A stone gate: a wall until its plate opens it (then temple floor). */
    GATE(solid = true, e = 0.6f),
    /** A tunnel's mouth (in) and where it comes out (out). */
    TUNNEL_IN(c = 36f, k = 1f),
    TUNNEL_OUT(c = 36f, k = 1f),
    /** Overgrowth: heavy rough. */
    VINES(c = 200f, k = 4f),
    LEDGE_N(c = 40f, k = 1.2f, dropY = -1f),
    LEDGE_S(c = 40f, k = 1.2f, dropY = 1f),
    LEDGE_E(c = 40f, k = 1.2f, dropX = 1f),
    LEDGE_W(c = 40f, k = 1.2f, dropX = -1f),
    /** Exposed grass on a ridge, in the wind. */
    RIDGE(c = 40f, k = 1.2f, windy = true),
    KICK_N(c = 36f, k = 1f, kickY = -1f),
    KICK_S(c = 36f, k = 1f, kickY = 1f),
    KICK_E(c = 36f, k = 1f, kickX = 1f),
    KICK_W(c = 36f, k = 1f, kickX = -1f),
    /** A boost pad: the ball leaves it fast, the way it was going. */
    BOOST(c = 30f, k = 1f),
    /** A portal: in one, out of its twin, still rolling the same way. */
    PORTAL(c = 36f, k = 1f),
    /** Quicksand: it drags and pulls to its middle, and swallows a ball that stops in it. */
    QUICKSAND(c = 160f, k = 3f),
    /** Pulse walls: set A is up on even shots, set B on odd ones (each is floor when down). */
    PULSE_A(solid = true, e = 0.6f),
    PULSE_B(solid = true, e = 0.6f),
    /** A gravity well: it pulls the ball towards its middle from a few tiles away. */
    WELL(c = 40f, k = 1.2f);

    val isGreen get() = this == GREEN || slopeX != 0f || slopeY != 0f
    val isRamp get() = rampX != 0f || rampY != 0f
    val isLand get() = !water
    val flows get() = flowX != 0f || flowY != 0f
    val isLedge get() = dropX != 0f || dropY != 0f
    val isKicker get() = kickX != 0f || kickY != 0f
    val isPulse get() = this == PULSE_A || this == PULSE_B
    /** Made of wood (for sounds): driftwood, hull and planks. */
    val wooden get() = this == WOOD || this == HULL || this == PLANK
}

/** The tide: it moves one step per shot, low for [PERIOD]/2 shots, then high. */
object Tide {
    const val PERIOD = 6
    fun phase(p: Int) = Math.floorMod(p, PERIOD)
    fun high(p: Int) = phase(p) >= PERIOD / 2
}

/** A windmill: blades of [length] px turning about a hub tile at [omega] rad/s, from [phase] at the start of every shot. */
class Windmill(val tx: Int, val ty: Int, val length: Float = 13f, val omega: Float = 1.6f, val phase: Float = 0.4f, val blades: Int = 4) {
    val hubX get() = (tx + 0.5f) * TILE
    val hubY get() = (ty + 0.5f) * TILE
    fun angle(t: Float) = phase + omega * t
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
 *
 * And: `V` a steam vent, `g` obsidian, `L` lava, `i` ice, `j` thin ice, `n` snowbank,
 * `p` / `q` switch plates for gates `G` / `J` (`u` a plate that opens nothing), `(` and `[`
 * tunnel mouths coming out at `)` and `]`, `r` vines, `A U F Z` ledges dropping north, south,
 * east or west, and `;` a windswept ridge.
 *
 * And: `Y y X K` kicker pads throwing north, south, east or west, `B` a boost pad, `1` and `3`
 * portal pairs, `Q` quicksand, `M` / `R` pulse walls (up on even / odd shots), `@` a gravity well.
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
    val windmills: List<Windmill> = emptyList(),
    /** The wind's direction in degrees (0 east, 90 south) on ridge tiles, or null. */
    val windDeg: Float? = null,
) {
    /** How the course paints its shores and grass: "" (sand), "ember" (black sand), "frost" (snow). Set by the course. */
    var style: String = ""
    /** Currents run as conveyor belts on these courses. */
    val belts: Boolean get() = style == "carnival" || style == "clock"
    val windX: Float = windDeg?.let { kotlin.math.cos(Math.toRadians(it.toDouble())).toFloat() } ?: 0f
    val windY: Float = windDeg?.let { kotlin.math.sin(Math.toRadians(it.toDouble())).toFloat() } ?: 0f
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
    /** For each tile: its index among the thin-ice tiles, or -1. */
    val thinOf: IntArray
    val thinCount: Int
    /** For each tile: a plate's gate group (0 opens 'G', 1 opens 'J', -1 a decoy), a gate's group, or -2. */
    val plateOf: IntArray
    /** Tunnels: for each tile, which tunnel's mouth it is (or -1); where each comes out, and which way. */
    val tunnelOf: IntArray
    val exitX = FloatArray(2)
    val exitY = FloatArray(2)
    val exitDX = FloatArray(2)
    val exitDY = FloatArray(2)
    /** Portals: for each tile, which pair it belongs to (or -1), and each pair's two ends (px). */
    val portalOf: IntArray
    val portalX = Array(2) { FloatArray(2) }
    val portalY = Array(2) { FloatArray(2) }
    /** Gravity wells' centres: x, y pairs (px). */
    val wells: FloatArray
    /** Quicksand: for each tile, the middle of its patch (px), or NaN. */
    val quickX: FloatArray
    val quickY: FloatArray
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
        val plates = IntArray(rows.first().length * rows.size) { -2 }
        val mouths = IntArray(rows.first().length * rows.size) { -1 }
        val outs = arrayOfNulls<Pair<Int, Int>>(2)
        val rowW = rows.first().length
        val portalTiles = IntArray(rows.first().length * rows.size) { -1 }
        val wellList = ArrayList<Float>()
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
                'V' -> Tile.VENT
                'g' -> Tile.OBSIDIAN
                'L' -> Tile.LAVA
                'i' -> Tile.ICE
                'j' -> Tile.THIN_ICE
                'n' -> Tile.SNOW
                'p' -> { plates[y * rowW + x] = 0; Tile.PLATE }
                'q' -> { plates[y * rowW + x] = 1; Tile.PLATE }
                'u' -> { plates[y * rowW + x] = -1; Tile.PLATE }
                'G' -> { plates[y * rowW + x] = 0; Tile.GATE }
                'J' -> { plates[y * rowW + x] = 1; Tile.GATE }
                '(' -> { mouths[y * rowW + x] = 0; Tile.TUNNEL_IN }
                '[' -> { mouths[y * rowW + x] = 1; Tile.TUNNEL_IN }
                ')' -> { outs[0] = x to y; Tile.TUNNEL_OUT }
                ']' -> { outs[1] = x to y; Tile.TUNNEL_OUT }
                'r' -> Tile.VINES
                'A' -> Tile.LEDGE_N
                'U' -> Tile.LEDGE_S
                'F' -> Tile.LEDGE_E
                'Z' -> Tile.LEDGE_W
                ';' -> Tile.RIDGE
                'Y' -> Tile.KICK_N
                'y' -> Tile.KICK_S
                'X' -> Tile.KICK_E
                'K' -> Tile.KICK_W
                'B' -> Tile.BOOST
                '1' -> { portalTiles[y * rowW + x] = 0; Tile.PORTAL }
                '3' -> { portalTiles[y * rowW + x] = 1; Tile.PORTAL }
                'Q' -> Tile.QUICKSAND
                'M' -> Tile.PULSE_A
                'R' -> Tile.PULSE_B
                '@' -> { centre(wellList, x, y); Tile.WELL }
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
        var thin = 0
        thinOf = IntArray(w * h) { if (tiles[it] == Tile.THIN_ICE) thin++ else -1 }
        thinCount = thin
        plateOf = plates
        tunnelOf = mouths
        portalOf = portalTiles
        for (k in 0..1) {
            val ends = portalTiles.indices.filter { portalTiles[it] == k }
            require(ends.isEmpty() || ends.size == 2) { "$name: portal pair $k needs exactly two ends" }
            for ((e, idx) in ends.withIndex()) { portalX[k][e] = (idx % w + 0.5f) * TILE; portalY[k][e] = (idx / w + 0.5f) * TILE }
        }
        wells = wellList.toFloatArray()
        // Each quicksand patch pulls towards its own middle.
        quickX = FloatArray(w * h) { Float.NaN }
        quickY = FloatArray(w * h) { Float.NaN }
        val seen = BooleanArray(w * h)
        for (i0 in 0 until w * h) if (tiles[i0] == Tile.QUICKSAND && !seen[i0]) {
            val patch = ArrayList<Int>()
            val q = ArrayDeque<Int>()
            seen[i0] = true; q.add(i0)
            while (q.isNotEmpty()) {
                val j = q.removeFirst(); patch += j
                val jx = j % w
                val jy = j / w
                for ((nx, ny) in arrayOf(jx + 1 to jy, jx - 1 to jy, jx to jy + 1, jx to jy - 1)) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val k = ny * w + nx
                    if (tiles[k] == Tile.QUICKSAND && !seen[k]) { seen[k] = true; q.add(k) }
                }
            }
            val mx = patch.map { (it % w + 0.5f) * TILE }.average().toFloat()
            val my = patch.map { (it / w + 0.5f) * TILE }.average().toFloat()
            for (j in patch) { quickX[j] = mx; quickY[j] = my }
        }
        for (k in 0..1) {
            val o = outs[k]
            val hasMouth = mouths.any { it == k }
            require((o == null) == !hasMouth) { "$name: tunnel $k needs one mouth and one way out" }
            if (o == null) continue
            val (ox, oy) = o
            // It comes out of a wall: leave facing away from it.
            val dirs = arrayOf(0 to -1, 0 to 1, 1 to 0, -1 to 0)
            val d = dirs.firstOrNull { (dx, dy) -> tile(ox - dx, oy - dy).solid && !tile(ox + dx, oy + dy).solid }
                ?: error("$name: tunnel $k's way out must have a wall behind it")
            exitX[k] = (ox + 0.5f) * TILE; exitY[k] = (oy + 0.5f) * TILE
            exitDX[k] = d.first.toFloat(); exitDY[k] = d.second.toFloat()
        }
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
    fun withoutRamps(): Hole = Hole(name, par, rows.map { r -> r.map { if (it in "NESW") '.' else it }.joinToString("") }, tideStart, crabs, lighthouse, lanterns, windmills, windDeg).also { it.style = style }

    /** The same hole with the tide always in (to prove par doesn't depend on timing it). */
    fun withoutTide(): Hole = Hole(name, par, rows.map { r -> r.map { if (it == 't') '~' else it }.joinToString("") }, tideStart, crabs, lighthouse, lanterns, windmills, windDeg).also { it.style = style }

    /** Whether a tile index is a plank (any state). */
    fun plankAt(tx: Int, ty: Int): Int = if (tx < 0 || ty < 0 || tx >= w || ty >= h) -1 else plankOf[ty * w + tx]

    val hasRamps: Boolean get() = tiles.any { it.isRamp }

    /** Indices of the tiles of kinds [kinds], for drawing what lives on them. */
    private val byKind = HashMap<Tile, IntArray>()
    fun tilesOf(t: Tile): IntArray = byKind.getOrPut(t) { tiles.indices.filter { tiles[it] == t }.toIntArray() }

    /** Tiles with a current on them (for drawing their drift). */
    val currentTiles: IntArray by lazy { tiles.indices.filter { tiles[it].flows }.toIntArray() }

    /** Off the map is open sea. */
    fun thinAt(tx: Int, ty: Int): Int = if (tx < 0 || ty < 0 || tx >= w || ty >= h) -1 else thinOf[ty * w + tx]
    fun plateAt(tx: Int, ty: Int): Int = if (tx < 0 || ty < 0 || tx >= w || ty >= h) -2 else plateOf[ty * w + tx]

    fun tile(tx: Int, ty: Int): Tile =
        if (tx < 0 || ty < 0 || tx >= w || ty >= h) Tile.WATER else tiles[ty * w + tx]

    fun at(x: Float, y: Float): Tile = tile(floor(x / TILE).toInt(), floor(y / TILE).toInt())
}

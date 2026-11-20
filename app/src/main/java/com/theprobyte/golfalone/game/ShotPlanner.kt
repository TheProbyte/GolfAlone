package com.theprobyte.golfalone.game

import kotlin.math.PI
import kotlin.math.floor

/**
 * Finds a way round a hole with the real physics: a beam search over a grid of shot
 * angles and powers, ranked by walking distance to the cup over land tiles.
 *
 * It plays the hole's state too (the tide, crabs, planks and barrels move exactly as in the
 * game), and counts a splash's penalty stroke, so a plan's [Plan.strokes] is your score.
 */
class ShotPlanner(private val hole: Hole, private val angles: Int = 96, private val powers: Int = 18, private val beam: Int = 6) {

    data class Shot(val angle: Float, val power: Float)
    data class Plan(val shots: List<Shot>, val strokes: Int = shots.size)

    private class Node(val x: Float, val y: Float, val shots: List<Shot>, val score: Float, val state: HoleState, val strokes: Int)

    private val vents: IntArray = hole.tiles.indices.filter { hole.tiles[it].vent }.toIntArray()

    /** Walking distance (in tiles) from each tile to the cup, over non-water, non-wall tiles. */
    val distance: IntArray = walk(flooded = false)

    /** The same at high tide (tidal sand is water). */
    private val floodedDistance: IntArray = if (hole.tidal) walk(flooded = true) else distance

    /** Gates: the walking map with only the gate groups in a mask open, and each group's plates. */
    private val gateMaps = HashMap<Int, IntArray>()
    private fun gateMap(mask: Int): IntArray = gateMaps.getOrPut(mask) { walk(false, mask) }
    private val plates: Array<IntArray> = Array(2) { g -> hole.tiles.indices.filter { hole.tiles[it] == Tile.PLATE && hole.plateOf[it] == g }.toIntArray() }
    private val hasGates = hole.tiles.any { it == Tile.GATE }
    private val toPlate = HashMap<Int, IntArray>()
    /** Walking distance to the nearest plate of group [g], with the gates in [mask] open. */
    private fun toPlate(g: Int, mask: Int): IntArray = toPlate.getOrPut(g * 8 + mask) { walk(false, mask, plates[g]) }

    /** Best guess at the walk to the cup from tile [i] with gates [mask] open: straight there, or via a plate first. */
    private fun gateEstimate(i: Int, mask: Int, depth: Int = 0): Int {
        var best = gateMap(mask)[i]
        if (depth < 2) for (g in 0..1) {
            if (mask and (1 shl g) != 0 || plates[g].isEmpty()) continue
            val reach = toPlate(g, mask)[i]
            if (reach == Int.MAX_VALUE) continue
            var after = Int.MAX_VALUE
            for (p in plates[g]) after = minOf(after, gateEstimate(p, mask or (1 shl g), depth + 1))
            if (after != Int.MAX_VALUE) best = minOf(best, reach + after)
        }
        return best
    }

    /** BFS distances to the cup (or to the [sources] given), over walkable tiles; [gates] says which gate groups are open. */
    private fun walk(flooded: Boolean, gates: Int = 3, sources: IntArray? = null): IntArray = IntArray(hole.w * hole.h) { Int.MAX_VALUE }.also { d ->
        val cx = floor(hole.cupX / TILE).toInt()
        val cy = floor(hole.cupY / TILE).toInt()
        val q = ArrayDeque<Int>()
        if (sources == null) { d[cy * hole.w + cx] = 0; q.add(cy * hole.w + cx) }
        else for (s0 in sources) { d[s0] = 0; q.add(s0) }
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val x = i % hole.w
            val y = i / hole.w
            val here = hole.tile(x, y)
            for ((nx, ny) in arrayOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                if (nx !in 0 until hole.w || ny !in 0 until hole.h) continue
                val t = hole.tile(nx, ny)
                val j = ny * hole.w + nx
                // Cracked planks can be smashed and gates opened, so they're ways through.
                if ((t.solid && t != Tile.PLANK && t != Tile.GATE) || t.water || (flooded && t == Tile.TIDAL) || d[j] != Int.MAX_VALUE) continue
                if (t == Tile.GATE && (gates shr hole.plateOf[j]) and 1 == 0) continue
                // Ledges are one way: no climbing onto one from below, or off one uphill.
                if (here.isLedge && nx == x + here.dropX.toInt() && ny == y + here.dropY.toInt()) continue
                if (t.isLedge && x == nx - t.dropX.toInt() && y == ny - t.dropY.toInt()) continue
                d[j] = d[i] + 1
                q.add(j)
            }
            // A vent hops the ball over anything within its reach: it's one step from there.
            for (v in vents) if (d[v] == Int.MAX_VALUE) {
                val vx = v % hole.w - x
                val vy = v / hole.w - y
                if (vx * vx + vy * vy <= VENT_REACH * VENT_REACH) { d[v] = d[i] + 1; q.add(v) }
            }
            // A tunnel's way out is one step from its mouth.
            if (here == Tile.TUNNEL_OUT) for (m in hole.tunnelOf.indices) {
                if (hole.tunnelOf[m] < 0 || d[m] != Int.MAX_VALUE) continue
                val k = hole.tunnelOf[m]
                if (floor(hole.exitX[k] / TILE).toInt() == x && floor(hole.exitY[k] / TILE).toInt() == y) { d[m] = d[i] + 1; q.add(m) }
            }
        }
    }

    /** How far a ball resting at ([x], [y]) is from the cup, for a next shot played at [high] tide. */
    private fun score(x: Float, y: Float, high: Boolean = false, gates: Int = 3): Float {
        val tx = floor(x / TILE).toInt()
        val ty = floor(y / TILE).toInt()
        if (tx !in 0 until hole.w || ty !in 0 until hole.h) return Float.MAX_VALUE
        if (hasGates) {
            val g = gateEstimate(ty * hole.w + tx, gates)
            return if (g == Int.MAX_VALUE) Float.MAX_VALUE else g.toFloat() + if (hole.tile(tx, ty) == Tile.ROUGH) 1.5f else 0f
        }
        val map = if (high) floodedDistance else distance
        var d = map[ty * hole.w + tx]
        // A ball on the drawn edge of a tile the map can't walk (the lip of flooded sand, say):
        // score it from the best neighbouring tile it can.
        if (d == Int.MAX_VALUE) for (dy in -1..1) for (dx in -1..1) {
            val nx = tx + dx
            val ny = ty + dy
            if (nx !in 0 until hole.w || ny !in 0 until hole.h) continue
            val v = map[ny * hole.w + nx]
            if (v != Int.MAX_VALUE && v + 1 < d) d = v + 1
        }
        if (d == Int.MAX_VALUE) return Float.MAX_VALUE
        val t = hole.tile(tx, ty)
        return d + if (t == Tile.SAND) 4f else if (t == Tile.ROUGH) 1.5f else 0f
    }

    /** The shortest plan from the tee found within [maxStrokes], or null. */
    fun solve(maxStrokes: Int): Plan? = solveFrom(hole.teeX, hole.teeY, maxStrokes)

    private class Splashes : PhysicsEvents {
        var n = 0
        override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) { n++ }
    }

    /** The shortest plan from a ball at rest at ([x], [y]), with the hole in [state] (fresh if null). */
    fun solveFrom(x: Float, y: Float, maxStrokes: Int, state: HoleState? = null): Plan? {
        val start = HoleState(hole).also { if (state != null) it.copyFrom(state) }
        val simState = HoleState(hole)
        val sim = Physics(hole, simState)
        val splashes = Splashes()
        var frontier = listOf(Node(x, y, emptyList(), score(x, y), start, 0))
        var found: Plan? = null
        repeat(maxStrokes) {
            val next = HashMap<Long, Node>()
            for (n in frontier) for (a in 0 until angles) for (p in 1..powers) {
                val shot = Shot((2 * PI * a / angles).toFloat(), p / powers.toFloat())
                simState.copyFrom(n.state)
                sim.placeAt(n.x, n.y)
                sim.shootAngle(shot.angle, shot.power)
                splashes.n = 0
                sim.settle(splashes)
                val strokes = n.strokes + 1 + splashes.n
                if (strokes > maxStrokes) continue
                val shots = n.shots + shot
                if (sim.inCup) {
                    if (splashes.n == 0) return Plan(shots, strokes)
                    if (found == null || strokes < found!!.strokes) found = Plan(shots, strokes)
                    continue
                }
                sim.endShot()
                val s = score(sim.x, sim.y, simState.high, (if (simState.gates[0]) 1 else 0) or (if (simState.gates[1]) 2 else 0))
                if (s == Float.MAX_VALUE) continue
                // One survivor per 4 px cell and hole state keeps the beam diverse.
                val key = ((floor(sim.x / 4).toLong() shl 32) or (floor(sim.y / 4).toLong() and 0xffffffffL)) xor (simState.key() * 0x9E3779B97F4A7C15uL.toLong())
                val old = next[key]
                if (old == null || s < old.score) next[key] = Node(sim.x, sim.y, shots, s, HoleState(hole).also { it.copyFrom(simState) }, strokes)
            }
            found?.let { return it }
            frontier = next.values.sortedBy { it.score }.take(beam)
            if (frontier.isEmpty()) return null
        }
        return found
    }

    private companion object {
        /** How far (tiles) a vent's hop can carry the ball, for the planner's map. */
        const val VENT_REACH = 8
    }
}

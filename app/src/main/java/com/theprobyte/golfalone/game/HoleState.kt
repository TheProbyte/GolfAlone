package com.theprobyte.golfalone.game

import kotlin.math.hypot

/**
 * Everything on a hole that changes as you play: shots taken (the tide follows them),
 * smashed planks, where the barrels lie, and how far each crab has walked.
 *
 * It changes in two ways only: during a shot (planks break, barrels roll) and once after
 * each shot comes to rest ([advance]: the tide and the crabs move on). Nothing is random,
 * so the same shots from the same state always play out the same.
 */
class HoleState(val hole: Hole) {
    var shots = 0
    val broken = BooleanArray(hole.plankCount)
    val bx = FloatArray(hole.barrels.size / 2)
    val by = FloatArray(hole.barrels.size / 2)
    val bvx = FloatArray(hole.barrels.size / 2)
    val bvy = FloatArray(hole.barrels.size / 2)
    val sunk = BooleanArray(hole.barrels.size / 2)
    val crabStep = IntArray(hole.crabs.size)
    /** Thin ice: cracked this shot (it opens after it), and open water. */
    val cracked = BooleanArray(hole.thinCount)
    val thinOpen = BooleanArray(hole.thinCount)
    /** Gate groups 'G' and 'J': open once their plate is pressed. */
    val gates = BooleanArray(2)

    init { reset() }

    fun reset() {
        shots = 0
        broken.fill(false)
        for (i in bx.indices) { bx[i] = hole.barrels[2 * i]; by[i] = hole.barrels[2 * i + 1] }
        bvx.fill(0f); bvy.fill(0f); sunk.fill(false)
        crabStep.fill(0)
        cracked.fill(false); thinOpen.fill(false); gates.fill(false)
    }

    fun copyFrom(o: HoleState) {
        shots = o.shots
        o.broken.copyInto(broken)
        o.bx.copyInto(bx); o.by.copyInto(by); o.bvx.copyInto(bvx); o.bvy.copyInto(bvy); o.sunk.copyInto(sunk)
        o.crabStep.copyInto(crabStep)
        o.cracked.copyInto(cracked); o.thinOpen.copyInto(thinOpen); o.gates.copyInto(gates)
    }

    /** Whether the thin ice at this tile has opened to water. */
    fun thinOpenAt(tx: Int, ty: Int): Boolean { val k = hole.thinAt(tx, ty); return k >= 0 && thinOpen[k] }
    fun thinOpenAt(x: Float, y: Float): Boolean = thinOpenAt(kotlin.math.floor(x / TILE).toInt(), kotlin.math.floor(y / TILE).toInt())

    /** Whether the gate at this tile is open. */
    fun gateOpen(tx: Int, ty: Int): Boolean { val g = hole.plateAt(tx, ty); return g >= 0 && gates[g] }

    /** High tide now (during the next shot), and after it. */
    val high: Boolean get() = hole.tidal && Tide.high(hole.tideStart + shots)
    val highAfter: Boolean get() = hole.tidal && Tide.high(hole.tideStart + shots + 1)
    val tidePhase: Int get() = Tide.phase(hole.tideStart + shots)

    val coast: Coast get() = hole.coastFor(high)

    fun plankBroken(tx: Int, ty: Int): Boolean { val g = hole.plankAt(tx, ty); return g >= 0 && broken[g] }

    val barrelsMoving: Boolean get() {
        for (i in bx.indices) if (!sunk[i] && (bvx[i] != 0f || bvy[i] != 0f)) return true
        return false
    }

    fun crabX(i: Int) = (hole.crabs[i].tileAt(crabStep[i]).first + 0.5f) * TILE
    fun crabY(i: Int) = (hole.crabs[i].tileAt(crabStep[i]).second + 0.5f) * TILE
    fun crabNextX(i: Int) = (hole.crabs[i].tileAt(crabStep[i] + 1).first + 0.5f) * TILE
    fun crabNextY(i: Int) = (hole.crabs[i].tileAt(crabStep[i] + 1).second + 0.5f) * TILE

    /**
     * A shot has come to rest with the ball at ([ballX], [ballY]): the tide moves on and each
     * crab takes a step, unless the ball or a barrel is in its way (then it waits).
     */
    fun advance(ballX: Float, ballY: Float) {
        shots++
        // Ice the ball cracked on this shot gives way now.
        for (k in cracked.indices) if (cracked[k]) thinOpen[k] = true
        for (i in crabStep.indices) {
            val nx = crabNextX(i)
            val ny = crabNextY(i)
            var free = hypot(nx - ballX, ny - ballY) > Hole.CRAB_R + Physics.BALL_R + 1f
            for (b in bx.indices) if (!sunk[b] && hypot(nx - bx[b], ny - by[b]) < Hole.CRAB_R + Hole.BARREL_R + 1f) free = false
            if (free) crabStep[i]++
        }
    }

    /** A compact fingerprint (the planner keeps one line of play per state). */
    fun key(): Long {
        var h = Tide.phase(shots).toLong()
        for (b in broken) h = h * 31 + (if (b) 1 else 0)
        for (i in bx.indices) h = h * 131 + if (sunk[i]) 7 else ((bx[i] / 4).toInt() * 977 + (by[i] / 4).toInt())
        for (c in hole.crabs.indices) h = h * 37 + Math.floorMod(crabStep[c], hole.crabs[c].cycle)
        for (k in cracked.indices) h = h * 3 + (if (thinOpen[k]) 2 else if (cracked[k]) 1 else 0)
        for (g in gates) h = h * 5 + (if (g) 1 else 0)
        return h
    }

    /** For saving a hole in progress. */
    fun encode(): String = buildString {
        append(shots).append(';')
        append(broken.joinToString("") { if (it) "1" else "0" }).append(';')
        append(bx.indices.joinToString(",") { if (sunk[it]) "s" else "${bx[it]}/${by[it]}" }).append(';')
        append(crabStep.joinToString(",")).append(';')
        append(cracked.joinToString("") { if (it) "1" else "0" }).append(';')
        append(thinOpen.joinToString("") { if (it) "1" else "0" }).append(';')
        append(gates.joinToString("") { if (it) "1" else "0" })
    }

    fun decode(s: String) {
        reset()
        val p = s.split(';')
        if (p.size < 4) return
        shots = p[0].toIntOrNull() ?: 0
        for (i in broken.indices) broken[i] = p[1].getOrNull(i) == '1'
        val bs = p[2].split(',')
        for (i in bx.indices) {
            val v = bs.getOrNull(i) ?: continue
            if (v == "s") sunk[i] = true
            else v.split('/').let { xy -> if (xy.size == 2) { bx[i] = xy[0].toFloatOrNull() ?: bx[i]; by[i] = xy[1].toFloatOrNull() ?: by[i] } }
        }
        val cs = p[3].split(',')
        for (i in crabStep.indices) crabStep[i] = cs.getOrNull(i)?.toIntOrNull() ?: 0
        if (p.size >= 7) {
            for (i in cracked.indices) cracked[i] = p[4].getOrNull(i) == '1'
            for (i in thinOpen.indices) thinOpen[i] = p[5].getOrNull(i) == '1'
            for (i in gates.indices) gates[i] = p[6].getOrNull(i) == '1'
        }
    }
}

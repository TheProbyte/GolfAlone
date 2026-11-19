package com.theprobyte.golfalone.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.HoleState
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The things on a hole that aren't baked into its map: bumper buoys, coral heads, hermit
 * crabs (with footprints where they'll step next), barrels, cracked planks, and the night's
 * lighthouse and lanterns. All drawn as pixels so they take the time of day's colours.
 */
object Bodies {
    private val CRAB = 0xFFE0603C.toInt()
    private val CRAB_DARK = 0xFFA8382E.toInt()
    private val CORAL = 0xFFE87A8A.toInt()
    private val CORAL_LIGHT = 0xFFF5B0B8.toInt()
    private val CORAL_DARK = 0xFFA84858.toInt()
    private val LAMP = 0xFFFFE6A0.toInt()
    private val shadow get() = Palette.alpha(Palette.INK_SHADOW, 90)

    /** Shadows and ground-level bodies, drawn under the ball. */
    fun draw(
        c: Canvas, hole: Hole, st: HoleState, ix: Int, iy: Int, time: Float,
        crabFromX: FloatArray, crabFromY: FloatArray, crabK: Float, showSteps: Boolean,
        bumpX: Float, bumpY: Float, bumpT: Float,
    ) {
        // Currents: streaks of foam drifting the way the water runs.
        for (i in hole.currentTiles) {
            val t = hole.tiles[i]
            val tx = i % hole.w
            val ty = i / hole.w
            for (s in 0 until 2) {
                val h = Terrain.hash(tx * 2 + s, ty, 73)
                val along = ((time * 14f + h * TILE * 3) % TILE).toInt()
                val across = 1 + ((Terrain.hash(tx, ty * 2 + s, 74) * 6).toInt())
                val ox = if (t.flowX != 0f) (if (t.flowX > 0) along else TILE - 1 - along) else across
                val oy = if (t.flowY != 0f) (if (t.flowY > 0) along else TILE - 1 - along) else across
                val x = tx * TILE + ox - ix
                val y = ty * TILE + oy - iy
                val col = Palette.alpha(Palette.SEA[5], 210)
                px(c, x, y, col)
                px(c, x - t.flowX.toInt(), y - t.flowY.toInt(), Palette.alpha(Palette.SEA[5], 120))
            }
        }

        // Cracked planks still standing: a board wall with a split down it.
        for (ty in 0 until hole.h) for (tx in 0 until hole.w) {
            val g = hole.plankAt(tx, ty)
            if (g < 0) continue
            val x = tx * TILE - ix
            val y = ty * TILE - iy
            if (st.broken[g]) splinters(c, x, y, tx, ty) else plank(c, hole, x, y, tx, ty)
        }
        var k = 0
        while (k < hole.corals.size) { coral(c, hole.corals[k].roundToInt() - ix, hole.corals[k + 1].roundToInt() - iy, k); k += 2 }
        k = 0
        while (k < hole.buoys.size) {
            val bx = hole.buoys[k]
            val by = hole.buoys[k + 1]
            val hit = bumpT in 0f..0.15f && kotlin.math.hypot(bx - bumpX, by - bumpY) < 9f
            buoy(c, bx.roundToInt() - ix, by.roundToInt() - iy, hit)
            k += 2
        }
        for (i in hole.crabs.indices) {
            if (showSteps && (st.crabNextX(i) != st.crabX(i) || st.crabNextY(i) != st.crabY(i))) {
                footprints(c, st.crabNextX(i).roundToInt() - ix, st.crabNextY(i).roundToInt() - iy)
            }
            val cx = crabFromX[i] + (st.crabX(i) - crabFromX[i]) * crabK
            val cy = crabFromY[i] + (st.crabY(i) - crabFromY[i]) * crabK
            val walking = crabK < 1f
            crab(c, cx.roundToInt() - ix, cy.roundToInt() - iy, walking && ((time * 12f).toInt() % 2 == 0), (st.crabX(i) - crabFromX[i]) < 0f)
        }
        for (b in st.bx.indices) if (!st.sunk[b]) barrel(c, st.bx[b].roundToInt() - ix, st.by[b].roundToInt() - iy, st.bvx[b] + st.bvy[b], time)
    }

    private fun plank(c: Canvas, hole: Hole, x: Int, y: Int, tx: Int, ty: Int) {
        val across = hole.plankAt(tx - 1, ty) >= 0 || hole.plankAt(tx + 1, ty) >= 0
        // 3/4 view: a pale top, a darker face, a jagged crack.
        rect(c, x + 1, y + 6, TILE, 2, shadow)
        rect(c, x, y, TILE, 5, Palette.WOODS[3])
        rect(c, x, y + 5, TILE, 3, Palette.WOODS[1])
        rect(c, x, y, TILE, 1, Palette.WOODS[4])
        if (across) { for (u in 0 until TILE step 3) rect(c, x + u, y + 1, 1, 4, Palette.WOODS[2]) }
        val crackX = x + 3 + ((tx * 7 + ty * 3) % 3)
        px(c, crackX, y + 1, Palette.WOODS[0]); px(c, crackX + 1, y + 2, Palette.WOODS[0]); px(c, crackX, y + 3, Palette.WOODS[0])
        px(c, crackX - 1, y + 4, Palette.WOODS[0]); px(c, crackX, y + 5, Palette.WOODS[0]); px(c, crackX + 1, y + 6, Palette.WOODS[0])
    }

    private fun splinters(c: Canvas, x: Int, y: Int, tx: Int, ty: Int) {
        for (s in 0 until 4) {
            val h = Terrain.hash(tx * 4 + s, ty, 71)
            val sx = x + (h * 7).toInt()
            val sy = y + (Terrain.hash(tx, ty * 4 + s, 72) * 7).toInt()
            px(c, sx, sy, Palette.WOODS[2]); px(c, sx + 1, sy, Palette.WOODS[3])
        }
    }

    private fun coral(c: Canvas, x: Int, y: Int, seed: Int) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 3.5f, 1.5f, shadow)
        val lobes = arrayOf(intArrayOf(-2, 0), intArrayOf(1, -1), intArrayOf(0, 2), intArrayOf(2, 1))
        for ((n, l) in lobes.withIndex()) {
            val r = if (n == 0) 2 else 1
            rect(c, x + l[0] - r, y + l[1] - r, 2 * r + 1, 2 * r + 1, if ((n + seed) % 2 == 0) CORAL else CORAL_DARK)
            px(c, x + l[0], y + l[1] - r, CORAL_LIGHT)
        }
        px(c, x, y - 2, CORAL_LIGHT); px(c, x - 1, y + 1, CORAL_LIGHT)
    }

    private fun buoy(c: Canvas, x: Int, y: Int, hit: Boolean) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 4f, 2f, shadow)
        val r2 = if (hit) 14 else 11
        for (dy in -4..4) for (dx in -4..4) {
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            val band = dy in -1..0
            var col = if (band) Palette.PAPER[3] else Palette.FLAG
            if (dx >= 2 || dy >= 3) col = if (band) Palette.PAPER[1] else Palette.FLAG_DARK
            if (d2 >= r2 - 3) col = Palette.FLAG_DARK
            px(c, x + dx, y + dy, col)
        }
        px(c, x - 1, y - 2, Palette.PAPER[3]); px(c, x - 2, y - 1, Palette.PAPER[3])
        px(c, x, y - 4, Palette.STONES[3]) // the bell's ring
    }

    private fun footprints(c: Canvas, x: Int, y: Int) {
        val col = Palette.alpha(Palette.INK_SHADOW, 110)
        px(c, x - 2, y - 1, col); px(c, x - 2, y + 1, col); px(c, x + 2, y - 1, col); px(c, x + 2, y + 1, col)
        px(c, x - 1, y, col); px(c, x + 1, y, col)
    }

    /** A hermit crab in a pale shell; legs scuttle while it walks. */
    private fun crab(c: Canvas, x: Int, y: Int, step: Boolean, left: Boolean) {
        Pix.oval(c, x + 1f, y + 2.5f, 3.5f, 1.5f, shadow)
        // Legs and claws.
        val ly = if (step) 1 else 0
        px(c, x - 3, y + ly, CRAB_DARK); px(c, x + 3, y + 1 - ly, CRAB_DARK)
        px(c, x - 3, y + 2 - ly, CRAB_DARK); px(c, x + 3, y + 2 + ly - 1, CRAB_DARK)
        val fx = if (left) x - 3 else x + 3
        rect(c, fx - 1, y - 2, 2, 2, CRAB)
        // The shell, with its spiral.
        rect(c, x - 2, y - 2, 5, 4, Palette.SHELL_PINK)
        rect(c, x - 1, y - 3, 3, 1, Palette.PAPER[3])
        px(c, x, y - 1, Palette.SANDS[1]); px(c, x + 1, y, Palette.SANDS[1]); px(c, x - 1, y, Palette.PAPER[3])
        rect(c, x - 2, y + 1, 5, 1, Palette.SANDS[1])
        // Eyes.
        px(c, fx, y - 3, Palette.INK_SHADOW)
    }

    private fun barrel(c: Canvas, x: Int, y: Int, spin: Float, time: Float) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 4f, 2f, shadow)
        for (dy in -3..3) for (dx in -3..3) {
            val d2 = dx * dx + dy * dy
            if (d2 > 11) continue
            var col = Palette.WOODS[3]
            if (d2 in 5..7) col = Palette.WOODS[1] // the hoop
            if (d2 >= 10) col = Palette.WOODS[0]
            if (dx + dy <= -3 && d2 < 5) col = Palette.WOODS[4]
            px(c, x + dx, y + dy, col)
        }
        // A stave line that turns while it rolls.
        val a = if (spin != 0f) time * 8f else 0.6f
        px(c, x + (cos(a) * 1.5f).roundToInt(), y + (sin(a) * 1.5f).roundToInt(), Palette.WOODS[4])
    }

    // ------------------------------------------------------------ night scenery

    private val beamPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val beam = Path()

    /** The lighthouse tower on its tile, and lanterns on posts with pools of warm light. */
    fun scenery(c: Canvas, hole: Hole, ix: Int, iy: Int, time: Float, still: Boolean) {
        for ((tx, ty) in hole.lanterns) {
            val x = ((tx + 0.5f) * TILE).roundToInt() - ix
            val y = ((ty + 0.5f) * TILE).roundToInt() - iy
            Pix.oval(c, x + 0.5f, y + 3f, 13f, 8f, Palette.alpha(LAMP, 26))
            Pix.oval(c, x + 0.5f, y + 2f, 8f, 5f, Palette.alpha(LAMP, 30))
            rect(c, x, y - 4, 1, 7, Palette.WOODS[0])
            val flick = if (still) 0 else ((time * 7f).toInt() % 3)
            rect(c, x - 1, y - 7, 3, 3, if (flick == 0) LAMP else Palette.GOLD)
            px(c, x, y - 8, Palette.WOODS[0])
        }
        val lh = hole.lighthouse ?: return
        val x = ((lh.first + 0.5f) * TILE).roundToInt() - ix
        val y = ((lh.second + 0.5f) * TILE).roundToInt() - iy
        // The tower: white with a red band, a lantern room on top.
        Pix.oval(c, x + 4f, y + 4f, 7f, 3f, shadow)
        rect(c, x - 3, y - 10, 7, 14, Palette.PAPER[3])
        rect(c, x - 3, y - 4, 7, 3, Palette.FLAG)
        rect(c, x + 2, y - 10, 2, 14, Palette.PAPER[1])
        rect(c, x - 4, y - 13, 9, 3, Palette.STONES[1])
        rect(c, x - 2, y - 15, 5, 2, LAMP)
        rect(c, x - 1, y - 17, 3, 2, Palette.STONES[1])
        // The beam sweeps round (scenery only; it never hides anything).
        if (!still) {
            val a = time * 0.7f
            val len = 260f
            beam.reset()
            beam.moveTo(x + 0.5f, y - 14f)
            beam.lineTo(x + cos(a - 0.12f) * len, y - 14f + sin(a - 0.12f) * len)
            beam.lineTo(x + cos(a + 0.12f) * len, y - 14f + sin(a + 0.12f) * len)
            beam.close()
            beamPaint.color = Palette.alpha(LAMP, 22)
            c.drawPath(beam, beamPaint)
        }
    }

    /** Whether a tile has something drawn on it here (for tests and spot pickers). */
    fun isBodyTile(t: Tile) = t == Tile.PLANK
}

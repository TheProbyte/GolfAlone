package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader

/** Drawing primitives for the low-resolution canvases: whole pixels only, no anti-aliasing. */
object Pix {
    private val p = Paint().apply { isAntiAlias = false }
    private val line = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 0f }

    /**
     * A bitmap with no density, so Android never rescales it when it's drawn: pixel art must
     * stay 1:1 inside the low-resolution canvases.
     */
    fun bitmap(w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap =
        Bitmap.createBitmap(w, h, config).apply { density = Bitmap.DENSITY_NONE }

    fun px(c: Canvas, x: Int, y: Int, col: Int) {
        p.color = col
        c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, p)
    }

    fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, col: Int) {
        if (w <= 0 || h <= 0) return
        p.color = col
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p)
    }

    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, col: Int) {
        p.color = col
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p)
    }

    /** A 1 px ring. */
    fun ring(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        line.color = col
        c.drawCircle(cx, cy, r, line)
    }

    /** A panel with clipped corners and a 1 px edge. */
    fun panel(c: Canvas, x: Int, y: Int, w: Int, h: Int, fill: Int = Palette.PANEL, edge: Int = Palette.PANEL_EDGE) {
        rect(c, x + 1, y, w - 2, h, fill)
        rect(c, x, y + 1, w, h - 2, fill)
        rect(c, x + 1, y, w - 2, 1, edge)
        rect(c, x + 1, y + h - 1, w - 2, 1, edge)
        rect(c, x, y + 1, 1, h - 2, edge)
        rect(c, x + w - 1, y + 1, 1, h - 2, edge)
    }

    // ------------------------------------------------------------ scorecard paper and driftwood

    /** A sheet of scorecard paper with a drop shadow and a perforated top edge. */
    fun paper(c: Canvas, x: Int, y: Int, w: Int, h: Int, perforated: Boolean = true) {
        rect(c, x + 2, y + 2, w, h, Palette.alpha(Palette.NIGHT[0], 150))
        rect(c, x + 1, y, w - 2, h, Palette.PAPER[3])
        rect(c, x, y + 1, w, h - 2, Palette.PAPER[3])
        rect(c, x + 1, y + h - 1, w - 2, 1, Palette.PAPER[1])
        if (perforated) for (i in x + 3 until x + w - 3 step 3) px(c, i, y + 2, Palette.PAPER[1])
    }

    /** Faint ruled lines across paper, every [step] px. */
    fun ruled(c: Canvas, x: Int, y: Int, w: Int, h: Int, step: Int = 10) {
        var ly = y
        while (ly < y + h) { rect(c, x, ly, w, 1, Palette.PAPER[2]); ly += step }
    }

    /** A driftwood plank (primary button face). [down] presses it into its shadow. */
    fun plank(c: Canvas, x: Int, y: Int, w: Int, h: Int, down: Boolean, seed: Int = 0) {
        val oy = if (down) 2 else 0
        if (!down) {
            rect(c, x + 1, y + h - 2, w - 2, 2, Palette.WOOD_DARKER)
            rect(c, x + 2, y + h, w - 2, 1, Palette.alpha(Palette.NIGHT[0], 120))
        }
        val top = y + oy
        val bh = h - 2
        rect(c, x + 1, top, w - 2, bh, Palette.WOOD)
        rect(c, x, top + 1, w, bh - 2, Palette.WOOD)
        rect(c, x + 1, top, w - 2, 1, Palette.WOOD_LIGHT)
        rect(c, x, top + 1, 1, bh - 2, Palette.WOOD_LIGHT)
        rect(c, x + 1, top + bh - 1, w - 2, 1, Palette.WOOD_DARK)
        // Grain: broken dashes on two lines.
        for (gy in intArrayOf(top + 3, top + bh - 4)) {
            var gx = x + 3
            while (gx < x + w - 4) {
                val len = 3 + (Terrain.hash(gx, gy, seed) * 6).toInt()
                if (Terrain.hash(gx, gy, seed + 1) > 0.35f) rect(c, gx, gy, minOf(len, x + w - 4 - gx), 1, Palette.WOOD_DARK)
                gx += len + 2
            }
        }
        // Nail heads.
        px(c, x + 3, top + bh / 2, Palette.WOOD_DARKER)
        px(c, x + w - 4, top + bh / 2, Palette.WOOD_DARKER)
    }

    /** A paper tab (secondary button face). */
    fun tab(c: Canvas, x: Int, y: Int, w: Int, h: Int, down: Boolean, on: Boolean = true) {
        val oy = if (down) 2 else 0
        val face = if (on) Palette.PAPER[2] else Palette.PAPER[1]
        if (!down) rect(c, x + 1, y + h - 2, w - 2, 2, Palette.PAPER[0])
        rect(c, x + 1, y + oy, w - 2, h - 2, face)
        rect(c, x, y + 1 + oy, w, h - 4, face)
        rect(c, x + 1, y + oy, w - 2, 1, Palette.PAPER[3])
    }

    /** A dark rounded chip floating over the course (the in-play pause button). */
    fun chip(c: Canvas, x: Int, y: Int, w: Int, h: Int, down: Boolean) {
        val a = if (down) 230 else 170
        rect(c, x + 1, y, w - 2, h, Palette.alpha(Palette.NIGHT[1], a))
        rect(c, x, y + 1, w, h - 2, Palette.alpha(Palette.NIGHT[1], a))
        rect(c, x + 1, y, w - 2, 1, Palette.alpha(Palette.NIGHT[4], a))
    }

    // ------------------------------------------------------------ ordered dither

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun bayer(x: Int, y: Int): Int = bayer[(y and 3) * 4 + (x and 3)]

    private val dissolvePaints: Array<Paint> = Array(17) { level ->
        val b = bitmap(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) b.setPixel(x, y, if (bayer(x, y) < level) Palette.INK_SHADOW else 0)
        Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    /** Covers the canvas with an ordered-dither dissolve: [level] 0 clear … 1 solid. */
    fun dissolve(c: Canvas, w: Int, h: Int, level: Float) {
        val l = (level * 16f + 0.5f).toInt().coerceIn(0, 16)
        if (l == 0) return
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dissolvePaints[l])
    }
}

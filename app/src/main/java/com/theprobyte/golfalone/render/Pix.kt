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

    // ------------------------------------------------------------ ordered dither

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun bayer(x: Int, y: Int): Int = bayer[(y and 3) * 4 + (x and 3)]

    private val dissolvePaints: Array<Paint> = Array(17) { level ->
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
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

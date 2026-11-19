package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Small sprites drawn in code: ball, flag, palms, gulls, clouds and UI icons. */
object Sprites {

    /**
     * The ball, 4×4, centred on ([x], [y]) in canvas pixels, with its shadow. [spin] 0..3
     * walks a seam pixel round the ball as it rolls; [ghost] draws only a faint outline
     * (seen through a palm canopy).
     */
    fun ball(c: Canvas, x: Int, y: Int, spin: Int, ghost: Boolean = false) {
        val l = x - 2
        val t = y - 2
        if (ghost) {
            val g = Palette.alpha(Palette.BALL, 150)
            rect(c, l + 1, t, 2, 1, g); rect(c, l + 1, t + 3, 2, 1, g)
            rect(c, l, t + 1, 1, 2, g); rect(c, l + 3, t + 1, 1, 2, g)
            return
        }
        rect(c, l + 2, t + 1, 2, 4, Palette.SHADOW)
        rect(c, l + 1, t + 2, 4, 2, Palette.SHADOW)
        rect(c, l + 1, t, 2, 4, Palette.BALL)
        rect(c, l, t + 1, 4, 2, Palette.BALL)
        px(c, l + 3, t + 2, Palette.BALL_SHADE)
        px(c, l + 2, t + 3, Palette.BALL_SHADE)
        // Seam dot turning with the roll.
        val sx = intArrayOf(1, 2, 2, 1)[spin and 3]
        val sy = intArrayOf(1, 1, 2, 2)[spin and 3]
        px(c, l + sx, t + sy, Palette.alpha(Palette.BALL_SHADE, 200))
    }

    /** Flag on its pole above the cup at ([cx], [cy]); [wave] 0/1, [lift] raises it when holed. */
    fun flag(c: Canvas, cx: Int, cy: Int, wave: Int, lift: Int) {
        val top = cy - 15 - lift
        for (i in 1..7) px(c, cx + i, cy - 1 + i / 3, Palette.alpha(Palette.CUP, 70))
        rect(c, cx, top, 1, cy - top, Palette.POLE)
        rect(c, cx + 1, top, 5, 1, Palette.FLAG)
        rect(c, cx + 1, top + 1, 6 - wave, 1, Palette.FLAG)
        rect(c, cx + 1, top + 2, 4 + wave, 1, Palette.FLAG_DARK)
        rect(c, cx + 1, top + 3, 2, 1, Palette.FLAG_DARK)
    }

    fun cup(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 2, cy - 2, 4, 4, Palette.CUP)
        rect(c, cx - 3, cy - 1, 6, 2, Palette.CUP)
        rect(c, cx - 2, cy + 2, 4, 1, Palette.CUP_LIP)
    }

    // ------------------------------------------------------------ palms

    private val frondDx = intArrayOf(-7, 7, -6, 6, -3, 3, -1, 2)
    private val frondDy = intArrayOf(-1, -1, 3, 3, -4, -4, 4, 4)

    /** Where the canopy centre sits relative to the trunk base. */
    const val CANOPY_DX = -3
    const val CANOPY_DY = -11

    fun palmShadow(c: Canvas, bx: Int, by: Int, sway: Int) {
        Pix.oval(c, bx + 4f + sway, by + 1.5f, 8f, 3.5f, Palette.alpha(Palette.INK_SHADOW, 55))
    }

    fun palmTrunk(c: Canvas, bx: Int, by: Int) {
        rect(c, bx - 1, by, 3, 1, Palette.alpha(Palette.INK_SHADOW, 80))
        for (i in 0..10) {
            val x = bx + (-i * 0.28f).roundToInt()
            val y = by - i
            val ring = i % 3 == 2
            px(c, x, y, if (ring) Palette.TRUNK_DARK else Palette.TRUNK)
            px(c, x + 1, y, Palette.TRUNK_DARK)
        }
    }

    private const val CANOPY_OX = 8
    private const val CANOPY_OY = 6

    /** The canopy pre-drawn once; [sway] only shifts it, so one bitmap serves every palm. */
    private val canopy: Bitmap by lazy {
        val b = Bitmap.createBitmap(17, 15, Bitmap.Config.ARGB_8888)
        drawCanopy(Canvas(b), CANOPY_OX, CANOPY_OY)
        b
    }
    private val blit = android.graphics.Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    /** The canopy, drawn over the ball. [sway] shifts it by a pixel in the breeze. */
    fun palmCanopy(c: Canvas, bx: Int, by: Int, sway: Int) {
        val tx = bx + CANOPY_DX + sway
        val ty = by + CANOPY_DY
        c.drawBitmap(canopy, (tx - CANOPY_OX).toFloat(), (ty - CANOPY_OY).toFloat(), blit)
    }

    private fun drawCanopy(c: Canvas, tx: Int, ty: Int) {
        for (f in frondDx.indices) {
            val dx = frondDx[f]
            val dy = frondDy[f]
            val len = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            for (s in 0..len) {
                val k = s / len.toFloat()
                val x = tx + (dx * k).roundToInt()
                val y = ty + (dy * k + k * k * 2.5f).roundToInt()
                px(c, x, y + 1, Palette.PALM_DARK)
                px(c, x, y, Palette.PALM)
                if (s in 1 until len - 1 && dy <= 0) px(c, x, y - 1, Palette.PALM_LIGHT)
                if (s in 2 until len && (s % 2 == 0)) px(c, x, y + 2, Palette.alpha(Palette.PALM_DARK, 200))
            }
        }
        rect(c, tx - 1, ty - 1, 3, 3, Palette.PALM)
        px(c, tx, ty - 1, Palette.PALM_LIGHT)
        px(c, tx - 1, ty + 1, Palette.TRUNK_DARK)
        px(c, tx + 1, ty + 1, Palette.TRUNK_DARK)
    }

    /** True if ([x], [y]) is under the canopy of the palm based at ([bx], [by]). */
    fun underCanopy(x: Float, y: Float, bx: Float, by: Float): Boolean {
        val cx = bx + CANOPY_DX
        val cy = by + CANOPY_DY + 1
        return x > cx - 8 && x < cx + 8 && y > cy - 6 && y < cy + 6
    }

    // ------------------------------------------------------------ gull

    fun gull(c: Canvas, x: Int, y: Int, flap: Boolean, col: Int) {
        if (flap) {
            px(c, x - 2, y - 1, col); px(c, x + 2, y - 1, col)
            px(c, x - 1, y, col); px(c, x + 1, y, col); px(c, x, y + 1, col)
        } else {
            rect(c, x - 2, y, 2, 1, col); rect(c, x + 1, y, 2, 1, col); px(c, x, y + 1, col)
        }
    }

    // ------------------------------------------------------------ clouds

    /** A soft cloud shadow: overlapping blobs, solid inside and dithered at the edges. */
    fun cloudShadow(seed: Int): Bitmap {
        val w = 84
        val h = 44
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val blobs = 4
        val bx = FloatArray(blobs) { 18f + Terrain.hash(it, 1, seed) * 48f }
        val by = FloatArray(blobs) { 14f + Terrain.hash(it, 2, seed) * 16f }
        val br = FloatArray(blobs) { 9f + Terrain.hash(it, 3, seed) * 7f }
        val col = Palette.alpha(Palette.INK_SHADOW, 46)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (i in 0 until blobs) {
                val dx = (x - bx[i]) / (br[i] * 1.4f)
                val dy = (y - by[i]) / br[i]
                m = maxOf(m, 1f - sqrt(dx * dx + dy * dy))
            }
            val on = m > 0.18f || (m > 0f && Pix.bayer(x, y) < (m / 0.18f * 16f).toInt())
            if (on) b.setPixel(x, y, col)
        }
        return b
    }

    // ------------------------------------------------------------ icons (UI)

    fun pauseIcon(c: Canvas, x: Int, y: Int, col: Int) {
        rect(c, x, y, 2, 6, col)
        rect(c, x + 4, y, 2, 6, col)
    }

    /** A small flag: [col] is the cloth colour. */
    fun flagIcon(c: Canvas, x: Int, y: Int, col: Int, h: Int = 8) {
        rect(c, x, y, 1, h, Palette.POLE)
        rect(c, x + 1, y, 4, 1, col)
        rect(c, x + 1, y + 1, 5, 1, col)
        rect(c, x + 1, y + 2, 3, 1, col)
    }

    fun lockIcon(c: Canvas, x: Int, y: Int, col: Int) {
        rect(c, x + 1, y, 3, 1, col)
        px(c, x, y + 1, col); px(c, x + 4, y + 1, col)
        px(c, x, y + 2, col); px(c, x + 4, y + 2, col)
        rect(c, x - 1, y + 3, 7, 4, col)
        px(c, x + 2, y + 4, Palette.INK_SHADOW)
        px(c, x + 2, y + 5, Palette.INK_SHADOW)
    }

    fun palmSway(time: Float, seed: Int): Int = (sin(time * 1.1f + seed * 1.7f) * 0.9f).roundToInt()
}

package com.theprobyte.golfalone.render

import android.graphics.Canvas
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.roundToInt

/**
 * Short frame-by-frame effects in the world: a water splash (4 frames), a puff of dust
 * where a flying ball comes down (3 frames) and the club's swoosh behind a struck ball
 * (2 frames). Drawn as pixels, so they take the time of day's colours.
 */
class SpriteFx {
    private val n = 8
    private val kind = IntArray(n)
    private val fx = FloatArray(n)
    private val fy = FloatArray(n)
    private val ux = FloatArray(n)
    private val uy = FloatArray(n)
    private val col = IntArray(n)
    private val t = FloatArray(n) { -1f }
    private var next = 0

    fun clear() = t.fill(-1f)

    val active: Boolean get() = t.any { it >= 0f }

    fun splash(x: Float, y: Float) = add(SPLASH, x, y, 0f, 0f, 0)
    fun dust(x: Float, y: Float, colour: Int) = add(DUST, x, y, 0f, 0f, colour)
    /** [dx], [dy]: the shot's direction (unit). */
    fun swoosh(x: Float, y: Float, dx: Float, dy: Float) = add(SWOOSH, x, y, dx, dy, 0)

    private fun add(k: Int, x: Float, y: Float, dx: Float, dy: Float, c: Int) {
        val i = next; next = (next + 1) % n
        kind[i] = k; fx[i] = x; fy[i] = y; ux[i] = dx; uy[i] = dy; col[i] = c; t[i] = 0f
    }

    fun update(dt: Float) {
        for (i in 0 until n) if (t[i] >= 0f) { t[i] += dt; if (t[i] > DUR[kind[i]]) t[i] = -1f }
    }

    fun draw(c: Canvas, ix: Int, iy: Int) {
        for (i in 0 until n) if (t[i] >= 0f) {
            val frame = (t[i] / DUR[kind[i]] * FRAMES[kind[i]]).toInt().coerceAtMost(FRAMES[kind[i]] - 1)
            val x = fx[i].roundToInt() - ix
            val y = fy[i].roundToInt() - iy
            when (kind[i]) {
                SPLASH -> splashFrame(c, x, y, frame)
                DUST -> dustFrame(c, x, y, frame, col[i])
                SWOOSH -> swooshFrame(c, fx[i] - ix, fy[i] - iy, ux[i], uy[i], frame)
            }
        }
    }

    private fun splashFrame(c: Canvas, x: Int, y: Int, f: Int) {
        val foam = Palette.FOAM
        val shoal = Palette.SHOAL
        when (f) {
            0 -> { // the crown opens
                Pix.ring(c, x + 0.5f, y + 0.5f, 2f, shoal)
                px(c, x - 2, y - 1, foam); px(c, x + 2, y - 1, foam); px(c, x, y - 2, foam)
            }
            1 -> { // a column of water
                Pix.ring(c, x + 0.5f, y + 0.5f, 3f, shoal)
                rect(c, x - 1, y - 4, 3, 4, shoal)
                rect(c, x, y - 5, 1, 4, foam)
                px(c, x - 3, y - 2, foam); px(c, x + 3, y - 2, foam)
            }
            2 -> { // it breaks into drops
                Pix.ring(c, x + 0.5f, y + 0.5f, 4f, Palette.alpha(shoal, 200))
                px(c, x, y - 6, foam); px(c, x - 3, y - 4, foam); px(c, x + 3, y - 4, foam)
                px(c, x - 4, y - 1, foam); px(c, x + 4, y - 1, foam); px(c, x, y - 1, shoal)
            }
            else -> { // rings settle
                Pix.ring(c, x + 0.5f, y + 0.5f, 5f, Palette.alpha(foam, 120))
                px(c, x - 4, y + 1, foam); px(c, x + 4, y + 1, foam)
            }
        }
    }

    private fun dustFrame(c: Canvas, x: Int, y: Int, f: Int, colour: Int) {
        val a = 220 - f * 60
        val d = 2 + f * 2
        val cl = Palette.alpha(colour, a)
        rect(c, x - d - 1, y + 1, 2, 1, cl); rect(c, x + d, y + 1, 2, 1, cl)
        px(c, x - d + 1, y, cl); px(c, x + d - 1, y, cl)
        if (f == 0) px(c, x, y + 2, cl)
    }

    private fun swooshFrame(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, f: Int) {
        // An arc behind the ball, across the line of the shot.
        val back = if (f == 0) 4f else 7f
        val half = if (f == 0) 2 else 1
        val a = if (f == 0) 200 else 110
        val px0 = -dy
        val py0 = dx
        for (k in -half..half) {
            val bend = kotlin.math.abs(k) * 0.7f
            val wx = x - dx * (back - bend) + px0 * k * 1.2f
            val wy = y - dy * (back - bend) + py0 * k * 1.2f
            px(c, wx.roundToInt(), wy.roundToInt(), Palette.alpha(Palette.INK, a))
        }
    }

    companion object {
        const val SPLASH = 0
        const val DUST = 1
        const val SWOOSH = 2
        private val DUR = floatArrayOf(0.44f, 0.3f, 0.12f)
        private val FRAMES = intArrayOf(4, 3, 2)
    }
}

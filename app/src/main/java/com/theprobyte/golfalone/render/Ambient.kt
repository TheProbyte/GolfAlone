package com.theprobyte.golfalone.render

import android.graphics.Canvas
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Small life on the island, drawn over the ground: crabs on the beaches, butterflies over
 * the flowers, fish rings in the ponds and a gull resting on a wall. All of it moves on
 * stepped time (15 Hz), so an idle screen can keep its slow frame rate, and it keeps
 * still (or stays away) under reduce motion. None of it touches the ball.
 */
class Ambient {
    private var w = 1
    private var crabs = IntArray(0)
    private var flies = IntArray(0)
    private var fish = IntArray(0)
    private var perch = -1
    /** When the perched gull took off (-1: still perched), and whether it has gone for this hole. */
    private var flewAt = -1f
    private var gone = false

    private var crab = 0
    private var crabDark = 0
    private var beak = 0
    private var wing = 0

    fun reset(t: Terrain) {
        w = t.w
        crabs = pick(t.beachSpots, 2, 11)
        flies = pick(t.flowerSpots, 2, 12)
        fish = pick(t.pondSpots, 2, 13)
        perch = pick(t.wallTopSpots, 1, 14).firstOrNull() ?: -1
        flewAt = -1f
        gone = false
        val m = Palette.mood
        crab = m.grade(CRAB)
        crabDark = m.grade(CRAB_DARK)
        beak = m.grade(BEAK)
        wing = Palette.STONE_TOP
    }

    /** [k] spots from [src], chosen by a stable hash so a hole always has the same ones. */
    private fun pick(src: List<Int>, k: Int, seed: Int): IntArray =
        src.sortedBy { Terrain.hash(it % 4096, it / 4096, seed) }.take(k).toIntArray()

    /** Whether the gull is mid take-off (worth full-rate frames). */
    fun busy(time: Float) = flewAt >= 0f && time - flewAt < FLY_S

    fun draw(c: Canvas, ix: Int, iy: Int, time: Float, ballX: Float, ballY: Float, ballMoving: Boolean, still: Boolean) {
        val t = (time * 15f).toInt() / 15f
        for ((i, s) in crabs.withIndex()) drawCrab(c, s % w - ix, s / w - iy, t, i, hypot(ballX - s % w, ballY - s / w) < 14f, still)
        if (!still) for ((i, s) in flies.withIndex()) drawFly(c, s % w - ix, s / w - iy, t, i)
        if (!still) for ((i, s) in fish.withIndex()) {
            val p = ((t + i * 2.3f) % 5f) / 1.4f
            if (p < 1f) Pix.ring(c, s % w - ix + 0.5f, s / w - iy + 0.5f, 1f + p * 4f, Palette.alpha(Palette.POND[4], (170 * (1f - p)).toInt()))
            if (p < 0.12f) px(c, s % w - ix, s / w - iy - 1, Palette.POND[4])
        }
        if (perch >= 0 && !gone) {
            val gx = perch % w
            val gy = perch / w
            if (flewAt < 0f && ballMoving && hypot(ballX - gx, ballY - gy) < 22f) flewAt = time
            if (flewAt < 0f) drawPerched(c, gx - ix, gy - iy, t)
            else {
                val f = time - flewAt
                if (f > FLY_S) gone = true
                else {
                    val x = (gx + f * 34f).roundToInt() - ix
                    val y = (gy - 3 - f * 26f - f * f * 8f).roundToInt() - iy
                    val flap = ((f * 9f).toInt()) % 2 == 0
                    val lift = (f * 10f).roundToInt()
                    Sprites.gull(c, x + 2 + lift / 2, y + 3 + lift, flap, Palette.alpha(Palette.INK_SHADOW, 60))
                    Sprites.gull(c, x, y, flap, Palette.INK)
                }
            }
        }
    }

    /** A crab scuttling sideways along the beach; it ducks into the sand when the ball is near. */
    private fun drawCrab(c: Canvas, x0: Int, y: Int, t: Float, i: Int, hide: Boolean, still: Boolean) {
        if (hide) {
            px(c, x0 - 1, y, crabDark); px(c, x0 + 1, y, crabDark)
            return
        }
        val ph = (t + i * 3.1f) % 6f
        val off = if (still) 0f else when {
            ph < 1f -> ph * 4f
            ph < 3f -> 4f
            ph < 4f -> (4f - ph) * 4f
            else -> 0f
        }
        val x = x0 + off.roundToInt()
        val walking = !still && (ph < 1f || ph in 3f..4f)
        val step = walking && ((t * 15f).toInt() % 2 == 0)
        rect(c, x - 1, y + 1, 3, 1, Palette.alpha(Palette.SANDS[1], 120)) // shadow
        rect(c, x - 1, y, 3, 1, crab)
        px(c, x, y - 1, crab)
        px(c, x - 2, y - 1, crab); px(c, x + 2, y - 1, crab) // claws
        px(c, if (step) x - 2 else x - 1, y + 1, crabDark)
        px(c, if (step) x + 2 else x + 1, y + 1, crabDark)
    }

    /** A butterfly looping over its flower patch. */
    private fun drawFly(c: Canvas, sx: Int, sy: Int, t: Float, i: Int) {
        val x = (sx + sin(t * 0.9f + i * 2f) * 7f).roundToInt()
        val y = (sy - 4 + sin(t * 1.7f + i) * 3f).roundToInt()
        val col = if (i % 2 == 0) Palette.FLOWER_Y else Palette.PAPER[3]
        px(c, x, y + 5, Palette.alpha(Palette.INK_SHADOW, 50))
        if (((t * 15f).toInt() + i) % 3 != 0) { px(c, x - 1, y, col); px(c, x + 1, y, col); px(c, x, y, Palette.WOOD_DARK) }
        else { px(c, x, y, col); px(c, x, y - 1, col) }
    }

    /** A gull standing on a wall top, looking about now and then. */
    private fun drawPerched(c: Canvas, x: Int, y: Int, t: Float) {
        val left = (t % 7f) in 4.5f..5.6f
        val hx = if (left) x - 1 else x + 1
        px(c, x, y + 1, Palette.alpha(Palette.INK_SHADOW, 90))
        rect(c, x - 1, y - 2, 3, 2, Palette.INK)
        px(c, if (left) x + 1 else x - 1, y - 2, wing)
        px(c, if (left) x + 2 else x - 2, y - 2, wing) // tail
        px(c, hx, y - 3, Palette.INK)
        px(c, if (left) hx - 1 else hx + 1, y - 3, beak)
        px(c, x, y, beak)
    }

    companion object {
        private const val CRAB = 0xFFE0603C.toInt()
        private const val CRAB_DARK = 0xFFA8382E.toInt()
        private const val BEAK = 0xFFF0A040.toInt()
        const val FLY_S = 3f
    }
}

package com.theprobyte.golfalone.render

import android.graphics.Canvas
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.cos
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

    /** Which course's life: cove, marsh (herons), light (fireflies), lagoon (fish), wreck (rain). */
    var theme = "cove"
        private set
    private var herons = IntArray(0)
    private var fireflies = IntArray(0)
    private var fishes = IntArray(0)
    private var mapH = 1
    private var seals = IntArray(0)
    private var sheep = IntArray(0)

    fun reset(t: Terrain, theme: String = "cove") {
        this.theme = theme
        w = t.w
        mapH = t.h
        herons = if (theme == "marsh") pick(t.beachSpots + t.pondSpots, 2, 15) else IntArray(0)
        fireflies = if (theme == "light") pick(t.flowerSpots + t.beachSpots, 6, 16) else IntArray(0)
        fishes = if (theme == "lagoon") pick(t.shallowSpots, 4, 17) else IntArray(0)
        seals = if (theme == "frost") pick(t.beachSpots, 2, 20) else IntArray(0)
        sheep = if (theme == "bluff") pick(t.grassSpots, 3, 21) else IntArray(0)
        crabs = if (theme in setOf("cove", "lagoon", "wreck", "marsh")) pick(t.beachSpots, 2, 11) else IntArray(0)
        flies = if (theme == "cove" || theme == "lagoon" || theme == "temple") pick(t.flowerSpots, 2, 12) else IntArray(0)
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
        for (i in crabs.indices) { val s = crabs[i]; drawCrab(c, s % w - ix, s / w - iy, t, i, hypot(ballX - s % w, ballY - s / w) < 14f, still) }
        if (!still) for (i in flies.indices) { val s = flies[i]; drawFly(c, s % w - ix, s / w - iy, t, i) }
        for (i in herons.indices) { val s = herons[i]; drawHeron(c, s % w - ix, s / w - iy, t, i, hypot(ballX - s % w, ballY - s / w) < 18f) }
        for (i in fireflies.indices) { val s = fireflies[i]; drawFirefly(c, s % w - ix, s / w - iy, t, i, still) }
        if (!still) for (i in fishes.indices) { val s = fishes[i]; drawFish(c, s % w - ix, s / w - iy, t, i) }
        for (i in seals.indices) { val s = seals[i]; drawSeal(c, s % w - ix, s / w - iy, t, i, still) }
        for (i in sheep.indices) { val s = sheep[i]; drawSheep(c, s % w - ix, s / w - iy, t, i, still) }
        if (theme == "temple" && !still) drawParrot(c, ix, iy, t)
        if (!still) for (i in fish.indices) {
            val s = fish[i]
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

    /** A heron standing in the shallows; it lifts its head when the ball comes near. */
    private fun drawHeron(c: Canvas, x0: Int, y: Int, t: Float, i: Int, alert: Boolean) {
        val x = x0 + if (((t + i * 4f) % 12f) > 9f) 1 else 0 // the odd slow step
        px(c, x + 1, y + 1, Palette.alpha(Palette.INK_SHADOW, 90))
        rect(c, x, y - 1, 1, 2, Palette.STONES[1])                  // legs
        rect(c, x - 1, y - 4, 3, 3, Palette.PAPER[2])               // body
        px(c, x - 2, y - 3, Palette.STONES[2])                      // tail
        val neck = if (alert) 3 else 2
        rect(c, x + 1, y - 4 - neck, 1, neck, Palette.PAPER[3])
        px(c, x + 2, y - 4 - neck, beak); px(c, x + 3, y - 4 - neck, beak)
    }

    /** A firefly drifting in the dark, glowing on and off. */
    private fun drawFirefly(c: Canvas, sx: Int, sy: Int, t: Float, i: Int, still: Boolean) {
        val x = if (still) sx else (sx + sin(t * 0.6f + i * 1.7f) * 9f).roundToInt()
        val y = if (still) sy - 4 else (sy - 5 + sin(t * 0.45f + i * 2.3f) * 6f).roundToInt()
        val glow = if (still) 1f else (0.5f + 0.5f * sin(t * 2.2f + i * 1.3f))
        if (glow < 0.2f) return
        val a = (glow * 255).toInt()
        Pix.oval(c, x + 0.5f, y + 0.5f, 2.5f, 2.5f, Palette.alpha(FIREFLY, a / 5))
        px(c, x, y, Palette.alpha(FIREFLY, a))
    }

    /** A small fish shadow darting about the shallows. */
    private fun drawFish(c: Canvas, sx: Int, sy: Int, t: Float, i: Int) {
        val dart = ((t + i * 1.9f) / 2.2f).toInt()
        val a = Terrain.hash(dart, i, 18) * 6.283f
        val k = (((t + i * 1.9f) / 2.2f) % 1f).let { if (it < 0.25f) it / 0.25f else 1f }
        val x = (sx + cos(a) * 6f * k).roundToInt()
        val y = (sy + sin(a) * 4f * k).roundToInt()
        val col = Palette.alpha(Palette.SEA[1], 150)
        px(c, x, y, col); px(c, x - (cos(a) * 1.5f).roundToInt(), y - (sin(a) * 1.5f).roundToInt(), col)
        px(c, x - (cos(a) * 3f).roundToInt(), y - (sin(a) * 3f).roundToInt(), Palette.alpha(Palette.SEA[1], 90))
    }

    /** A seal lolling on the snowy shore; now and then it shuffles. */
    private fun drawSeal(c: Canvas, x0: Int, y: Int, t: Float, i: Int, still: Boolean) {
        val x = x0 + if (!still && ((t + i * 5f) % 10f) > 8f) 1 else 0
        Pix.oval(c, x + 1f, y + 2f, 4f, 1.5f, Palette.alpha(Palette.INK_SHADOW, 70))
        rect(c, x - 3, y - 1, 6, 3, Palette.STONES[2])
        rect(c, x - 2, y - 2, 4, 1, Palette.STONES[3])
        rect(c, x + 2, y - 3, 2, 2, Palette.STONES[2])
        px(c, x + 3, y - 3, Palette.INK_SHADOW)
        px(c, x - 4, y + 1, Palette.STONES[1])
    }

    /** A sheep grazing: a woolly back and a dark head that dips. */
    private fun drawSheep(c: Canvas, x: Int, y: Int, t: Float, i: Int, still: Boolean) {
        val graze = !still && ((t * 0.7f + i * 1.3f) % 3f) < 1.5f
        Pix.oval(c, x + 1f, y + 2.5f, 3.5f, 1.5f, Palette.alpha(Palette.INK_SHADOW, 70))
        rect(c, x - 2, y - 2, 5, 4, Palette.PAPER[3])
        px(c, x - 2, y - 2, Palette.PAPER[2]); px(c, x + 2, y + 1, Palette.PAPER[2])
        rect(c, x + 3, y - 1 + (if (graze) 1 else 0), 2, 2, Palette.STONES[0])
        px(c, x - 1, y + 2, Palette.STONES[0]); px(c, x + 2, y + 2, Palette.STONES[0])
    }

    /** A parrot crossing the jungle now and then: red with a blue wing. */
    private fun drawParrot(c: Canvas, ix: Int, iy: Int, t: Float) {
        val period = 14f
        val ph = t % period
        if (ph > 4f) return
        val run = (t / period).toInt()
        val left = Terrain.hash(run, 0, 22) < 0.5f
        val y = (mapH * (0.2f + 0.6f * Terrain.hash(run, 1, 22))).toInt() + (sin(ph * 5f) * 2f).roundToInt()
        val x = if (left) (-10 + ph * (w + 20) / 4f).toInt() else (w + 10 - ph * (w + 20) / 4f).toInt()
        val flap = (t * 10f).toInt() % 2 == 0
        val sx = x - ix
        val sy = y - iy
        rect(c, sx - 1, sy, 3, 2, PARROT)
        px(c, if (left) sx + 2 else sx - 2, sy, Palette.GOLD)
        px(c, sx, if (flap) sy - 2 else sy + 2, Palette.SEA[3]); px(c, sx - 1, if (flap) sy - 1 else sy + 1, Palette.SEA[3])
    }

    /** Weather over the whole view, in screen space: rain at the wreck, embers on the volcano, snow on the fjord. */
    fun rain(c: Canvas, lw: Int, lh: Int, time: Float) {
        val t = (time * 15f).toInt() / 15f
        if (theme == "ember") {
            for (k in 0 until 18) {
                val h1 = Terrain.hash(k, 3, 19)
                val h2 = Terrain.hash(k, 4, 19)
                val x = ((h1 * (lw + 20) + sin(t * 0.8f + k) * 6f).toInt() + lw) % (lw + 20) - 10
                val y = lh + 10 - ((h2 * (lh + 20) + t * 14f * (0.6f + h1)) % (lh + 20)).toInt()
                px(c, x, y, Palette.alpha(if (k % 3 == 0) Palette.GOLD else EMBER, 200))
            }
            return
        }
        if (theme == "frost") {
            for (k in 0 until 40) {
                val h1 = Terrain.hash(k, 5, 19)
                val h2 = Terrain.hash(k, 6, 19)
                val x = ((h1 * (lw + 20) + t * 6f + sin(t * 0.9f + k) * 4f).toInt() % (lw + 20)) - 10
                val y = ((h2 * (lh + 20) + t * 22f * (0.6f + h1)) % (lh + 20)).toInt() - 10
                px(c, x, y, Palette.alpha(0xFFFFFFFF.toInt(), 200))
                if (k % 4 == 0) px(c, x + 1, y, Palette.alpha(0xFFFFFFFF.toInt(), 110))
            }
            return
        }
        if (theme != "wreck") return
        val col = Palette.alpha(Palette.PAPER[3], 90)
        for (k in 0 until 46) {
            val h1 = Terrain.hash(k, 1, 19)
            val h2 = Terrain.hash(k, 2, 19)
            val x = ((h1 * (lw + 40) + t * 30f) % (lw + 40)).toInt() - 20
            val y = ((h2 * (lh + 40) + t * 180f * (0.8f + h1 * 0.4f)) % (lh + 40)).toInt() - 20
            px(c, x, y, col); px(c, x - 1, y - 2, col); px(c, x - 1, y - 3, Palette.alpha(Palette.PAPER[3], 50))
        }
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
        private const val FIREFLY = 0xFFE8F57A.toInt()
        private const val EMBER = 0xFFF07A30.toInt()
        private const val PARROT = 0xFFE0483E.toInt()
        const val FLY_S = 3f
    }
}

package com.theprobyte.golfalone.game

import kotlin.math.floor
import kotlin.math.min

/** Deterministic integer hash noise, shared by the art and the coastline. */
object Noise {
    fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffffff) / 16777216f
    }
}

/**
 * The coastline as it's drawn: per pixel over the hole, the tile map's land is blurred and
 * thresholded (with a little jitter), so corners round off by at most a few pixels.
 *
 * Physics uses this, not the tile grid, to decide when the ball is in the water. That means
 * what you see is what counts: a ball touching the drawn foam line is still on the beach.
 */
class Coast(private val hole: Hole) {
    val w = hole.pxW
    val h = hole.pxH

    /** Drawn land, per pixel. */
    val land = BooleanArray(w * h)

    /** Land pixels: distance to the nearest water. Water pixels: distance to the nearest land. */
    val dist = FloatArray(w * h)

    init {
        val r = 4
        val iw = w + 2 * r + 1
        val ih = h + 2 * r + 1
        val sum = IntArray(iw * ih)
        for (y in 1 until ih) {
            var row = 0
            for (x in 1 until iw) {
                val tx = Math.floorDiv(x - 1 - r, TILE)
                val ty = Math.floorDiv(y - 1 - r, TILE)
                if (!hole.tile(tx, ty).water) row++
                sum[y * iw + x] = sum[(y - 1) * iw + x] + row
            }
        }
        val area = ((2 * r) * (2 * r)).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x + 1
            val y0 = y + 1
            val x1 = x + 2 * r + 1
            val y1 = y + 2 * r + 1
            val v = (sum[y1 * iw + x1] - sum[y0 * iw + x1] - sum[y1 * iw + x0] + sum[y0 * iw + x0]) / area
            land[y * w + x] = v + (Noise.hash(x / 2, y / 2, 9) - 0.5f) * 0.14f > 0.5f
        }
        val toWater = chamfer { !land[it] }
        val toLand = chamfer { land[it] }
        for (i in 0 until w * h) dist[i] = (if (land[i]) toWater[i] else toLand[i]) - 0.5f
    }

    private fun chamfer(isSource: (Int) -> Boolean): FloatArray {
        val d = FloatArray(w * h) { if (isSource(it)) 0f else 99f }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) v = min(v, d[i - w] + 1f)
            if (x > 0 && y > 0) v = min(v, d[i - w - 1] + 1.414f)
            if (x < w - 1 && y > 0) v = min(v, d[i - w + 1] + 1.414f)
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1f)
            if (y < h - 1) v = min(v, d[i + w] + 1f)
            if (x < w - 1 && y < h - 1) v = min(v, d[i + w + 1] + 1.414f)
            if (x > 0 && y < h - 1) v = min(v, d[i + w - 1] + 1.414f)
            d[i] = v
        }
        return d
    }

    private fun index(x: Float, y: Float): Int {
        val px = floor(x).toInt()
        val py = floor(y).toInt()
        return if (px < 0 || py < 0 || px >= w || py >= h) -1 else py * w + px
    }

    fun isLand(x: Float, y: Float): Boolean {
        val i = index(x, y)
        return i >= 0 && land[i]
    }

    /** How far ([x], [y]) is into the water (0 on land; off the map counts as open sea). */
    fun waterDepth(x: Float, y: Float): Float {
        val i = index(x, y)
        if (i < 0) return 99f
        return if (land[i]) 0f else dist[i]
    }

    /** How far ([x], [y]) is from the water, on land (0 in the water). */
    fun landDepth(x: Float, y: Float): Float {
        val i = index(x, y)
        if (i < 0) return 0f
        return if (land[i]) dist[i] else 0f
    }
}

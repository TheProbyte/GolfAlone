package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import kotlin.math.min

/**
 * Paints a hole's ground once, pixel by pixel, from its tiles:
 * - mown stripes, rough tufts with flowers, beaches with shells and pebbles
 * - stone blocks lit from the top left, driftwood logs with grain and cut ends
 * - terraced shading on slopes
 *
 * The coastline is smoothed per pixel (a blurred land mask), so islands read as rounded
 * shapes rather than tile staircases. Straight tile edges stay exactly where physics has
 * them, and only corners round off, by about 3 px at most. The sea's foam and ripples are
 * four transparent overlay frames played on top.
 */
class Terrain(private val hole: Hole) {

    val w = hole.pxW
    val h = hole.pxH
    val ground: Bitmap
    val water: Array<Bitmap>

    /** Visual land mask (per pixel), after smoothing. */
    private val land = BooleanArray(w * h)
    /** Distance (px, chamfer) from each pixel to the nearest pixel of the other kind (land/water). */
    private val edge = FloatArray(w * h)

    init {
        computeLand()
        computeEdges()
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = groundPixel(x, y)
        ground = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        water = Array(FRAMES) { f ->
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) o[y * w + x] = waterPixel(x, y, f)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(o, 0, w, 0, 0, w, h) }
        }
    }

    private fun tileAt(x: Int, y: Int): Tile = hole.tile(Math.floorDiv(x, TILE), Math.floorDiv(y, TILE))

    /** Box-blurs the tile land mask (radius 4) and thresholds it, with a little jitter. */
    private fun computeLand() {
        val r = 4
        val iw = w + 2 * r + 1
        val ih = h + 2 * r + 1
        val sum = IntArray(iw * ih) // integral image over the padded mask
        for (y in 1 until ih) {
            var row = 0
            for (x in 1 until iw) {
                if (!tileAt(x - 1 - r, y - 1 - r).water) row++
                sum[y * iw + x] = sum[(y - 1) * iw + x] + row
            }
        }
        val area = ((2 * r) * (2 * r)).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            // Window of 2r×2r pixels centred on the pixel's centre.
            val x0 = x + 1
            val y0 = y + 1
            val x1 = x + 2 * r + 1
            val y1 = y + 2 * r + 1
            val s = sum[y1 * iw + x1] - sum[y0 * iw + x1] - sum[y1 * iw + x0] + sum[y0 * iw + x0]
            val v = s / area + (hash(x / 2, y / 2, 9) - 0.5f) * 0.14f
            land[y * w + x] = v > 0.5f
        }
    }

    /** Two-pass chamfer distance to the nearest pixel of the opposite kind, capped. */
    private fun computeEdges() {
        val inf = 99f
        for (pass in 0..1) {
            val want = pass == 0 // pass 0: distance for water pixels to land; pass 1: land to water
            val d = FloatArray(w * h) { if (land[it] == want) 0f else inf }
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
            // Pixels on the far side of the map edge count as water, which matters for land touching it.
            for (i in 0 until w * h) if (land[i] != want) edge[i] = d[i] - 0.5f
        }
    }

    private fun groundPixel(x: Int, y: Int): Int {
        val tx = x / TILE
        val ty = y / TILE
        val u = x % TILE
        val v = y % TILE
        var t = hole.tile(tx, ty)
        val n = hash(x, y, 1)
        val d = edge[y * w + x]
        val isLand = land[y * w + x]

        if (!isLand) {
            val dith = hash(x, y, 7) * 1.5f
            return when {
                d + dith < 3.5f -> Palette.SHALLOW
                d + dith < 8f -> Palette.MID
                else -> Palette.DEEP
            }
        }
        if (t == Tile.WALL) return stone(tx, ty, u, v, n)
        if (t == Tile.WOOD) return wood(tx, ty, u, v)
        if (t.water) t = Tile.ROUGH // a smoothed-in corner of the coast

        // Beaches where land meets the sea, with the odd shell or pebble.
        if (d < 3.6f + hash(x, y, 3)) {
            val shell = hash(x / 2, y / 2, 21)
            if (d > 0.8f && shell < 0.035f) return if (hash(x, y, 22) < 0.5f) Palette.SHELL else Palette.SHELL_PINK
            if (d > 0.8f && shell > 0.975f) return Palette.PEBBLE
            if (d < 1.6f) return if (n < 0.2f) Palette.SAND else Palette.SAND_LIGHT
            return if (n < 0.12f) Palette.SAND_DARK else Palette.SAND
        }

        var c = when {
            t == Tile.FAIRWAY -> when {
                hash(x / 2, y / 2, 33) < 0.004f && (x + y) % 2 == 0 -> Palette.FLOWER_W // a daisy
                n < 0.05f -> Palette.FAIR_DOT
                ty % 2 == 0 -> Palette.FAIR_A
                else -> Palette.FAIR_B
            }
            t == Tile.ROUGH -> {
                val f = hash(x / 2, y / 2, 31)
                when {
                    f < 0.012f && (x + y) % 2 == 0 -> when ((hash(x / 2, y / 2, 32) * 3).toInt()) {
                        0 -> Palette.FLOWER_Y
                        1 -> Palette.FLOWER_P
                        else -> Palette.FLOWER_W
                    }
                    n < 0.13f -> Palette.ROUGH_DARK
                    n > 0.95f || (hash(x, y + 1, 1) > 0.95f) -> Palette.ROUGH_LIGHT
                    else -> Palette.ROUGH
                }
            }
            t == Tile.SAND -> {
                val lip = neighbourDiffers(x, y) { it != Tile.SAND }
                if (lip) Palette.SAND_DARK else if (n < 0.1f) Palette.SAND_DARK else if (n > 0.93f) Palette.SAND_LIGHT else Palette.SAND
            }
            t.isGreen -> {
                var g = if (tx % 2 == 0) Palette.GREEN_A else Palette.GREEN_B
                if (t.slopeX != 0f || t.slopeY != 0f) {
                    // Terraced shading: darker towards the downhill side of each tile.
                    val k = when {
                        t.slopeY > 0 -> v / 7f
                        t.slopeY < 0 -> 1f - v / 7f
                        t.slopeX > 0 -> u / 7f
                        else -> 1f - u / 7f
                    }
                    g = Palette.lerp(Palette.GREEN_A, Palette.SLOPE_DARK, k * 0.9f)
                }
                if (neighbourDiffers(x, y) { !it.isGreen }) Palette.lerp(g, Palette.FAIR_A, 0.6f) else g
            }
            else -> Palette.FAIR_A
        }

        // Shadows cast by stone and wood to the bottom right (light from the top left).
        val above = hole.tile(tx, ty - 1).solid
        val left = hole.tile(tx - 1, ty).solid
        val diag = hole.tile(tx - 1, ty - 1).solid
        if ((above && v < 3) || (left && u < 2) || (diag && u < 2 && v < 3)) c = Palette.shade(c, 0.74f)
        return c
    }

    private fun stone(tx: Int, ty: Int, u: Int, v: Int, n: Float): Int {
        val up = hole.tile(tx, ty - 1) == Tile.WALL
        val down = hole.tile(tx, ty + 1) == Tile.WALL
        val left = hole.tile(tx - 1, ty) == Tile.WALL
        val right = hole.tile(tx + 1, ty) == Tile.WALL
        // Round off outer corners.
        val corner = (!up && !left && u == 0 && v == 0) || (!up && !right && u == 7 && v == 0) ||
            (!down && !left && u == 0 && v == 7) || (!down && !right && u == 7 && v == 7)
        if (corner) return groundBehind(tx, ty, u, v)
        return when {
            !down && v == 7 -> Palette.STONE_DARKER
            !down && v >= 5 -> Palette.STONE_DARK
            !up && v == 0 -> Palette.STONE_LIGHT
            !left && u == 0 -> Palette.STONE_LIGHT
            !right && u == 7 -> Palette.STONE_DARK
            // Block seams.
            v == 3 && (u + ty) % 4 != 0 -> Palette.STONE_DARK
            v < 3 && u == (if (ty % 2 == 0) 3 else 6) -> Palette.STONE_DARK
            v > 3 && u == (if (ty % 2 == 0) 6 else 2) -> Palette.STONE_DARK
            n < 0.07f -> Palette.STONE_DARK
            n > 0.9f -> Palette.STONE_LIGHT
            else -> Palette.STONE
        }
    }

    /** Driftwood: logs run along their neighbours, with grain and pale cut ends. */
    private fun wood(tx: Int, ty: Int, u0: Int, v0: Int): Int {
        val l = hole.tile(tx - 1, ty) == Tile.WOOD
        val r = hole.tile(tx + 1, ty) == Tile.WOOD
        val up = hole.tile(tx, ty - 1) == Tile.WOOD
        val dn = hole.tile(tx, ty + 1) == Tile.WOOD
        val horizontal = l || r || !(up || dn)
        // Work in log space: a = along the log, b = across (0 top/left … 7).
        val a = if (horizontal) u0 else v0
        val b = if (horizontal) v0 else u0
        val startOpen = if (horizontal) !l else !up
        val endOpen = if (horizontal) !r else !dn
        // Rounded ends.
        if ((startOpen && a == 0 || endOpen && a == 7) && (b <= 1 || b >= 6)) return groundBehind(tx, ty, u0, v0)
        if ((startOpen && a <= 1 || endOpen && a >= 6)) {
            // Cut end: pale rings.
            return if (b == 0 || b == 7 || (a == 0 || a == 7)) Palette.WOOD_DARK
            else if ((b == 3 || b == 4) && (a == 1 || a == 6)) Palette.WOOD_DARK else Palette.WOOD_LIGHT
        }
        val g = Terrain.hash(tx * 8 + a, ty, 51)
        return when {
            b == 0 -> Palette.WOOD_LIGHT
            b == 7 -> Palette.WOOD_DARKER
            b == 6 -> Palette.WOOD_DARK
            (b == 2 || b == 4) && g > 0.25f -> Palette.WOOD_DARK
            b == 1 && g > 0.7f -> Palette.WOOD_LIGHT
            else -> Palette.WOOD
        }
    }

    /** What shows through a rounded corner: the neighbouring ground, roughly. */
    private fun groundBehind(tx: Int, ty: Int, u: Int, v: Int): Int {
        val ox = if (u < 4) -1 else 1
        val oy = if (v < 4) -1 else 1
        val t = sequenceOf(hole.tile(tx + ox, ty), hole.tile(tx, ty + oy), hole.tile(tx + ox, ty + oy))
            .firstOrNull { !it.solid } ?: Tile.ROUGH
        return when {
            t.water -> Palette.SHALLOW
            t.isGreen -> Palette.GREEN_B
            t == Tile.SAND -> Palette.SAND
            t == Tile.FAIRWAY -> Palette.FAIR_B
            else -> Palette.ROUGH
        }
    }

    private inline fun neighbourDiffers(x: Int, y: Int, pred: (Tile) -> Boolean): Boolean =
        pred(tileAt(x - 1, y)) || pred(tileAt(x + 1, y)) || pred(tileAt(x, y - 1)) || pred(tileAt(x, y + 1))

    private fun waterPixel(x: Int, y: Int, f: Int): Int {
        if (land[y * w + x]) return 0
        val d = edge[y * w + x]
        val n = hash(x, y, 11)
        // Foam hugging the shore, shimmering.
        if (d < 1.2f) return if ((((x + y) / 2 + f) % FRAMES) != 0) Palette.alpha(Palette.FOAM, 220) else Palette.alpha(Palette.FOAM, 120)
        if (d < 2.2f && hash(x / 2, y / 2, 5 + f) < 0.45f) return Palette.alpha(Palette.FOAM, 110)
        // A ripple band walking out from the shore.
        val r = 3f + f * 1.25f
        if (d >= r && d < r + 1f && n < 0.55f) return Palette.alpha(Palette.FOAM, 70 - f * 12)
        // Glints on open water.
        if (d > 6f && hash(x / 2, y, 100 + f) < 0.006f) return Palette.alpha(Palette.SPARKLE, 200)
        return 0
    }

    /**
     * How far the drawn coastline strays from the physics tiles: the deepest pixel (px from
     * the nearest tile of the drawn kind) drawn as land on a water tile, and as water on a
     * land tile.
     */
    fun coastMismatch(): FloatArray {
        val out = FloatArray(2)
        for (y in 0 until h) for (x in 0 until w) {
            val tx = x / TILE
            val ty = y / TILE
            val tileLand = !hole.tile(tx, ty).water
            val drawnLand = land[y * w + x]
            if (tileLand == drawnLand) continue
            var best = 99f
            for (dy in -1..1) for (dx in -1..1) {
                if (!hole.tile(tx + dx, ty + dy).water != drawnLand) continue
                val l = (tx + dx) * TILE.toFloat()
                val t = (ty + dy) * TILE.toFloat()
                val ex = maxOf(l - (x + 0.5f), 0f, (x + 0.5f) - (l + TILE))
                val ey = maxOf(t - (y + 0.5f), 0f, (y + 0.5f) - (t + TILE))
                best = minOf(best, kotlin.math.sqrt(ex * ex + ey * ey))
            }
            val k = if (drawnLand) 0 else 1
            out[k] = maxOf(out[k], best)
        }
        return out
    }

    /** A small nearest-sampled copy of the ground for hole cards: every [step]th pixel. */
    fun thumbnail(step: Int): Bitmap {
        val tw = w / step
        val th = h / step
        val px = IntArray(tw * th)
        val src = IntArray(w * h)
        ground.getPixels(src, 0, w, 0, 0, w, h)
        for (y in 0 until th) for (x in 0 until tw) px[y * tw + x] = src[(y * step + step / 2) * w + x * step + step / 2]
        return Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, tw, 0, 0, tw, th) }
    }

    companion object {
        const val FRAMES = 4

        fun hash(x: Int, y: Int, s: Int): Float {
            var h = x * 374761393 + y * 668265263 + s * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xffffff) / 16777216f
        }
    }
}

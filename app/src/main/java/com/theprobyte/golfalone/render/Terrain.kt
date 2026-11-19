package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Paints a hole's ground once, pixel by pixel, from its tiles: mown stripes, rough tufts,
 * beaches where land meets the sea, stone blocks with light from the top left, and
 * terraced shading on slopes. The sea's foam and ripples are four transparent overlay
 * frames played on top.
 */
class Terrain(private val hole: Hole) {

    val w = hole.pxW
    val h = hole.pxH
    val ground: Bitmap
    val water: Array<Bitmap>

    /** Distance (px) from each pixel to the nearest land pixel (water pixels) or water pixel (land). */
    private val edge = FloatArray(w * h)

    init {
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

    private fun computeEdges() {
        val reach = 2 // tiles
        for (y in 0 until h) for (x in 0 until w) {
            val tx = x / TILE
            val ty = y / TILE
            val water = hole.tile(tx, ty).water
            var best = 99f
            val cx = x + 0.5f
            val cy = y + 0.5f
            for (dy in -reach..reach) for (dx in -reach..reach) {
                val t = hole.tile(tx + dx, ty + dy)
                if (t.water == water) continue
                val l = ((tx + dx) * TILE).toFloat()
                val tp = ((ty + dy) * TILE).toFloat()
                val ex = max(max(l - cx, 0f), cx - (l + TILE))
                val ey = max(max(tp - cy, 0f), cy - (tp + TILE))
                best = min(best, sqrt(ex * ex + ey * ey))
            }
            edge[y * w + x] = best
        }
    }

    private fun groundPixel(x: Int, y: Int): Int {
        val tx = x / TILE
        val ty = y / TILE
        val u = x % TILE
        val v = y % TILE
        val t = hole.tile(tx, ty)
        val n = hash(x, y, 1)
        val d = edge[y * w + x]

        if (t.water) {
            val dith = hash(x, y, 7) * 1.5f
            return when {
                d + dith < 3.5f -> Palette.SHALLOW
                d + dith < 8f -> Palette.MID
                else -> Palette.DEEP
            }
        }
        if (t == Tile.WALL) return stone(tx, ty, u, v, n)

        // Beaches where land meets the sea.
        if (d < 1.6f) return if (n < 0.2f) Palette.SAND else Palette.SAND_LIGHT
        if (d < 2.6f + hash(x, y, 3)) return if (n < 0.12f) Palette.SAND_DARK else Palette.SAND

        var c = when {
            t == Tile.FAIRWAY -> if (n < 0.05f) Palette.FAIR_DOT else if (ty % 2 == 0) Palette.FAIR_A else Palette.FAIR_B
            t == Tile.ROUGH -> when {
                n < 0.13f -> Palette.ROUGH_DARK
                n > 0.95f || (hash(x, y + 1, 1) > 0.95f) -> Palette.ROUGH_LIGHT
                else -> Palette.ROUGH
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

        // Shadows cast by stone to the bottom right (light from the top left).
        val above = hole.tile(tx, ty - 1) == Tile.WALL
        val left = hole.tile(tx - 1, ty) == Tile.WALL
        val diag = hole.tile(tx - 1, ty - 1) == Tile.WALL
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

    /** What shows through a rounded stone corner: the neighbouring ground, roughly. */
    private fun groundBehind(tx: Int, ty: Int, u: Int, v: Int): Int {
        val ox = if (u == 0) -1 else 1
        val oy = if (v == 0) -1 else 1
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
        val tx = x / TILE
        val ty = y / TILE
        if (!hole.tile(tx, ty).water) return 0
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

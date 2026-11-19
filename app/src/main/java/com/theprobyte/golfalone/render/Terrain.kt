package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Paints a hole's ground once, pixel by pixel, from its tiles. It follows the style rules
 * in docs/STYLE_AND_UI_PLAN.md:
 * - Shading steps along hue-shifted ramps.
 * - Texture comes in small clusters on a jittered grid, never per-pixel noise.
 * - Light comes from the top left, and walls and logs cast offset shadows.
 * - Every ground edge is smoothed.
 * - It's drawn in a 3/4 view: islands stand on a short bank above the sea, and walls show
 *   a top face and a front face.
 *
 * Physics stays on the tile grid. Smoothing only rounds corners (by under 3 px), banks are
 * drawn over water, and wall faces stay inside their own tiles. The sea's foam and ripples
 * are four transparent overlay frames played on top.
 */
class Terrain(private val hole: Hole) {

    val w = hole.pxW
    val h = hole.pxH
    val ground: Bitmap
    @Volatile private var waterFrames: Array<Bitmap>? = null

    /** The sea's moving layer, [FRAMES] frames, built on first use (see [releaseWater]). */
    fun water(f: Int): Bitmap = (waterFrames ?: buildWater())[f % FRAMES]

    @Synchronized private fun buildWater(): Array<Bitmap> {
        waterFrames?.let { return it }
        val frames = Array(FRAMES) { f ->
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) o[y * w + x] = waterPixel(x, y, f)
            Pix.bitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(o, 0, w, 0, 0, w, h) }
        }
        waterFrames = frames
        return frames
    }

    /** Frees the water frames (about 2.5 MB on the longest hole); they rebuild when next needed. */
    fun releaseWater() { waterFrames = null }

    /** Builds the water frames now (call off the main thread for the next hole). */
    fun prepareWater() { water(0) }

    /** Visual land mask per pixel, after smoothing. */
    private val land = BooleanArray(w * h)
    /** Bank face (0 none, 1..BANK_H rows down from the shore) on water pixels below land. */
    private val bank = ByteArray(w * h)
    /** Ground kind per land pixel after smoothing the tile classes. */
    private val kind = ByteArray(w * h)
    /** Land pixels: distance to water. Water pixels: distance to land or bank. */
    private val edge = FloatArray(w * h)

    init {
        computeLand()
        computeKinds()
        computeBanks()
        computeEdges()
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = basePixel(x, y)
        decorate(px)
        castShadows(px)
        ground = Pix.bitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun tileAt(x: Int, y: Int): Tile = hole.tile(Math.floorDiv(x, TILE), Math.floorDiv(y, TILE))

    /** Box-blurs a per-tile predicate over pixels (window 2r×2r) into 0..1. */
    private fun blur(r: Int, pred: (Tile) -> Boolean): FloatArray {
        val iw = w + 2 * r + 1
        val ih = h + 2 * r + 1
        val sum = IntArray(iw * ih)
        for (y in 1 until ih) {
            var row = 0
            for (x in 1 until iw) {
                if (pred(tileAt(x - 1 - r, y - 1 - r))) row++
                sum[y * iw + x] = sum[(y - 1) * iw + x] + row
            }
        }
        val area = ((2 * r) * (2 * r)).toFloat()
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x + 1
            val y0 = y + 1
            val x1 = x + 2 * r + 1
            val y1 = y + 2 * r + 1
            out[y * w + x] = (sum[y1 * iw + x1] - sum[y0 * iw + x1] - sum[y1 * iw + x0] + sum[y0 * iw + x0]) / area
        }
        return out
    }

    private fun computeLand() {
        System.arraycopy(hole.coast.land, 0, land, 0, land.size)
    }

    private fun computeKinds() {
        val sand = blur(3) { it == Tile.SAND }
        val green = blur(3) { it.isGreen }
        val fair = blur(3) { it == Tile.FAIRWAY }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val t = tileAt(x, y)
            kind[i] = when {
                !land[i] -> K_WATER
                t == Tile.WALL -> K_WALL
                t == Tile.WOOD -> K_WOOD
                sand[i] > 0.5f -> K_SAND
                green[i] > 0.5f -> K_GREEN
                fair[i] > 0.5f -> K_FAIR
                else -> K_ROUGH
            }
        }
    }

    private fun computeBanks() {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (land[i]) continue
            for (k in 1..BANK_H) {
                if (y - k >= 0 && land[(y - k) * w + x]) { bank[i] = k.toByte(); break }
            }
        }
    }

    /** Two-pass chamfer distance from every pixel where [isSource] holds. */
    private fun distanceFrom(isSource: (Int) -> Boolean): FloatArray {
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

    private fun computeEdges() {
        val toWater = distanceFrom { !land[it] }
        val toShore = distanceFrom { land[it] || bank[it] > 0 }
        for (i in 0 until w * h) edge[i] = if (land[i]) toWater[i] - 0.5f else toShore[i] - 0.5f
    }

    private fun k(x: Int, y: Int): Byte = if (x < 0 || y < 0 || x >= w || y >= h) K_WATER else kind[y * w + x]

    private fun basePixel(x: Int, y: Int): Int {
        val i = y * w + x
        val tx = x / TILE
        val ty = y / TILE
        val u = x % TILE
        val v = y % TILE
        val kd = kind[i]
        val d = edge[i]
        val b = bayer(x, y) / 16f

        if (kd == K_WATER) {
            when (bank[i].toInt()) { // the island's bank face
                1 -> return Palette.BANK
                2 -> return Palette.BANK_DARK
                3 -> return Palette.BANK_DARKER
            }
            // Clean depth bands; the first row of each deeper band keeps the lighter colour on
            // alternate pixels, a contour rather than a dither field.
            var c = when {
                d < 3f -> Palette.SHOAL
                d < 7f -> Palette.SHALLOW
                d < 14f -> Palette.MID
                else -> Palette.DEEP
            }
            if ((d in 3f..4f || d in 7f..8f || d in 14f..15f) && (x + y) % 2 == 0) c = Palette.lighter(c)
            // The bank shades the water at its foot.
            if (y > 0 && bank[i - w] > 0) c = Palette.darker(c)
            else if (y > 1 && bank[i - 2 * w] > 0 && (x + y) % 2 == 0) c = Palette.darker(c)
            return c
        }
        if (kd == K_WALL) return stone(tx, ty, u, v, x)
        if (kd == K_WOOD) return wood(tx, ty, u, v)

        // Beaches where land meets the sea.
        if (d < 3.4f + hash(x / 3, y / 3, 3)) return if (d < 1.5f) Palette.SAND_LIGHT else Palette.SAND

        return when (kd) {
            K_FAIR -> if (ty % 2 == 0) Palette.FAIR_A else Palette.FAIR_B
            K_ROUGH -> Palette.ROUGH
            K_SAND -> {
                val lip = k(x - 1, y) != K_SAND || k(x + 1, y) != K_SAND || k(x, y - 1) != K_SAND || k(x, y + 1) != K_SAND
                when {
                    lip -> Palette.SAND_DARK
                    (x + 2 * y + (hash(x / 6, y / 6, 4) * 3).toInt()) % 7 == 0 -> Palette.SAND_DARK // wind ripples
                    else -> Palette.SAND
                }
            }
            K_GREEN -> {
                val t = tileAt(x, y)
                var g = if (((x / 8) + (y / 8)) % 2 == 0) Palette.GREEN_A else Palette.GREEN_B
                if (t.slopeX != 0f || t.slopeY != 0f) {
                    // Grain streaks running downhill (the glints show which way).
                    val along = if (t.slopeX != 0f) x else y
                    val across = if (t.slopeX != 0f) y else x
                    g = Palette.GREEN_B
                    if (across % 4 == 1 && (along + across * 3) % 6 < 3) g = Palette.SLOPE_DARK
                }
                val collar = k(x - 1, y) != K_GREEN || k(x + 1, y) != K_GREEN || k(x, y - 1) != K_GREEN || k(x, y + 1) != K_GREEN
                if (collar) Palette.COLLAR else g
            }
            else -> Palette.ROUGH
        }
    }

    /** Clusters on a jittered grid: tufts and flowers in the rough, daisies, shells and pebbles. */
    private fun decorate(px: IntArray) {
        fun put(x: Int, y: Int, c: Int, onlyKind: Byte) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            if (kind[y * w + x] != onlyKind || edge[y * w + x] < 3.6f) return
            px[y * w + x] = c
        }
        var gy = 0
        while (gy < h) {
            var gx = 0
            while (gx < w) {
                val x = gx + (hash(gx, gy, 1) * 4).toInt()
                val y = gy + (hash(gx, gy, 2) * 4).toInt()
                val r = hash(gx, gy, 3)
                if (x in 1 until w - 2 && y in 1 until h) {
                    when (kind[y * w + x]) {
                        K_ROUGH -> if (r < 0.5f) { // a tuft
                            put(x, y, Palette.ROUGH_DARK, K_ROUGH); put(x + 1, y, Palette.ROUGH_DARK, K_ROUGH)
                            put(x + 2, y, Palette.ROUGH_DARK, K_ROUGH); put(x + 1, y - 1, Palette.ROUGH_LIGHT, K_ROUGH)
                        } else if (r > 0.93f) { // a flower
                            put(x, y, if (hash(gx, gy, 5) < 0.5f) Palette.FLOWER_Y else Palette.FLOWER_P, K_ROUGH)
                            put(x, y + 1, Palette.ROUGH_DARK, K_ROUGH)
                        }
                        K_FAIR -> if (r < 0.035f) {
                            put(x, y, Palette.FLOWER_W, K_FAIR); put(x, y + 1, Palette.FAIR_DOT, K_FAIR)
                        }
                        else -> {}
                    }
                }
                gx += 5
            }
            gy += 5
        }
        // Shells and pebbles on the beaches.
        gy = 0
        while (gy < h) {
            var gx = 0
            while (gx < w) {
                val x = gx + (hash(gx, gy, 11) * 6).toInt()
                val y = gy + (hash(gx, gy, 12) * 6).toInt()
                if (x in 0 until w - 1 && y in 0 until h) {
                    val i = y * w + x
                    val d = edge[i]
                    if (land[i] && kind[i] != K_WALL && kind[i] != K_WOOD && d in 1.2f..3.2f) {
                        val r = hash(gx, gy, 13)
                        when {
                            r < 0.18f -> { px[i] = Palette.SHELL; px[i + 1] = Palette.SHELL_PINK }
                            r < 0.28f -> px[i] = Palette.PEBBLE
                        }
                    }
                }
                gx += 7
            }
            gy += 7
        }
    }

    /** Walls and logs cast their footprint (2, 2) to the bottom right, one ramp step down. */
    private fun castShadows(px: IntArray) {
        fun solid(x: Int, y: Int) = tileAt(x, y).solid
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!land[i] || solid(x, y)) continue
            if (!solid(x - 2, y - 2)) continue
            val rim = !(solid(x - 3, y - 3) && solid(x - 3, y - 2) && solid(x - 2, y - 3))
            if (!rim || (x + y) % 2 == 0) px[i] = Palette.darker(px[i])
        }
    }

    /** Stone in 3/4 view: a light top face, and a front face with mortar above open ground. */
    private fun stone(tx: Int, ty: Int, u: Int, v: Int, x: Int): Int {
        val up = hole.tile(tx, ty - 1) == Tile.WALL
        val down = hole.tile(tx, ty + 1) == Tile.WALL
        val left = hole.tile(tx - 1, ty) == Tile.WALL
        val right = hole.tile(tx + 1, ty) == Tile.WALL
        val corner = (!up && !left && u == 0 && v == 0) || (!up && !right && u == 7 && v == 0) ||
            (!down && !left && u == 0 && v == 7) || (!down && !right && u == 7 && v == 7)
        if (corner) return groundBehind(tx, ty, u, v)
        if (!down && v >= FACE_TOP) {
            return when {
                v == 7 -> Palette.STONE_DARKER
                (x + (if (v == 6) 3 else 0)) % 6 == 0 -> Palette.STONE_DARK
                !right && u == 7 -> Palette.STONE_DARK
                else -> Palette.STONE
            }
        }
        return when {
            !up && v == 0 -> Palette.STONE_LIGHT
            !left && u == 0 -> Palette.STONE_LIGHT
            !right && u == 7 -> Palette.STONE
            !down && v == FACE_TOP - 1 -> Palette.STONE
            left && u == 0 && v % 4 != 3 -> Palette.STONE // cap-stone seams
            up && v == 0 -> Palette.STONE
            else -> Palette.STONE_TOP
        }
    }

    /** Driftwood: logs run along their neighbours, round in section, with pale cut ends. */
    private fun wood(tx: Int, ty: Int, u0: Int, v0: Int): Int {
        val l = hole.tile(tx - 1, ty) == Tile.WOOD
        val r = hole.tile(tx + 1, ty) == Tile.WOOD
        val up = hole.tile(tx, ty - 1) == Tile.WOOD
        val dn = hole.tile(tx, ty + 1) == Tile.WOOD
        val horizontal = l || r || !(up || dn)
        val a = if (horizontal) u0 else v0 // along the log
        val b = if (horizontal) v0 else u0 // across (0 = lit side)
        val startOpen = if (horizontal) !l else !up
        val endOpen = if (horizontal) !r else !dn
        if ((startOpen && a == 0 || endOpen && a == 7) && (b <= 1 || b >= 6)) return groundBehind(tx, ty, u0, v0)
        if (startOpen && a <= 1 || endOpen && a >= 6) {
            val rim = b == 0 || b == 7 || a == 0 || a == 7
            return when {
                rim -> Palette.WOOD_DARK
                (b == 3 || b == 4) && (a == 1 || a == 6) -> Palette.WOOD_LIGHT
                else -> Palette.WOOD_PALE
            }
        }
        val g = hash(tx * 8 + a, ty * 8 + b, 51)
        return when (b) {
            0 -> Palette.WOOD_DARK
            1 -> if (g > 0.35f) Palette.WOOD_PALE else Palette.WOOD_LIGHT
            2 -> Palette.WOOD_LIGHT
            3, 4 -> if ((a + b * 3) % 5 == 0) Palette.WOOD_DARK else Palette.WOOD
            5 -> Palette.WOOD
            6 -> Palette.WOOD_DARK
            else -> Palette.WOOD_DARKER
        }
    }

    /** What shows through a rounded corner: the neighbouring ground, roughly. */
    private fun groundBehind(tx: Int, ty: Int, u: Int, v: Int): Int {
        val ox = if (u < 4) -1 else 1
        val oy = if (v < 4) -1 else 1
        val t = sequenceOf(hole.tile(tx + ox, ty), hole.tile(tx, ty + oy), hole.tile(tx + ox, ty + oy))
            .firstOrNull { !it.solid } ?: Tile.ROUGH
        return when {
            t.water -> Palette.SHOAL
            t.isGreen -> Palette.GREEN_B
            t == Tile.SAND -> Palette.SAND
            t == Tile.FAIRWAY -> Palette.FAIR_B
            else -> Palette.ROUGH
        }
    }

    /**
     * The sea's moving layer (W1 "Crests"):
     * - A shore wash runs up the beach and back, leaving wet sand as it pulls away.
     * - Short wave crests travel across open water.
     * Everything else is transparent over the static bands in [ground].
     */
    private fun waterPixel(x: Int, y: Int, f: Int): Int {
        val i = y * w + x
        val d = edge[i]
        val phase = 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * f / FRAMES)
        if (land[i]) {
            // Wet sand where the wash just pulled back (not on a cliff top above a bank).
            val cliff = y + 1 < h && bank[i + w] > 0
            val beach = kind[i] != K_WALL && kind[i] != K_WOOD
            return if (beach && !cliff && d < 0.8f + 1.6f * (1f - phase)) Palette.alpha(Palette.SANDS[2], 150) else 0
        }
        if (bank[i] > 0) return 0
        val reach = 0.6f + 2.2f * phase
        if (d < reach) return if (d > reach - 1.1f) Palette.FOAM else Palette.lerp(Palette.SHOAL, Palette.FOAM, 0.5f)
        if (d > 9f) {
            when (crest(x, y, f)) {
                1 -> return Palette.SHOAL
                2 -> return Palette.SEA[0]
            }
        }
        return 0
    }

    /**
     * How far the drawn ground strays from the physics tiles, as the deepest stray pixel
     * (px from the nearest tile of the drawn kind):
     * [0] drawn land on a water tile, [1] drawn water on a land tile,
     * [2] sand drawn off or missing from sand tiles, [3] green likewise.
     * Bank faces are left out: they're a cliff drawn over water on purpose.
     */
    fun mismatch(): FloatArray {
        val out = FloatArray(4)
        fun depth(x: Int, y: Int, want: (Tile) -> Boolean): Float {
            val tx = x / TILE
            val ty = y / TILE
            var best = 99f
            for (dy in -1..1) for (dx in -1..1) {
                if (!want(hole.tile(tx + dx, ty + dy))) continue
                val l = (tx + dx) * TILE.toFloat()
                val t = (ty + dy) * TILE.toFloat()
                val ex = maxOf(l - (x + 0.5f), 0f, (x + 0.5f) - (l + TILE))
                val ey = maxOf(t - (y + 0.5f), 0f, (y + 0.5f) - (t + TILE))
                best = minOf(best, sqrt(ex * ex + ey * ey))
            }
            return best
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val t = tileAt(x, y)
            val kd = kind[i]
            if (land[i] && t.water) out[0] = maxOf(out[0], depth(x, y) { !it.water })
            if (!land[i] && !t.water) out[1] = maxOf(out[1], depth(x, y) { it.water })
            if (land[i] && !t.solid) {
                if ((kd == K_SAND) != (t == Tile.SAND)) out[2] = maxOf(out[2], depth(x, y) { (it == Tile.SAND) == (kd == K_SAND) })
                if ((kd == K_GREEN) != t.isGreen) out[3] = maxOf(out[3], depth(x, y) { it.isGreen == (kd == K_GREEN) })
            }
        }
        return out
    }

    /** For art experiments in tests: the land mask, bank rows and edge distances. */
    internal fun masks(): Triple<BooleanArray, ByteArray, FloatArray> = Triple(land, bank, edge)

    /** A small nearest-sampled copy of the ground for hole cards: every [step]th pixel. */
    fun thumbnail(step: Int): Bitmap {
        val tw = w / step
        val th = h / step
        val px = IntArray(tw * th)
        val src = IntArray(w * h)
        ground.getPixels(src, 0, w, 0, 0, w, h)
        for (y in 0 until th) for (x in 0 until tw) px[y * tw + x] = src[(y * step + step / 2) * w + x * step + step / 2]
        return Pix.bitmap(tw, th, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, tw, 0, 0, tw, th) }
    }

    companion object {
        const val FRAMES = 8

        /** Size of the tiling sea beyond the map: whole crest cells, so it repeats seamlessly. */
        const val SEA_W = 78
        const val SEA_H = 72

        /**
         * Travelling wave crests on open water: a short light dash (1) with a dark trough under
         * it (2), on a jittered grid that wraps every [SEA_W]×[SEA_H] so the sea can tile.
         */
        fun crest(x: Int, y: Int, f: Int): Int {
            val cx = x - f
            val gx = Math.floorMod(Math.floorDiv(cx, 13), 6)
            val gy = Math.floorMod(Math.floorDiv(y, 9), 8)
            if (hash(gx, gy, 74) > 0.5f) return 0 // only about half the cells carry a crest
            val ox = Math.floorDiv(cx, 13) * 13
            val oy = Math.floorDiv(y, 9) * 9
            val jx = ox + (hash(gx, gy, 71) * 8).toInt()
            val jy = oy + (hash(gx, gy, 72) * 6).toInt()
            val life = (f + (hash(gx, gy, 73) * 8).toInt()) % 8
            val len = intArrayOf(0, 2, 3, 4, 4, 3, 2, 0)[life]
            if (len == 0 || cx < jx || cx >= jx + len) return 0
            return if (y == jy) 1 else if (y == jy + 1) 2 else 0
        }

        /** The open sea beyond the map, [FRAMES] tiles of [SEA_W]×[SEA_H]. */
        val sea: Array<Bitmap> by lazy {
            Array(FRAMES) { f ->
                val px = IntArray(SEA_W * SEA_H)
                for (y in 0 until SEA_H) for (x in 0 until SEA_W) {
                    px[y * SEA_W + x] = when (crest(x, y, f)) {
                        1 -> Palette.SHOAL
                        2 -> Palette.SEA[0]
                        else -> Palette.DEEP
                    }
                }
                Pix.bitmap(SEA_W, SEA_H).apply { setPixels(px, 0, SEA_W, 0, 0, SEA_W, SEA_H) }
            }
        }
        const val BANK_H = 3
        /** First row of a wall tile's front face (rows FACE_TOP..7). */
        const val FACE_TOP = 5

        private const val K_WATER: Byte = 0
        private const val K_ROUGH: Byte = 1
        private const val K_FAIR: Byte = 2
        private const val K_GREEN: Byte = 3
        private const val K_SAND: Byte = 4
        private const val K_WALL: Byte = 5
        private const val K_WOOD: Byte = 6

        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        fun bayer(x: Int, y: Int): Int = BAYER[(y and 3) * 4 + (x and 3)]

        fun hash(x: Int, y: Int, s: Int): Float = com.theprobyte.golfalone.game.Noise.hash(x, y, s)
    }
}

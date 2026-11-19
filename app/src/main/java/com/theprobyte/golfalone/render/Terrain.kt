package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.Rails
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
    /** Inland water (not joined to the open sea), drawn as calm ponds. */
    private val pond = BooleanArray(w * h)
    /** Land within a few pixels of a pond (reeds, no surf). */
    private val nearPond = BooleanArray(w * h)
    private val rails = hole.rails

    init {
        computeLand()
        computeKinds()
        computeBanks()
        computeEdges()
        computePonds()
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

    /** Which rail kind covers this pixel, or -1. */
    private fun railAt(x: Int, y: Int): Int {
        val px = x + 0.5f
        val py = y + 0.5f
        var best = -1
        for (i in rails.near(x / TILE, y / TILE)) {
            if (rails.distance(i, px, py) <= rails.r[i]) {
                if (best < 0 || rails.kind[i] == Rails.WOOD) best = rails.kind[i].toInt()
            }
        }
        return best
    }

    private fun computeKinds() {
        val sand = blur(3) { it == Tile.SAND }
        val green = blur(3) { it.isGreen }
        val fair = blur(3) { it == Tile.FAIRWAY }
        // Stone: wall tiles (not boulders) plus the rails across diagonal gaps.
        val stoneMask = BooleanArray(w * h)
        val rail = IntArray(w * h) { -1 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val tx = x / TILE
            val ty = y / TILE
            rail[i] = railAt(x, y)
            stoneMask[i] = (hole.tile(tx, ty) == Tile.WALL && !rails.isBoulder(tx, ty)) || rail[i] == Rails.STONE.toInt()
        }
        // Round off single-pixel outer corners.
        val rounded = stoneMask.copyOf()
        fun st(x: Int, y: Int) = x in 0 until w && y in 0 until h && stoneMask[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            if (!stoneMask[y * w + x]) continue
            val l = st(x - 1, y); val r = st(x + 1, y); val u = st(x, y - 1); val d = st(x, y + 1)
            if ((!l && !u) || (!r && !u) || (!l && !d) || (!r && !d)) rounded[y * w + x] = false
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            kind[i] = when {
                !land[i] -> K_WATER
                rail[i] == Rails.WOOD.toInt() -> K_WOOD
                rail[i] == Rails.BOULDER.toInt() -> K_ROCK
                rounded[i] -> K_WALL
                hole.tile(x / TILE, y / TILE).isRamp -> K_RAMP
                sand[i] > 0.5f -> K_SAND
                green[i] > 0.5f -> K_GREEN
                fair[i] > 0.5f -> K_FAIR
                else -> K_ROUGH
            }
        }
    }

    /** Inland water: water pixels the open sea can't reach. */
    private fun computePonds() {
        val sea = BooleanArray(w * h)
        val q = ArrayDeque<Int>()
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) { val i = y * w + x; if (!land[i] && !sea[i]) { sea[i] = true; q.add(i) } }
        for (y in 0 until h) for (x in intArrayOf(0, w - 1)) { val i = y * w + x; if (!land[i] && !sea[i]) { sea[i] = true; q.add(i) } }
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val x = i % w
            val y = i / w
            for ((nx, ny) in arrayOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (!land[j] && !sea[j]) { sea[j] = true; q.add(j) }
            }
        }
        for (i in 0 until w * h) pond[i] = !land[i] && !sea[i]
        val toPond = distanceFrom { pond[it] }
        for (i in 0 until w * h) nearPond[i] = land[i] && toPond[i] < 5f
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
            when (bank[i].toInt()) { // the island's bank face, in strata, with grass hanging over the lip
                1 -> return if (hash(x, y, 81) < 0.22f) Palette.ROUGH_DARK else Palette.BANK
                2 -> return if ((x * 7 + y) % 11 == 0) Palette.BANK else Palette.BANK_DARK
                3 -> return Palette.BANK_DARKER
            }
            if (pond[i]) {
                // Calm inland water: a pale rim, then a still, greener depth.
                var c = when {
                    d < 1.2f -> Palette.POND[4]
                    d < 3f -> Palette.POND[3]
                    d < 6f -> Palette.POND[2]
                    else -> Palette.POND[1]
                }
                if ((d in 3f..4f || d in 6f..7f) && (x + y) % 2 == 0) c = Palette.lighter(c)
                if (y > 0 && bank[i - w] > 0) c = Palette.darker(c)
                return c
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
        if (kd == K_WALL) return stone(x, y)
        if (kd == K_WOOD) return wood(x, y)
        if (kd == K_ROCK) return rock(x, y)
        if (kd == K_RAMP) return ramp(x, y)

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
                        } else if (r > 0.93f) { // a little patch of flowers
                            val fc = if (hash(gx, gy, 5) < 0.5f) Palette.FLOWER_Y else Palette.FLOWER_P
                            put(x, y, fc, K_ROUGH); put(x, y + 1, Palette.ROUGH_DARK, K_ROUGH)
                            put(x + 2, y + 1, fc, K_ROUGH); put(x + 1, y + 2, Palette.FLOWER_W, K_ROUGH)
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
        // Low bushes deep in the rough: scenery, so no outline and no shadow (R10).
        gy = 0
        while (gy < h) {
            var gx = 0
            while (gx < w) {
                val x = gx + (hash(gx, gy, 21) * 8).toInt()
                val y = gy + (hash(gx, gy, 22) * 8).toInt()
                if (hash(gx, gy, 23) < 0.3f && x in 0 until w - 4 && y in 0 until h - 3) {
                    var ok = true
                    for (yy in y until y + 3) for (xx in x until x + 4) {
                        val i = yy * w + xx
                        if (kind[i] != K_ROUGH || edge[i] < 4f) ok = false
                    }
                    if (ok) {
                        val bush = arrayOf(".mm.", "mllm", "dmmd")
                        for (yy in 0..2) for (xx in 0..3) {
                            val ch = bush[yy][xx]
                            if (ch != '.') px[(y + yy) * w + x + xx] = when (ch) { 'l' -> Palette.LEAF[3]; 'm' -> Palette.LEAF[2]; else -> Palette.LEAF[1] }
                        }
                    }
                }
                gx += 13
            }
            gy += 13
        }
        // Lily pads on ponds, reeds round their edges.
        gy = 0
        while (gy < h) {
            var gx = 0
            while (gx < w) {
                val x = gx + (hash(gx, gy, 31) * 6).toInt()
                val y = gy + (hash(gx, gy, 32) * 6).toInt()
                if (x in 1 until w - 3 && y in 1 until h - 2) {
                    val i = y * w + x
                    if (pond[i] && edge[i] >= 3f && hash(gx, gy, 33) < 0.4f && pond[i + 2] && pond[i + w + 2]) {
                        px[i] = Palette.LEAF[3]; px[i + 1] = Palette.LEAF[3]
                        px[i + w] = Palette.LEAF[2]; px[i + w + 1] = Palette.LEAF[2]; px[i + w + 2] = Palette.LEAF[3]
                        if (hash(gx, gy, 34) < 0.3f) px[i + 1] = Palette.FLOWER_P
                    }
                }
                gx += 9
            }
            gy += 9
        }
        gy = 0
        while (gy < h) {
            var gx = 0
            while (gx < w) {
                val x = gx + (hash(gx, gy, 41) * 3).toInt()
                val y = gy + (hash(gx, gy, 42) * 3).toInt()
                if (x in 0 until w && y in 4 until h) {
                    val i = y * w + x
                    if (nearPond[i] && edge[i] < 2.5f && kind[i] != K_WALL && kind[i] != K_WOOD && hash(gx, gy, 43) < 0.55f) {
                        for (k in 0..2) px[i - k * w] = if (k == 0) Palette.LEAF[1] else Palette.LEAF[2]
                        if (hash(gx, gy, 44) < 0.5f) px[i - 3 * w] = Palette.WOODS[1] // a cattail
                    }
                }
                gx += 4
            }
            gy += 4
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
                            r < 0.34f && !nearPond[i] && x in 1 until w - 1 && y in 1 until h - 1 -> { // a starfish
                                px[i] = Palette.STARFISH_DARK
                                px[i - 1] = Palette.STARFISH; px[i + 1] = Palette.STARFISH
                                px[i - w] = Palette.STARFISH; px[i + w] = Palette.STARFISH
                            }
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
        fun solid(x: Int, y: Int): Boolean {
            val kd = k(x, y)
            return kd == K_WALL || kd == K_WOOD || kd == K_ROCK
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!land[i] || solid(x, y)) continue
            if (!solid(x - 2, y - 2)) continue
            val rim = !(solid(x - 3, y - 3) && solid(x - 3, y - 2) && solid(x - 2, y - 3))
            if (!rim || (x + y) % 2 == 0) px[i] = Palette.darker(px[i])
        }
    }

    private fun isStone(x: Int, y: Int) = k(x, y) == K_WALL

    /**
     * Stone in 3/4 view, shaded from the pixel mask so diagonal runs join up: a light top
     * face, and a 3 px front face with mortar wherever open ground lies below.
     */
    private fun stone(x: Int, y: Int): Int {
        val tx = x / TILE
        val ty = y / TILE
        val u = x % TILE
        val v = y % TILE
        val inTile = hole.tile(tx, ty) == Tile.WALL
        return when {
            !isStone(x, y + 1) -> Palette.STONE_DARKER
            !isStone(x, y + 2) || !isStone(x, y + 3) -> if ((x + (y % 2) * 3) % 6 == 0) Palette.STONE_DARK else Palette.STONE
            !isStone(x, y + 4) -> Palette.STONE
            !isStone(x, y - 1) || !isStone(x - 1, y) -> Palette.STONE_LIGHT
            !isStone(x + 1, y) -> Palette.STONE
            inTile && u == 0 && hole.tile(tx - 1, ty) == Tile.WALL && v % 4 != 3 -> Palette.STONE // cap-stone seams
            inTile && v == 0 && hole.tile(tx, ty - 1) == Tile.WALL -> Palette.STONE
            else -> Palette.STONE_TOP
        }
    }

    /**
     * Bleached driftwood from the rails: round in section, lit from the top left, with
     * grain along the log and pale cut faces where a log ends.
     */
    private fun wood(x: Int, y: Int): Int {
        val px = x + 0.5f
        val py = y + 0.5f
        var best = -1
        var bestD = 99f
        for (i in rails.near(x / TILE, y / TILE)) {
            if (rails.kind[i] != Rails.WOOD) continue
            val d = rails.distance(i, px, py)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return Palette.DRIFT[2]
        val i = best
        val r = rails.r[i]
        val ax = rails.x0[i]; val ay = rails.y0[i]
        var dx = rails.x1[i] - ax; var dy = rails.y1[i] - ay
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-3f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
        val raw = ((px - ax) * dx + (py - ay) * dy) / maxOf(len, 1e-3f)
        // A cut face where the log ends: rings round a pale heart.
        if ((raw < 0f && rails.openA[i]) || (raw > 1f && rails.openB[i])) {
            return when {
                bestD > r - 1f -> Palette.DRIFT[1]
                bestD > r * 0.45f && bestD < r * 0.75f -> Palette.DRIFT[3]
                else -> Palette.DRIFT[4]
            }
        }
        // Across the log: +1 faces the light (up and to the left).
        var nx = -dy
        var ny = dx
        if (nx * -0.6f + ny * -0.8f < 0f) { nx = -nx; ny = -ny }
        val t = rails.along(i, px, py)
        val cx = ax + (rails.x1[i] - ax) * t
        val cy = ay + (rails.y1[i] - ay) * t
        val b = ((px - cx) * nx + (py - cy) * ny) / r
        val alongPx = (raw * len).toInt()
        return when {
            b > 0.7f -> Palette.DRIFT[2]
            b > 0.3f -> if (hash(alongPx, i, 52) > 0.35f) Palette.DRIFT[4] else Palette.DRIFT[3]
            b > -0.3f -> if (b in -0.15f..0.15f && (alongPx + i) % 7 < 2) Palette.DRIFT[1] else Palette.DRIFT[3]
            b > -0.7f -> Palette.DRIFT[2]
            else -> Palette.DRIFT[0]
        }
    }

    /**
     * A wooden ramp in 3/4 view: planks across the climb, lighter towards the high lip, dark
     * side rails, and a chevron pointing the way it launches.
     */
    private fun ramp(x: Int, y: Int): Int {
        val t = tileAt(x, y)
        val u = x % TILE
        val v = y % TILE
        val a = when { t.rampY < 0 -> 7 - v; t.rampY > 0 -> v; t.rampX > 0 -> u; else -> 7 - u } // up the ramp
        val b = if (t.rampY != 0f) u else v // across
        val chevron = a in 2..5 && kotlin.math.abs(b * 2 - 7) == (5 - a) * 2 - 1
        return when {
            b == 0 || b == 7 -> Palette.WOODS[1]
            a == 7 -> Palette.WOODS[4]
            chevron -> Palette.WOODS[4]
            a % 2 == 1 -> Palette.WOODS[1 + (a + 1) / 3]
            else -> Palette.WOODS[2 + a / 4]
        }
    }

    /** A boulder: a round stone lit from the top left, outlined on its shaded side. */
    private fun rock(x: Int, y: Int): Int {
        val cx = (x / TILE + 0.5f) * TILE
        val cy = (y / TILE + 0.5f) * TILE
        val dx = (x + 0.5f - cx) / Rails.BOULDER_R
        val dy = (y + 0.5f - cy) / Rails.BOULDER_R
        val light = -(dx * 0.6f + dy * 0.8f)
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        return when {
            dist > 0.78f && (dx + dy) > 0f -> Palette.STONE_DARKER
            light > 0.45f -> Palette.STONE_LIGHT
            light > 0f -> Palette.STONE_TOP
            light > -0.45f -> Palette.STONE
            else -> Palette.STONE_DARK
        }
    }

    private fun waterPixel(x: Int, y: Int, f: Int): Int {
        val i = y * w + x
        val d = edge[i]
        val phase = 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * f / FRAMES)
        if (land[i] && nearPond[i]) return 0
        if (pond[i]) {
            // Ponds: a slow, faint ring moving out from the bank.
            val r = 1.5f + (f % FRAMES) * 0.6f
            return if (d >= r && d < r + 1f && (x + y + f) % 3 == 0) Palette.alpha(Palette.POND[4], 110) else 0
        }
        if (land[i]) {
            // Wet sand where the wash just pulled back (not on a cliff top above a bank).
            val cliff = y + 1 < h && bank[i + w] > 0
            val beach = kind[i] != K_WALL && kind[i] != K_WOOD
            return if (beach && !cliff && d < 0.8f + 1.6f * (1f - phase)) Palette.alpha(Palette.SANDS[2], 150) else 0
        }
        if (bank[i] > 0) return 0
        val reach = 0.6f + 2.2f * phase
        if (d < reach) return if (d > reach - 1.1f) Palette.FOAM else Palette.lerp(Palette.SHOAL, Palette.FOAM, 0.5f)
        if (d > 14f) {
            when (crest(x, y, f)) {
                1 -> return Palette.MID
                2 -> return Palette.SHALLOW
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
            // Each crest drifts left by its own life stage, so the loop never makes one jump.
            val gy0 = Math.floorDiv(y, 9)
            val gy = Math.floorMod(gy0, 8)
            val home = Math.floorDiv(x, 13)
            for (cell in home..home + 1) {
                val gx = Math.floorMod(cell, 6)
                if (hash(gx, gy, 74) > 0.5f) continue // only about half the cells carry a crest
                val life = (f + (hash(gx, gy, 73) * 8).toInt()) % 8
                val len = intArrayOf(0, 2, 3, 4, 4, 3, 2, 0)[life]
                if (len == 0) continue
                val jx = cell * 13 + (hash(gx, gy, 71) * 8).toInt() - life
                val jy = gy0 * 9 + (hash(gx, gy, 72) * 6).toInt()
                if (x >= jx && x < jx + len && y == jy) {
                    return if (life in 3..4 && hash(gx, gy, 75) < 0.2f) 2 else 1
                }
            }
            return 0
        }

        /** The open sea beyond the map, [FRAMES] tiles of [SEA_W]×[SEA_H]. */
        val sea: Array<Bitmap> by lazy {
            Array(FRAMES) { f ->
                val px = IntArray(SEA_W * SEA_H)
                for (y in 0 until SEA_H) for (x in 0 until SEA_W) {
                    px[y * SEA_W + x] = when (crest(x, y, f)) {
                        1 -> Palette.MID
                        2 -> Palette.SHALLOW
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
        private const val K_ROCK: Byte = 7
        private const val K_RAMP: Byte = 8

        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        fun bayer(x: Int, y: Int): Int = BAYER[(y and 3) * 4 + (x and 3)]

        fun hash(x: Int, y: Int, s: Int): Float = com.theprobyte.golfalone.game.Noise.hash(x, y, s)
    }
}

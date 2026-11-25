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
class Terrain(private val hole: Hole, private val high: Boolean = false) {

    private val bornNs = System.nanoTime()
    val w = hole.pxW
    val h = hole.pxH
    val ground: Bitmap
    /** On a tidal hole, the same map at high tide (tidal sand under water). */
    val flooded: Terrain? = if (hole.tidal && !high) Terrain(hole, true) else null

    /** The map for this state of the tide. */
    fun at(highTide: Boolean): Terrain = if (highTide) flooded ?: this else this

    /**
     * Warnings drawn over tidal sand: [floodSoon] a wet sheen (it goes under after this shot),
     * [drainSoon] sand showing through (it comes back after this shot).
     */
    val floodSoon: Bitmap? by lazy { if (flooded == null) null else tideMask(Palette.alpha(Palette.SEA[4], 200), 3) }
    val drainSoon: Bitmap? by lazy { if (flooded == null) null else tideMask(Palette.alpha(Palette.SANDS[3], 170), 4) }

    private fun tideMask(col: Int, every: Int): Bitmap {
        val dry = hole.coast.land
        val wet = hole.floodedCoast.land
        val o = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (dry[i] && !wet[i] && (x + 2 * y) % every == 0 && (y % 2 == 0)) o[i] = col
        }
        return Pix.bitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(o, 0, w, 0, 0, w, h) }
    }
    @Volatile private var waterFrames: Array<Bitmap>? = null

    /** The sea's moving layer, [FRAMES] frames, built on first use (see [releaseWater]). */
    fun water(f: Int): Bitmap = (waterFrames ?: buildWater())[f % FRAMES]

    /** The water frame if it's built (this map's and its high-tide twin's), else null: never builds. */
    fun waterOrNull(f: Int): Bitmap? = waterFrames?.get(f % FRAMES)

    /** Whether [prepareWater] has nothing left to do. */
    val waterReady: Boolean get() = waterFrames != null && (flooded?.waterReady ?: true)

    @Synchronized private fun buildWater(): Array<Bitmap> {
        waterFrames?.let { return it }
        val t0 = System.nanoTime()
        val frames = Array(FRAMES) { f ->
            val o = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) o[y * w + x] = waterPixel(x, y, f)
            Pix.bitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(o, 0, w, 0, 0, w, h) }
        }
        waterMs = (System.nanoTime() - t0) / 1e6f
        waterThread = Thread.currentThread().name
        waterFrames = frames
        return frames
    }

    /** How long this map took to build, with its high-tide twin (ms), for the frame overlay. */
    val bakeMs: Float
    /** How long the water frames last took to build (ms), and on which thread. */
    @Volatile var waterMs = 0f
        private set
    @Volatile var waterThread = ""
        private set

    /** Frees the water frames (about 2.5 MB on the longest hole); they rebuild when next needed. */
    fun releaseWater() { waterFrames = null; flooded?.releaseWater() }

    /** Builds the water frames now (call off the main thread for the next hole). */
    fun prepareWater() { water(0); flooded?.prepareWater() }

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
    /** The sun path's centre line, through the middle of the map. */
    private val sunLine = (w * 0.6f + h * 0.8f) / 2f
    /** How much fairway is near each pixel (0..1), for the first cut where rough meets it. */
    private var fairNear = FloatArray(0)
    /** Each hole mows its own way: straight bands, or diagonal ones (either way). */
    private val mow = (Terrain.hash(hole.w, hole.h, hole.name.hashCode()) * 3).toInt()

    /** Which water pixels are lava (inland, glowing). */
    private val lava = BooleanArray(w * h)
    /** Where ambient life can be: beaches (crabs), flower patches (butterflies), ponds (fish), wall tops (gulls). */
    val beachSpots = ArrayList<Int>()
    val flowerSpots = ArrayList<Int>()
    val pondSpots = ArrayList<Int>()
    val wallTopSpots = ArrayList<Int>()
    val shallowSpots = ArrayList<Int>()
    val grassSpots = ArrayList<Int>()


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
        findSpots()
        ground = Pix.bitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
        bakeMs = (System.nanoTime() - bornNs) / 1e6f
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
        System.arraycopy(hole.coastFor(high).land, 0, land, 0, land.size)
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
        fairNear = blur(3) { it == Tile.FAIRWAY || it.isRamp }
        val green = blur(3) { it.isGreen }
        val fair = blur(3) { it == Tile.FAIRWAY }
        val tidal = if (hole.tidal) blur(2) { it == Tile.TIDAL } else null
        val mud = blur(2) { it == Tile.MUD }
        val obsidian = blur(2) { it == Tile.OBSIDIAN }
        val ice = blur(2) { it == Tile.ICE }
        val thin = blur(2) { it == Tile.THIN_ICE }
        val snow = blur(2) { it == Tile.SNOW }
        val vines = blur(2) { it == Tile.VINES }
        val ridge = blur(2) { it == Tile.RIDGE }
        val quick = blur(2) { it == Tile.QUICKSAND }
        val lavaNear = blur(2) { it == Tile.LAVA }
        for (i in 0 until w * h) lava[i] = !land[i] && lavaNear[i] > 0f
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
            val t = hole.tile(x / TILE, y / TILE)
            kind[i] = when {
                !land[i] -> K_WATER
                t == Tile.DECK || t == Tile.PLANK -> K_DECK
                t.vent -> K_VENT
                t == Tile.PLATE -> K_PLATE
                t == Tile.GATE || t == Tile.TUNNEL_IN || t == Tile.TUNNEL_OUT -> K_TEMPLE
                t.isLedge -> K_LEDGE
                t.isKicker -> K_PAD
                t == Tile.BOOST -> K_BOOST
                t == Tile.PORTAL || t.isPulse -> K_TEMPLE
                t == Tile.WELL -> K_WELL
                t == Tile.HULL -> K_HULL
                t.shallow -> K_SHALLOW
                rail[i] == Rails.WOOD.toInt() -> K_WOOD
                rail[i] == Rails.BOULDER.toInt() -> K_ROCK
                rounded[i] -> K_WALL
                hole.tile(x / TILE, y / TILE).isRamp -> K_RAMP
                tidal != null && tidal[i] > 0.5f -> K_TIDAL
                mud[i] > 0.5f -> K_MUD
                obsidian[i] > 0.5f -> K_OBSIDIAN
                thin[i] > 0.5f -> K_THIN
                ice[i] > 0.5f -> K_ICE
                snow[i] > 0.5f -> K_SNOW
                vines[i] > 0.5f -> K_VINES
                ridge[i] > 0.5f -> K_RIDGE
                quick[i] > 0.5f -> K_QUICK
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
        // Lava pools aren't ponds: no lily pads, reeds or fish.
        for (i in 0 until w * h) pond[i] = !land[i] && !sea[i] && !lava[i]
        val toPond = distanceFrom { pond[it] }
        for (i in 0 until w * h) nearPond[i] = land[i] && toPond[i] < 5f
    }

    private fun computeBanks() {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (land[i]) continue
            for (k in 1..BANK_H) {
                if (y - k >= 0 && land[(y - k) * w + x]) {
                    val above = kind[(y - k) * w + x]
                    if (above != K_DECK && above != K_SHALLOW) bank[i] = k.toByte()
                    break
                }
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

    /** Each course's own shore face (rows 1-3 below the lip), or null for the sandy default. */
    private fun styledBank(x: Int, y: Int, row: Int): Int? = when (hole.style) {
        "desert" -> when (row) { // sandstone in strata
            1 -> Palette.SANDS[3]; 2 -> if (y % 2 == 0) Palette.SANDS[2] else Palette.SANDS[1]; else -> Palette.SANDS[0]
        }
        "mushroom" -> when (row) { // dark loam, the odd glowing cap in it
            1 -> Palette.GRASS[1]; 2 -> if (hash(x, y, 120) < 0.07f) 0xFFD878E0.toInt() else Palette.WOODS[0]; else -> Palette.darker(Palette.WOODS[0])
        }
        "mangrove" -> when (row) { // roots down into the water
            1 -> Palette.GRASS[1]; 2 -> if (x % 3 == 0) Palette.WOODS[2] else Palette.WOODS[0]; else -> if (x % 3 == 0) Palette.WOODS[1] else Palette.darker(Palette.WOODS[0])
        }
        "carnival" -> when (row) { // the pier's edge on its pilings
            1 -> Palette.WOODS[4]; 2 -> if (x % 6 < 2) Palette.WOODS[2] else Palette.WOODS[0]; else -> if (x % 6 < 2) Palette.WOODS[1] else Palette.INK_SHADOW
        }
        "clock" -> when (row) { // a brass plinth with rivets
            1 -> Palette.lerp(Palette.SANDS[3], Palette.WOODS[3], 0.4f); 2 -> if (x % 6 == 0) Palette.PAPER[2] else Palette.lerp(Palette.SANDS[1], Palette.WOODS[1], 0.5f); else -> Palette.WOODS[0]
        }
        "crystal" -> when (row) { // cave rock with crystals in it
            1 -> Palette.STONES[2]; 2 -> if (hash(x, y, 121) < 0.1f) 0xFF9AE8F0.toInt() else Palette.STONES[1]; else -> Palette.STONES[0]
        }
        "zen" -> when (row) { // cut stone blocks
            1 -> Palette.STONES[3]; 2 -> if (x % 8 == 0) Palette.STONES[1] else Palette.STONES[2]; else -> Palette.STONES[0]
        }
        "autumn" -> when (row) { // earth with pebbles
            1 -> Palette.GRASS[2]; 2 -> if (hash(x, y, 122) < 0.12f) Palette.STONES[2] else Palette.WOODS[1]; else -> Palette.WOODS[0]
        }
        else -> null
    }

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
            if (bank[i] > 0 && hole.style == "ember") return when (bank[i].toInt()) { // basalt cliffs
                1 -> Palette.STONES[2]; 2 -> if ((x * 5 + y) % 7 == 0) Palette.STONES[2] else Palette.STONES[1]; else -> Palette.STONES[0]
            }
            if (bank[i] > 0 && hole.style == "sky") return when (bank[i].toInt()) { // a floating island's rocky underside
                1 -> Palette.STONES[2]; 2 -> if ((x * 3 + y) % 5 == 0) Palette.GRASS[2] else Palette.STONES[1]; else -> Palette.STONES[0]
            }
            if (bank[i] > 0 && hole.style == "frost") return when (bank[i].toInt()) { // snow over ice
                1 -> Palette.PAPER[2]; 2 -> Palette.lerp(Palette.SEA[4], Palette.PAPER[2], 0.4f); else -> Palette.SEA[3]
            }
            if (bank[i] > 0) styledBank(x, y, bank[i].toInt())?.let { return it }
            when (bank[i].toInt()) { // the island's bank face, in strata, with grass hanging over the lip
                1 -> return if (hash(x, y, 81) < 0.22f) Palette.ROUGH_DARK else Palette.BANK
                2 -> return if ((x * 7 + y) % 11 == 0) Palette.BANK else Palette.BANK_DARK
                3 -> return Palette.BANK_DARKER
            }
            if (lava[i]) return lavaPixel(x, y, d)
            if (hole.style == "sky") return skyPixel(x, y, d)
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
            // A boardwalk's edge beam, and its shadow on the water.
            if (y > 0 && kind[i - w] == K_DECK) return Palette.WOODS[1]
            if (y > 1 && kind[i - 2 * w] == K_DECK) c = Palette.darker(Palette.darker(c))
            else if (y > 2 && kind[i - 3 * w] == K_DECK && (x + y) % 2 == 0) c = Palette.darker(c)
            return c
        }
        if (kd == K_DECK) return deck(x, y)
        if (kd == K_HULL) return hull(x, y)
        if (kd == K_SHALLOW) return shallow(x, y, d)
        if (kd == K_TIDAL) return tidalSand(x, y, d)
        if (kd == K_MUD) return mudPixel(x, y)
        if (kd == K_VENT) return if (hole.style == "mushroom") mushroomPixel(x, y) else ventPixel(x, y)
        if (kd == K_OBSIDIAN) return obsidianPixel(x, y)
        if (kd == K_ICE) return icePixel(x, y, false)
        if (kd == K_THIN) return if (hole.style == "mangrove") lilyPixel(x, y) else icePixel(x, y, true)
        if (kd == K_SNOW) return if (hole.style == "autumn") leafPixel(x, y) else snowPixel(x, y)
        if (kd == K_PLATE) return platePixel(x, y)
        if (kd == K_TEMPLE) return templeFloor(x, y)
        if (kd == K_VINES) return vinesPixel(x, y)
        if (kd == K_LEDGE) return ledgePixel(x, y)
        if (kd == K_PAD) return padPixel(x, y)
        if (kd == K_BOOST) return boostPixel(x, y)
        if (kd == K_WELL) return wellPixel(x, y)
        if (kd == K_QUICK) return quickPixel(x, y)
        if (kd == K_WALL) return if (hole.style == "crystal") Palette.lerp(stone(x, y), CRYSTAL, 0.4f) else stone(x, y)
        if (kd == K_WOOD) return wood(x, y)
        if (kd == K_ROCK) return rock(x, y)
        if (kd == K_RAMP) return ramp(x, y)

        // Beaches where land meets the sea.
        val beachTop = 3.4f + hash(x / 3, y / 3, 3)
        // Dune grass: little tufts where the grass gives way to the beach.
        if (hole.style == "" && d >= beachTop - 1.2f && d < beachTop && hash(x / 2, y, 104) < 0.35f && y % 2 == 0) return Palette.ROUGH_DARK
        if (kd != K_MUD && d < beachTop) return when (hole.style) {
            "ember" -> if (d < 1.5f) Palette.STONES[2] else Palette.STONES[1]   // black sand
            "frost" -> if (d < 1.5f) Palette.PAPER[2] else Palette.PAPER[3]     // snow to the waterline
            else -> if (d < 1.5f) Palette.SAND_LIGHT else Palette.SAND
        }

        return when (kd) {
            K_FAIR -> styled(fairPixel(x, y), 0.18f)
            K_ROUGH -> {
                // The first cut: a dithered band of slightly lighter grass where the rough meets fairway.
                val f = fairNear[i]
                if (f > 0.12f && (f > 0.3f || (x + y) % 2 == 0)) styled(Palette.lerp(Palette.ROUGH, Palette.FAIR_B, 0.5f), 0.45f)
                else styled(roughPixel(x, y), 0.45f)
            }
            K_RIDGE -> if ((x + y * 2 + (hash(x / 6, y / 4, 75) * 4).toInt()) % 9 < 2) Palette.GRASS[6] else Palette.GRASS[5]
            K_SAND -> if (hole.style == "zen") {
                // Raked gravel: pale, with grooves.
                if (k(x - 1, y) != K_SAND || k(x + 1, y) != K_SAND || k(x, y - 1) != K_SAND || k(x, y + 1) != K_SAND) Palette.STONES[3]
                else if (y % 3 == 0) Palette.PAPER[1] else Palette.PAPER[3]
            } else {
                val lip = k(x - 1, y) != K_SAND || k(x + 1, y) != K_SAND || k(x, y - 1) != K_SAND || k(x, y + 1) != K_SAND
                // Light from the top left: the bunker's far (top/left) face is in shadow, the near side lit.
                val face = k(x, y - 1) != K_SAND || k(x, y - 2) != K_SAND || k(x - 1, y) != K_SAND
                val near = k(x, y + 1) != K_SAND || k(x + 1, y) != K_SAND
                when {
                    lip -> Palette.SAND_DARK
                    face -> if ((x + y) % 2 == 0) Palette.SAND_DARK else Palette.SAND
                    near -> Palette.SAND_LIGHT
                    (x + 2 * y + (hash(x / 6, y / 6, 4) * 3).toInt()) % 7 == 0 -> Palette.SAND_DARK // wind ripples
                    else -> Palette.SAND
                }
            }
            K_GREEN -> {
                val t = tileAt(x, y)
                // Fine stripes, mown the same way as the hole's fairway, in close tones.
                var g = if (mowBand(x * 2, y * 2)) Palette.GREEN_A else Palette.lerp(Palette.GREEN_A, Palette.GREEN_B, 0.6f)
                if (t.slopeX != 0f || t.slopeY != 0f) {
                    // Grain streaks running downhill (the glints show which way).
                    val along = if (t.slopeX != 0f) x else y
                    val across = if (t.slopeX != 0f) y else x
                    g = Palette.GREEN_B
                    if (across % 4 == 1 && (along + across * 3) % 6 < 3) g = Palette.SLOPE_DARK
                }
                val collar = k(x - 1, y) != K_GREEN || k(x + 1, y) != K_GREEN || k(x, y - 1) != K_GREEN || k(x, y + 1) != K_GREEN
                // Raised greens: a lit rim along the top and left, a shaded one along the bottom and right.
                val lit = k(x - 2, y) != K_GREEN || k(x, y - 2) != K_GREEN
                val shade = k(x + 2, y) != K_GREEN || k(x, y + 2) != K_GREEN
                when {
                    collar -> Palette.COLLAR
                    lit -> Palette.lighter(Palette.GREEN_A)
                    shade -> Palette.GREEN_B
                    else -> g
                }
            }
            else -> Palette.ROUGH
        }
    }

    /** Ground that decoration may sit on: not walls, logs, boulders, ramps, decks or water over sand. */
    private fun isPlain(i: Int): Boolean {
        if (i < 0 || i >= w * h) return false
        val kd = kind[i]
        return kd != K_WALL && kd != K_WOOD && kd != K_ROCK && kd != K_RAMP && kd != K_DECK && kd != K_HULL &&
            kd != K_SHALLOW && kd != K_TIDAL && kd != K_MUD && kd != K_VENT && kd != K_OBSIDIAN && kd != K_ICE &&
            kd != K_THIN && kd != K_SNOW && kd != K_PLATE && kd != K_TEMPLE && kd != K_LEDGE
    }

    /** Whether a deck tile is part of a run going up and down the map (so its boards lie across). */
    private fun deckRunsVertically(tx: Int, ty: Int): Boolean {
        fun d(t: Tile) = t == Tile.DECK || t == Tile.PLANK
        val v = d(hole.tile(tx, ty - 1)) || d(hole.tile(tx, ty + 1))
        val hz = d(hole.tile(tx - 1, ty)) || d(hole.tile(tx + 1, ty))
        return v && !hz || (v && hz && hole.h > hole.w)
    }

    /** Weathered boards with soft seams, staggered ends and the odd nail. */
    private fun deck(x: Int, y: Int): Int {
        val tx = x / TILE
        val ty = y / TILE
        val vertical = deckRunsVertically(tx, ty)
        val across = if (vertical) y else x // boards are 4 px wide, laid across the run
        val along = if (vertical) x else y
        val board = across / 4
        val seam = across % 4 == 3
        val end = (along + (hash(board, 0, 61) * 24).toInt()) % 24 == 0
        var c = when {
            end -> Palette.WOODS[1]
            seam -> Palette.WOODS[2]
            hash(board, along / 8, 62) < 0.2f -> Palette.lerp(Palette.WOODS[3], Palette.WOODS[2], 0.5f)
            else -> Palette.WOODS[3]
        }
        if (!seam && !end && across % 4 == 0 && hash(x, y, 63) < 0.1f) c = Palette.WOODS[4]
        if (!seam && (along % 24 == 3) && across % 4 == 1) c = Palette.WOODS[1] // nails
        val edgeTile = if (vertical) (x % TILE == 0 && !isDeck(tx - 1, ty)) || (x % TILE == TILE - 1 && !isDeck(tx + 1, ty))
            else (y % TILE == 0 && !isDeck(tx, ty - 1)) || (y % TILE == TILE - 1 && !isDeck(tx, ty + 1))
        return if (edgeTile) Palette.WOODS[2] else c
    }

    private fun isDeck(tx: Int, ty: Int): Boolean { val t = hole.tile(tx, ty); return t == Tile.DECK || t == Tile.PLANK }

    /** A wreck's dark timbers: a 3/4 wall with a lit top edge, dark planked body and a shadowed face. */
    private fun hull(x: Int, y: Int): Int {
        val tx = x / TILE
        val ty = y / TILE
        val v = y % TILE
        val openBelow = hole.tile(tx, ty + 1) != Tile.HULL
        if (openBelow && v >= FACE_TOP) {
            if (v == TILE - 1) return Palette.WOODS[0]
            return if ((x + (v - FACE_TOP) * 3) % 7 == 0) Palette.WOODS[0] else Palette.lerp(Palette.WOODS[0], Palette.WOODS[1], 0.6f)
        }
        val openAbove = hole.tile(tx, ty - 1) != Tile.HULL
        if (openAbove && v == 0) return Palette.WOODS[4]
        if (openAbove && v == 1) return Palette.WOODS[3]
        val sideOpen = (x % TILE == 0 && hole.tile(tx - 1, ty) != Tile.HULL) || (x % TILE == TILE - 1 && hole.tile(tx + 1, ty) != Tile.HULL)
        if (sideOpen) return Palette.WOODS[3]
        return if (y % 3 == 0) Palette.WOODS[0] else Palette.WOODS[1]
    }

    /** A conveyor belt (carnival and clockwork currents): dark rubber between steel rails. Its ridges move in [Bodies]. */
    private fun beltPixel(x: Int, y: Int): Int {
        val t = tileAt(x, y)
        val tx = Math.floorDiv(x, TILE)
        val ty = Math.floorDiv(y, TILE)
        val across = if (t.flowX != 0f) y - ty * TILE else x - tx * TILE
        val (lo, hi) = if (t.flowX != 0f) hole.tile(tx, ty - 1) to hole.tile(tx, ty + 1) else hole.tile(tx - 1, ty) to hole.tile(tx + 1, ty)
        if (across == 0 && !lo.flows) return Palette.STONES[4]
        if (across == 1 && !lo.flows) return Palette.STONES[2]
        if (across == TILE - 1 && !hi.flows) return Palette.STONES[1]
        return if (hash(x / 2, y / 2, 112) < 0.1f) Palette.STONES[1] else Palette.STONES[0]
    }

    /** Clear turquoise water over sand: a bright rim at the drop-off, caustic lines, darker channels. */
    private fun shallow(x: Int, y: Int, d: Float): Int {
        if (hole.belts && tileAt(x, y).flows) return beltPixel(x, y)
        if (d < 1.2f) return Palette.SEA[5]
        if (d < 2.4f && (x + y) % 2 == 0) return Palette.SEA[5]
        val t = tileAt(x, y)
        val base = if (t.flows) Palette.lerp(Palette.SEA[3], Palette.SEA[4], 0.5f) else Palette.lerp(Palette.SEA[4], Palette.SANDS[3], 0.28f)
        // Caustics: thin wavy light lines.
        val wave = (x + (kotlin.math.sin(y * 0.5f + x * 0.13f) * 2.5f).toInt() + y / 3) % 9 == 0
        if (wave && hash(x / 2, y / 2, 64) < 0.7f) return Palette.lerp(base, Palette.SEA[5], 0.6f)
        return if (hash(x / 5, y / 4, 68) < 0.12f) Palette.lerp(base, Palette.SANDS[4], 0.25f) else base
    }

    /** Firm, wet tidal sand: darker and cooler than the beach, with long ripples and the odd puddle. */
    private fun tidalSand(x: Int, y: Int, d: Float): Int {
        if (d < 1.2f) return Palette.SANDS[3]
        // Puddles: small ovals on a coarse grid, lit along their top edge.
        val cx = x / 6
        val cy = y / 5
        if (hash(cx, cy, 66) < 0.08f) {
            val ox = cx * 6 + 3
            val oy = cy * 5 + 2
            val dx = x - ox
            val dy = y - oy
            if (dx * dx + dy * dy * 4 <= 5) return if (dy < 0) Palette.SEA[5] else Palette.SEA[4]
        }
        val ripple = (2 * y + (kotlin.math.sin(x * 0.45f + y / 6) * 2f).toInt()) % 7 == 0
        return if (ripple) Palette.SANDS[1] else Palette.SANDS[2]
    }

    /** Fairway: mowing stripes with fine mower lines, and a lit edge where each light stripe starts. */
    private fun fairPixel(x: Int, y: Int): Int {
        val light = mowBand(x, y)
        val base = if (light) Palette.FAIR_A else Palette.FAIR_B
        // The roller's edge: the first pixel row of a light stripe catches the light.
        val prev = when (mow) { 0 -> mowBand(x, y - 1); 1 -> mowBand(x - 1, y); else -> mowBand(x + 1, y) }
        if (light && !prev) return Palette.lerp(Palette.FAIR_A, Palette.GRASS[6], 0.35f)
        // Mower lines: every fourth row in the stripe's direction, a shade apart.
        val along = when (mow) { 0 -> y; 1 -> x + y; else -> y - x + 1024 }
        if (along % 4 == 0) return Palette.lerp(base, if (light) Palette.FAIR_B else Palette.FAIR_A, 0.22f)
        return base
    }

    /** Rough: big soft clumps of darker and lighter grass (cells, not speckle). */
    private fun roughPixel(x: Int, y: Int): Int {
        val cx = (x + (hash(x / 3, y / 3, 101) * 3).toInt()) / 7
        val cy = (y + (hash(x / 3, y / 3, 102) * 3).toInt()) / 6
        val h = hash(cx, cy, 103)
        return when {
            h < 0.18f -> Palette.lerp(Palette.ROUGH, Palette.ROUGH_DARK, 0.55f)
            h > 0.86f -> Palette.lerp(Palette.ROUGH, Palette.ROUGH_LIGHT, 0.3f)
            else -> Palette.ROUGH
        }
    }

    /** Mowing: two-tile bands, straight or diagonal depending on the hole. */
    private fun mowBand(x: Int, y: Int): Boolean = when (mow) {
        0 -> (y / 16) % 2 == 0
        1 -> ((x + y + 1024) / 14) % 2 == 0
        else -> ((y - x + 1024) / 14) % 2 == 0
    }

    /** Grass on a frosty or volcanic course: dusted with snow, or scorched darker. */
    private fun styled(c: Int, amount: Float): Int = when (hole.style) {
        "frost" -> Palette.lerp(c, Palette.PAPER[3], amount)
        "ember" -> Palette.lerp(c, Palette.STONES[1], amount * 0.7f)
        "desert" -> Palette.lerp(c, Palette.SANDS[3], amount * 0.9f)      // dry, sun-bleached grass
        "autumn" -> Palette.lerp(c, AUTUMN_LEAF, amount * 0.7f)
        "mangrove" -> Palette.lerp(c, Palette.GRASS[1], amount * 0.45f)
        "mushroom" -> Palette.lerp(c, MOSS, amount * 0.6f)
        "crystal" -> Palette.lerp(c, Palette.STONES[1], amount * 0.8f)
        "zen" -> Palette.lerp(c, Palette.GRASS[5], amount * 0.2f)
        else -> c
    }

    /** The sky beneath a floating island: pale haze with slow cloud banks. */
    private fun skyPixel(x: Int, y: Int, d: Float): Int {
        val base = when {
            d < 3f -> Palette.lerp(Palette.SEA[5], 0xFFFFFFFF.toInt(), 0.5f)
            d < 10f -> Palette.lerp(Palette.SEA[4], Palette.SEA[5], 0.6f)
            else -> return 0 // see-through: the open sky (and its far clouds) beyond the map shows here, without a seam
        }
        return base
    }

    /** A kicker pad: a metal plate with an arrow the way it throws. */
    private fun padPixel(x: Int, y: Int): Int {
        val t = tileAt(x, y)
        val u = x % TILE
        val v = y % TILE
        if (u == 0 || v == 0 || u == TILE - 1 || v == TILE - 1) return Palette.STONES[1]
        // Arrow: along the kick, centred.
        val along = (if (t.kickX != 0f) (if (t.kickX > 0) u else TILE - 1 - u) else (if (t.kickY > 0) v else TILE - 1 - v))
        val across = kotlin.math.abs((if (t.kickX != 0f) v else u) - 3.5f)
        return if (across < along * 0.6f - 0.5f && along in 2..6) Palette.GOLD else Palette.STONES[3]
    }

    /** A boost pad: dark with a bright bolt. */
    private fun boostPixel(x: Int, y: Int): Int {
        val u = x % TILE
        val v = y % TILE
        if (u == 0 || v == 0 || u == TILE - 1 || v == TILE - 1) return Palette.STONES[1]
        val bolt = (v < 4 && u == 5 - v) || (v >= 4 && u == 7 - v) || (v == 4 && u in 3..5)
        return if (bolt) Palette.GOLD else Palette.STONES[0]
    }

    /** A gravity well: a dark pool, rings of light falling in (drawn live over it). */
    private fun wellPixel(x: Int, y: Int): Int {
        val dx = x % TILE - 3.5f
        val dy = y % TILE - 3.5f
        val r = sqrt(dx * dx + dy * dy)
        return when {
            r < 2f -> Palette.NIGHT[0]
            r < 3.2f -> Palette.NIGHT[2]
            r < 4f -> Palette.NIGHT[4]
            else -> styled(Palette.FAIR_B, 0.2f)
        }
    }

    /** Quicksand: pale damp sand in rings (it swirls, drawn live). */
    /** Quicksand: wet, darker than a bunker, in rings closing on the middle of the patch. */
    private fun quickPixel(x: Int, y: Int): Int {
        val tx = (x / TILE).coerceIn(0, hole.w - 1)
        val ty = (y / TILE).coerceIn(0, hole.h - 1)
        val i = ty * hole.w + tx
        val wet = Palette.lerp(Palette.SANDS[2], Palette.SANDS[1], 0.45f)
        if (hole.tile(tx, ty) != Tile.QUICKSAND) return wet
        val d = kotlin.math.hypot(x + 0.5f - hole.quickX[i], y + 0.5f - hole.quickY[i])
        val ring = (d + hash(x / 3, y / 3, 106) * 0.8f) % 4f
        return when {
            d < 1.5f -> Palette.SANDS[0]
            ring < 1f -> Palette.SANDS[1]
            ring < 1.6f && (x + y) % 2 == 0 -> Palette.SANDS[2]
            else -> wet
        }
    }

    /** Lava: a dark crust with glowing cracks, molten towards the middle. */
    private fun lavaPixel(x: Int, y: Int, d: Float): Int {
        if (d < 1.2f) return LAVA_CRUST
        val crack = hash(x / 3, y / 3, 76) < 0.3f && (x + y) % 3 != 0
        return when {
            d < 3f -> if (crack) LAVA_HOT else LAVA_CRUST
            d < 6f -> if (crack) LAVA_BRIGHT else LAVA_HOT
            else -> if (crack) LAVA_WHITE else LAVA_BRIGHT
        }
    }

    /** A steam vent: a ring of dark rock round a glowing throat. */
    private fun ventPixel(x: Int, y: Int): Int {
        val dx = x % TILE - 3.5f
        val dy = y % TILE - 3.5f
        val r = sqrt(dx * dx + dy * dy)
        return when {
            r < 1.3f -> LAVA_HOT
            r < 2.3f -> Palette.STONES[0]
            r < 3.6f -> if ((x + y) % 2 == 0) Palette.STONES[1] else Palette.STONES[2]
            else -> styled(Palette.FAIR_B, 0.3f)
        }
    }

    /** Obsidian: black glass with diagonal glints. */
    private fun obsidianPixel(x: Int, y: Int): Int {
        val streak = (x - y + 64) % 11
        return when {
            streak == 0 && hash(x / 4, y / 4, 77) < 0.6f -> Palette.STONES[3]
            streak == 1 -> Palette.STONES[1]
            hash(x, y, 78) < 0.01f -> Palette.STONES[4]
            else -> Palette.STONES[0]
        }
    }

    /** Ice: pale and clear with scratches; thin ice is darker and bluer (deep water shows through). */
    private fun icePixel(x: Int, y: Int, thin: Boolean): Int {
        val base = if (thin) Palette.lerp(Palette.SEA[3], Palette.SEA[5], 0.55f) else Palette.lerp(Palette.SEA[5], Palette.PAPER[3], 0.45f)
        val scratch = (x * 2 + y + (hash(x / 9, y / 7, 79) * 6).toInt()) % 13 == 0
        return when {
            scratch -> if (thin) Palette.SEA[5] else Palette.lerp(Palette.SEA[4], base, 0.5f)
            hash(x, y, 80) < 0.015f -> Palette.PAPER[3]
            else -> base
        }
    }

    /** A pile of fallen leaves (autumn's snowbank): reds, oranges and browns in clusters. */
    private fun leafPixel(x: Int, y: Int): Int {
        val h = hash(x / 2, y / 2, 107)
        return when {
            k(x, y + 2) != K_SNOW -> Palette.WOODS[1]
            h < 0.3f -> AUTUMN_LEAF
            h < 0.55f -> 0xFFB83A2A.toInt()
            h < 0.8f -> 0xFFE8A040.toInt()
            else -> Palette.WOODS[2]
        }
    }

    /** Lily pads (the mangrove's thin ice): round pads on dark still water. */
    private fun lilyPixel(x: Int, y: Int): Int {
        val dx = x % TILE - 3.5f
        val dy = y % TILE - 3.5f
        val notch = dx > 0f && kotlin.math.abs(dy) < 0.8f
        return if (dx * dx + dy * dy < 12f && !notch) (if (dy < -1.5f) Palette.GRASS[5] else Palette.GRASS[4]) else Palette.POND[1]
    }

    /** A springy mushroom (the hollow's vent): a red cap with pale spots. */
    private fun mushroomPixel(x: Int, y: Int): Int {
        val dx = x % TILE - 3.5f
        val dy = y % TILE - 3.5f
        val r = dx * dx + dy * dy * 1.3f
        return when {
            r < 2.2f && (x + y) % 2 == 0 -> Palette.PAPER[3]
            r < 12f -> if (dy < 0f) 0xFFE0483E.toInt() else 0xFFA8303A.toInt()
            r < 15f -> Palette.lerp(0xFFA8303A.toInt(), Palette.GRASS[2], 0.5f)
            else -> styled(Palette.FAIR_B, 0.3f)
        }
    }

    /** Snowbanks: soft white with a cool shadow on their lower side. */
    private fun snowPixel(x: Int, y: Int): Int {
        val below = k(x, y + 2) != K_SNOW
        val above = k(x, y - 1) != K_SNOW
        return when {
            below -> Palette.lerp(Palette.PAPER[2], Palette.SEA[4], 0.25f)
            above -> 0xFFFFFFFF.toInt()
            hash(x / 2, y / 2, 82) < 0.1f -> Palette.PAPER[2]
            else -> Palette.PAPER[3]
        }
    }

    /** A switch plate: a carved stone square (its glyph is drawn live, lit once pressed). */
    private fun platePixel(x: Int, y: Int): Int {
        val u = x % TILE
        val v = y % TILE
        return when {
            u == 0 || v == 0 -> Palette.STONES[3]
            u == TILE - 1 || v == TILE - 1 -> Palette.STONES[1]
            else -> Palette.STONES[2]
        }
    }

    /** Old temple flagstones (under gates and round tunnel mouths). */
    private fun templeFloor(x: Int, y: Int): Int {
        val u = x % TILE
        val v = y % TILE
        if (u == 0 || v == 0) return Palette.STONES[1]
        return if (hash(x / 4, y / 4, 83) < 0.3f) Palette.STONES[2] else Palette.lerp(Palette.STONES[2], Palette.STONES[3], 0.5f)
    }

    /** Vines: dark, leafy overgrowth. */
    private fun vinesPixel(x: Int, y: Int): Int {
        val h = hash(x / 2, y / 2, 84)
        return when {
            h < 0.18f -> Palette.LEAF[3]
            h < 0.55f -> Palette.LEAF[1]
            else -> Palette.LEAF[2]
        }
    }

    /**
     * A ledge, drawn the same whichever way it drops (so you can always read it): a lit lip,
     * a rock face, the lower ground in its shadow, and a pale chevron pointing down the drop.
     */
    private fun ledgePixel(x: Int, y: Int): Int {
        val t = tileAt(x, y)
        val u = x % TILE
        val v = y % TILE
        // How far down the drop this pixel is (0 at the lip, TILE-1 at the foot), and across it.
        val k = when {
            t.dropY > 0f -> v
            t.dropY < 0f -> TILE - 1 - v
            t.dropX > 0f -> u
            else -> TILE - 1 - u
        }
        val across = if (t.dropY != 0f) u else v
        val a = kotlin.math.abs(across - 3.5f)
        val chevron = ((x / TILE + y / TILE) % 2 == 0) && a < 3f && k == 2 + (3f - a).toInt()
        if (chevron) return Palette.PAPER[2]
        return when {
            k == 0 -> styled(Palette.GRASS[6], 0.3f)
            k == 1 -> styled(Palette.GRASS[5], 0.3f)
            k <= 4 -> if ((x * 3 + y) % 7 == 0) Palette.STONES[1] else Palette.STONES[2]
            k == 5 -> Palette.STONES[1]
            else -> Palette.GRASS[2]
        }
    }

    /** Glossy dark mud with wet highlights. */
    private fun mudPixel(x: Int, y: Int): Int {
        val blob = hash(x / 3, y / 3, 67)
        return when {
            blob < 0.12f && (x + y) % 2 == 0 -> Palette.SANDS[2]
            blob > 0.7f -> Palette.SANDS[0]
            else -> Palette.SANDS[1]
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
                        } else if (r < 0.62f) { // clover: a soft lighter clump
                            val cl = Palette.lerp(Palette.ROUGH, Palette.ROUGH_LIGHT, 0.5f)
                            put(x, y, cl, K_ROUGH); put(x + 1, y, cl, K_ROUGH); put(x, y + 1, cl, K_ROUGH)
                            put(x + 1, y - 1, Palette.ROUGH_LIGHT, K_ROUGH)
                        } else if (r < 0.72f) { // long grass: blades with lit tips
                            put(x, y, Palette.ROUGH_DARK, K_ROUGH); put(x, y - 1, Palette.ROUGH_DARK, K_ROUGH); put(x, y - 2, Palette.ROUGH_LIGHT, K_ROUGH)
                            put(x + 2, y, Palette.ROUGH_DARK, K_ROUGH); put(x + 2, y - 1, Palette.ROUGH_LIGHT, K_ROUGH)
                        } else if (r < 0.77f && edge[y * w + x] < 9f) { // pebbles near the shore
                            put(x, y, Palette.STONES[3], K_ROUGH); put(x + 1, y, Palette.STONES[2], K_ROUGH)
                            put(x + 3, y + 1, Palette.STONES[3], K_ROUGH)
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
                        val bush = SCENERY[hole.style] ?: SCENERY.getValue("")
                        for (yy in 0..2) for (xx in 0..3) {
                            val ch = bush[yy][xx]
                            if (ch != '.') px[(y + yy) * w + x + xx] = sceneryColour(ch)
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
                    if (hole.style != "frost" && pond[i] && edge[i] >= 3f && hash(gx, gy, 33) < 0.4f && pond[i + 2] && pond[i + w + 2]) {
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
                    if (hole.style != "frost" && nearPond[i] && edge[i] < 2.5f && isPlain(i) && isPlain(i - 3 * w) && hash(gx, gy, 43) < 0.55f) {
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
                    if (land[i] && isPlain(i) && isPlain(i + 1) && d in 1.2f..3.2f) {
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
            return kd == K_WALL || kd == K_WOOD || kd == K_ROCK || kd == K_HULL
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
        val phase = PHASE[f % FRAMES]
        if (land[i] && nearPond[i]) return 0
        // The sky below a floating island: just a wisp of cloud at the island's edge, no waves.
        if (hole.style == "sky" && !land[i]) return if (d < 1.6f && (x + y + f) % 4 == 0) Palette.alpha(0xFFFFFFFF.toInt(), 110) else 0
        if (lava[i]) {
            // Lava: glowing cracks that slowly shift.
            return if (d > 1.5f && hash((x + f / 2) / 3, (y - f / 3) / 3, 85) < 0.07f) Palette.alpha(LAVA_WHITE, 200) else 0
        }
        if (pond[i]) {
            // Ponds: a slow, faint ring moving out from the bank.
            val r = 1.5f + (f % FRAMES) * 0.6f
            return if (d >= r && d < r + 1f && (x + y + f) % 3 == 0) Palette.alpha(Palette.POND[4], 110) else 0
        }
        if (land[i]) {
            // Wet sand where the wash just pulled back (not on a cliff top above a bank).
            val cliff = y + 1 < h && bank[i + w] > 0
            val beach = kind[i] != K_WALL && kind[i] != K_WOOD && kind[i] != K_DECK && kind[i] != K_HULL && kind[i] != K_SHALLOW
            return if (beach && !cliff && d < 0.8f + 1.6f * (1f - phase)) Palette.alpha(Palette.SANDS[2], 150) else 0
        }
        if (bank[i] > 0) return 0
        val reach = 0.6f + 2.2f * phase
        if (d < reach) return if (d > reach - 1.1f) Palette.FOAM else Palette.lerp(Palette.SHOAL, Palette.FOAM, 0.5f)
        // The sun on the water: a diagonal path of twinkling glints (light from the top left).
        if (d > 4f) {
            val along = x * 0.6f + y * 0.8f - sunLine
            if (along in -16f..16f) {
                val gx = x / 5
                val gy = y / 5
                val jx = gx * 5 + (hash(gx, gy, 95) * 4).toInt()
                val jy = gy * 5 + (hash(gx, gy, 96) * 4).toInt()
                val on = ((hash(gx, gy, 97) * FRAMES).toInt() + f) % FRAMES < 3 && hash(gx, gy, 98) < 0.55f - kotlin.math.abs(along) / 40f
                if (on && y == jy && (x == jx || x == jx + 1)) return Palette.alpha(Palette.FOAM, 210)
            }
        }
        if (d > 14f) {
            when (crest(x, y, f)) {
                1 -> return Palette.MID
                2 -> return Palette.SHALLOW
            }
            val sd = seaDetail(x, y, f)
            if (sd != 0) return sd
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

    private fun findSpots() {
        for (gy in 0 until h step 7) for (gx in 0 until w step 7) {
            val x = gx + (hash(gx, gy, 91) * 6).toInt()
            val y = gy + (hash(gx, gy, 92) * 6).toInt()
            if (x !in 2 until w - 3 || y !in 2 until h - 3) continue
            val i = y * w + x
            when {
                land[i] && isPlain(i) && edge[i] in 1.5f..3f && !nearPond[i] -> beachSpots += i
                pond[i] && edge[i] >= 4f -> pondSpots += i
                kind[i] == K_WALL && isStone(x, y + 4) && isStone(x, y - 1) -> wallTopSpots += i
                kind[i] == K_ROUGH && edge[i] > 5f && hash(gx, gy, 93) < 0.25f -> flowerSpots += i
                kind[i] == K_SHALLOW && edge[i] > 3f && !tileAt(x, y).flows -> shallowSpots += i
                kind[i] == K_FAIR && edge[i] > 6f && hash(gx, gy, 94) < 0.3f -> grassSpots += i
            }
        }
    }

    /** Whether ([x], [y]) is inland water (a pond) rather than the sea. */
    fun isPond(x: Float, y: Float): Boolean {
        val px = x.toInt()
        val py = y.toInt()
        return px in 0 until w && py in 0 until h && pond[py * w + px]
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
        for (y in 0 until th) for (x in 0 until tw) {
            val sx = x * step + step / 2
            val sy = y * step + step / 2
            val v = src[sy * w + sx]
            px[y * tw + x] = if (v == 0) skyAt(sx, sy) else v // the open sky is see-through on the map itself
        }
        return Pix.bitmap(tw, th, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, tw, 0, 0, tw, th) }
    }

    /** The colours of the scenery grids in [SCENERY]. */
    private fun sceneryColour(ch: Char): Int = when (ch) {
        'l' -> Palette.LEAF[3]; 'm' -> Palette.LEAF[2]; 'd' -> Palette.LEAF[1]
        's' -> Palette.SANDS[1]; 'S' -> Palette.SANDS[3]; 'g' -> Palette.GRASS[2]
        'r' -> 0xFFB050C0.toInt(); 'w' -> Palette.PAPER[2]; 'W' -> Palette.PAPER[3]
        'y' -> Palette.GOLD; 'f' -> Palette.FLAG; 'b' -> Palette.SEA[3]
        'c' -> 0xFF9AE8F0.toInt(); 'k' -> Palette.STONES[1]; 'K' -> Palette.STONES[2]; 'L' -> Palette.STONES[3]
        'o' -> Palette.WOODS[2]; 'O' -> Palette.WOODS[0]
        else -> Palette.LEAF[1]
    }

    companion object {
        /** Low scenery in the rough, per course style (4×3; never anything that looks like a hazard). */
        private val SCENERY = mapOf(
            "" to arrayOf(".mm.", "mllm", "dmmd"),
            "desert" to arrayOf("s.s.", ".gs.", "sgg."),          // a dry shrub
            "mushroom" to arrayOf("r..r", "rr.w", "w..w"),        // little toadstools
            "carnival" to arrayOf("y..f", ".b..", "f.y."),        // dropped confetti
            "clock" to arrayOf(".SS.", "SOOS", ".SS."),           // a stray cog
            "mangrove" to arrayOf("l.l.", "l.ll", "dddd"),        // reeds
            "crystal" to arrayOf(".c..", "cWc.", "kkK."),         // small crystals
            "autumn" to arrayOf(".oo.", "oWWo", ".w.."),          // a toadstool
            "zen" to arrayOf(".KK.", "KLLK", ".KK."),             // a smooth stone
        )
        const val FRAMES = 8
        /** The wash's phase on each water frame (in and out once per loop). */
        private val PHASE = FloatArray(FRAMES) { f -> 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * f / FRAMES) }

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
                var len = intArrayOf(0, 2, 3, 4, 4, 3, 2, 0)[life]
                if (len == 0) continue
                if (Palette.mood == Mood.STORM) len += 2 // a storm's crests run longer
                val jx = cell * 13 + (hash(gx, gy, 71) * 8).toInt() - life
                val jy = gy0 * 9 + (hash(gx, gy, 72) * 6).toInt()
                if (x >= jx && x < jx + len && y == jy) {
                    return if (life in 3..4 && hash(gx, gy, 75) < 0.2f) 2 else 1
                }
            }
            return 0
        }

        private var seaTiles: Array<Bitmap>? = null
        private var seaMood: Mood? = null
        private var seaStyle: String = ""

        /** The open sea beyond the map, [FRAMES] tiles of [SEA_W]×[SEA_H], rebuilt if the time of day changes. */
        val sea: Array<Bitmap> get() = sea("")

        /** The same for a course's style (a sky course has open sky below, not sea). */
        fun sea(style: String): Array<Bitmap> {
            val st = if (style == "sky") "sky" else ""
            val cur = seaTiles
            if (cur != null && seaMood == Palette.mood && seaStyle == st) return cur
            return buildSea(st).also { seaTiles = it; seaMood = Palette.mood; seaStyle = st }
        }

        private fun buildSea(style: String): Array<Bitmap> =
            Array(FRAMES) { f ->
                val px = IntArray(SEA_W * SEA_H)
                for (y in 0 until SEA_H) for (x in 0 until SEA_W) {
                    px[y * SEA_W + x] = if (style == "sky") skyAt(x, y) else when (crest(x, y, f)) {
                        1 -> Palette.MID
                        2 -> Palette.SHALLOW
                        else -> seaDetail(x, y, f).takeIf { it != 0 } ?: Palette.DEEP
                    }
                }
                Pix.bitmap(SEA_W, SEA_H).apply { setPixels(px, 0, SEA_W, 0, 0, SEA_W, SEA_H) }
            }

        /**
         * Detail on open water between the crests, the same on the map and the sea beyond it
         * (it repeats every [SEA_W]×[SEA_H]): a slow dark swell everywhere, and by time of day
         * star glints, the aurora's green, ice floes or glowing specks. Never brighter than the
         * shallows or bigger than the ball, so it can't be taken for land.
         */
        fun seaDetail(x: Int, y: Int, f: Int): Int {
            val tx = Math.floorMod(x, SEA_W)
            val ty = Math.floorMod(y, SEA_H)
            val sx = tx / 26
            val sy = ty / 12
            val jx = sx * 26 + (hash(sx, sy, 130) * 14).toInt()
            val jy = sy * 12 + (hash(sx, sy, 131) * 9).toInt()
            if (hash(sx, sy, 132) < 0.5f && ty == jy && tx >= jx && tx < jx + 6 + (hash(sx, sy, 133) * 5).toInt()) return Palette.darker(Palette.DEEP)
            val cx = tx / 13
            val cy = ty / 9
            val px = cx * 13 + 2 + (hash(cx, cy, 134) * 9).toInt()
            val py = cy * 9 + 1 + (hash(cx, cy, 135) * 7).toInt()
            val on = ((hash(cx, cy, 136) * FRAMES).toInt() + f) % FRAMES < 5
            return when (Palette.mood) {
                Mood.STARRY, Mood.NIGHT, Mood.DESERT_NIGHT, Mood.MOON_GARDEN, Mood.SPORELIGHT, Mood.NEON ->
                    if (hash(cx, cy, 137) < 0.35f && on && tx == px && ty == py) Palette.SHALLOW else 0
                Mood.AURORA ->
                    if (hash(cx, cy, 137) < 0.4f && on && tx == px && ty == py) Palette.lerp(Palette.SHALLOW, 0xFF70E8A8.toInt(), 0.5f) else 0
                Mood.COLD, Mood.FIRST_FROST ->
                    if (hash(cx, cy, 137) < 0.3f && ty == py && tx >= px && tx < px + 2 + (hash(cx, cy, 138) * 2).toInt()) Palette.lerp(Palette.MID, Palette.SHOAL, 0.5f) else 0
                Mood.CAVE, Mood.DEEP_CAVE ->
                    if (hash(cx, cy, 137) < 0.3f && on && tx == px && ty == py) Palette.lerp(Palette.DEEP, 0xFF7AD0E0.toInt(), 0.55f) else 0
                else -> 0
            }
        }

        private var farClouds: Bitmap? = null
        private var farMood: Mood? = null

        /**
         * Skyreach's far cloud layer: faint wisps on a tile half the sea's size, drawn at half
         * the camera's speed. Half-size, so the glide between holes (which lines the sea up)
         * lines this up too.
         */
        fun farClouds(): Bitmap {
            farClouds?.let { if (farMood == Palette.mood) return it }
            val w = SEA_W / 2
            val h = SEA_H / 2
            val px = IntArray(w * h)
            val col = Palette.alpha(0xFFFFFFFF.toInt(), 40)
            for (y in 0 until h) for (x in 0 until w) {
                val k = hash((x + y / 2) / 9, y / 6, 140)
                if (k < 0.18f && (x + y) % 2 == 0) px[y * w + x] = col
            }
            return Pix.bitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }.also { farClouds = it; farMood = Palette.mood }
        }

        /** Open sky far below: haze with cloud banks, repeating every [SEA_W]×[SEA_H] so it tiles. */
        fun skyAt(x: Int, y: Int): Int {
            val tx = Math.floorMod(x, SEA_W)
            val ty = Math.floorMod(y, SEA_H)
            val base = Palette.lerp(Palette.SEA[3], Palette.SEA[4], 0.7f)
            val h = hash((tx + ty / 3) / 13, ty / 8, 105)
            return if (h < 0.22f && (tx + ty) % 3 != 0) Palette.lerp(base, 0xFFFFFFFF.toInt(), 0.55f) else base
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
        private const val K_TIDAL: Byte = 9
        private const val K_MUD: Byte = 10
        private const val K_DECK: Byte = 11
        private const val K_HULL: Byte = 12
        private const val K_SHALLOW: Byte = 13
        private const val K_VENT: Byte = 14
        private const val K_OBSIDIAN: Byte = 15
        private const val K_ICE: Byte = 16
        private const val K_THIN: Byte = 17
        private const val K_SNOW: Byte = 18
        private const val K_PLATE: Byte = 19
        private const val K_TEMPLE: Byte = 20
        private const val K_VINES: Byte = 21
        private const val K_LEDGE: Byte = 22
        private const val K_RIDGE: Byte = 23
        private const val K_PAD: Byte = 24
        private const val K_BOOST: Byte = 25
        private const val K_WELL: Byte = 26
        private const val K_QUICK: Byte = 27
        private val AUTUMN_LEAF = 0xFFD8702A.toInt()
        private val MOSS = 0xFF4A5A7A.toInt()
        private val CRYSTAL = 0xFF9A6AE0.toInt()
        private val LAVA_CRUST = 0xFF3A1A20.toInt()
        private val LAVA_HOT = 0xFFC8402A.toInt()
        private val LAVA_BRIGHT = 0xFFF07A30.toInt()
        private val LAVA_WHITE = 0xFFFFD27A.toInt()

        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        fun bayer(x: Int, y: Int): Int = BAYER[(y and 3) * 4 + (x and 3)]

        fun hash(x: Int, y: Int, s: Int): Float = com.theprobyte.golfalone.game.Noise.hash(x, y, s)
    }
}

package com.theprobyte.golfalone.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.HoleState
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import com.theprobyte.golfalone.game.Windmill
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The things on a hole that aren't baked into its map: bumper buoys, coral heads, hermit
 * crabs (with footprints where they'll step next), barrels, cracked planks, and the night's
 * lighthouse and lanterns. All drawn as pixels so they take the time of day's colours.
 */
object Bodies {
    private val CRAB = 0xFFE0603C.toInt()
    private val CRAB_DARK = 0xFFA8382E.toInt()
    private val CORAL = 0xFFE87A8A.toInt()
    private val CORAL_LIGHT = 0xFFF5B0B8.toInt()
    private val CORAL_DARK = 0xFFA84858.toInt()
    private val LAMP = 0xFFFFE6A0.toInt()
    private val LOBES = intArrayOf(-2, 0, 1, -1, 0, 2, 2, 1)
    private val PORTAL_A = 0xFF5FD8E8.toInt()
    private val PORTAL_B = 0xFFE868D0.toInt()
    private val PULSE_A = 0xFFE8803A.toInt()
    private val PULSE_B = 0xFF3AB8A8.toInt()
    private val shadow get() = Palette.alpha(Palette.INK_SHADOW, 90)

    // ------------------------------------------------------------ baked pieces

    /**
     * Pieces are drawn once into a small bitmap per time of day and course style, then stamped
     * each frame: one draw instead of dozens of pixels.
     */
    private val cache = HashMap<Long, android.graphics.Bitmap>()
    private var cacheMood: Mood? = null
    private val bakeCanvas = Canvas()
    private var styleCode = 0L

    private fun key(kind: Int, variant: Int): Long = (kind.toLong() shl 40) or (styleCode shl 32) or (variant.toLong() and 0xffffffffL)

    /** Stamps the piece [key] with its anchor at ([x], [y]), drawing it into a [w]×[h] bitmap (anchor at [ox], [oy]) the first time. */
    private inline fun stamp(c: Canvas, key: Long, x: Int, y: Int, w: Int, h: Int, ox: Int, oy: Int, draw: (Canvas) -> Unit) {
        if (cacheMood != Palette.mood) { cache.clear(); cacheMood = Palette.mood }
        var b = cache[key]
        if (b == null) {
            b = Pix.bitmap(w, h)
            bakeCanvas.setBitmap(b)
            draw(bakeCanvas)
            bakeCanvas.setBitmap(null)
            cache[key] = b
        }
        Pix.calls++
        c.drawBitmap(b, (x - ox).toFloat(), (y - oy).toFloat(), Sprites.blit)
    }

    private fun styleOf(style: String): Long = when (style) {
        "" -> 0L; "ember" -> 1L; "frost" -> 2L; "desert" -> 3L; "mushroom" -> 4L; "carnival" -> 5L; "sky" -> 6L
        "clock" -> 7L; "mangrove" -> 8L; "crystal" -> 9L; "autumn" -> 10L; "zen" -> 11L; else -> 12L
    }

    private var viewW = 4096
    private var viewH = 4096
    private val PULSES = arrayOf(Tile.PULSE_A, Tile.PULSE_B)

    /** Whether something drawn at ([x], [y]) on screen (top left of its tile) could show. */
    private fun on(x: Int, y: Int): Boolean = x > -24 && y > -24 && x < viewW + 24 && y < viewH + 24

    /** Shadows and ground-level bodies, drawn under the ball; only what's within [vw] by [vh] of the view. */
    fun draw(
        c: Canvas, hole: Hole, st: HoleState, ix: Int, iy: Int, time: Float,
        crabFromX: FloatArray, crabFromY: FloatArray, crabK: Float, showSteps: Boolean,
        bumpX: Float, bumpY: Float, bumpT: Float, vw: Int = 4096, vh: Int = 4096,
        pulseK: Float = 1f, gateK0: Float = 1f, gateK1: Float = 1f,
    ) {
        viewW = vw
        viewH = vh
        styleCode = styleOf(hole.style)
        // Belts: ridges riding along the way the belt runs.
        if (hole.belts) for (i in hole.currentTiles) {
            val t = hole.tiles[i]
            val tx = i % hole.w
            val ty = i / hole.w
            if (!on(tx * TILE - ix, ty * TILE - iy)) continue
            val horiz = t.flowX != 0f
            val sign = if (horiz) t.flowX else t.flowY
            val lo = if (horiz) hole.tile(tx, ty - 1) else hole.tile(tx - 1, ty)
            val hi = if (horiz) hole.tile(tx, ty + 1) else hole.tile(tx + 1, ty)
            val b0 = if (lo.flows) 0 else 2
            val b1 = if (hi.flows) TILE else TILE - 1
            for (k in 0 until TILE / 4) {
                val along = ((k * 4 + time * 14f * sign) % TILE + TILE) % TILE
                val a = along.toInt()
                if (horiz) rect(c, tx * TILE + a - ix, ty * TILE + b0 - iy, 1, b1 - b0, Palette.STONES[2])
                else rect(c, tx * TILE + b0 - ix, ty * TILE + a - iy, b1 - b0, 1, Palette.STONES[2])
            }
        }
        // Currents: streaks of foam drifting the way the water runs.
        if (!hole.belts) for (i in hole.currentTiles) {
            val t = hole.tiles[i]
            val tx = i % hole.w
            val ty = i / hole.w
            if (!on(tx * TILE - ix, ty * TILE - iy)) continue
            for (s in 0 until 2) {
                val h = Terrain.hash(tx * 2 + s, ty, 73)
                val along = ((time * 14f + h * TILE * 3) % TILE).toInt()
                val across = 1 + ((Terrain.hash(tx, ty * 2 + s, 74) * 6).toInt())
                val ox = if (t.flowX != 0f) (if (t.flowX > 0) along else TILE - 1 - along) else across
                val oy = if (t.flowY != 0f) (if (t.flowY > 0) along else TILE - 1 - along) else across
                val x = tx * TILE + ox - ix
                val y = ty * TILE + oy - iy
                val col = Palette.alpha(Palette.SEA[5], 210)
                px(c, x, y, col)
                px(c, x - t.flowX.toInt(), y - t.flowY.toInt(), Palette.alpha(Palette.SEA[5], 120))
            }
        }

        // Portals: swirling rings, each pair its own colour.
        for (i in hole.tilesOf(Tile.PORTAL)) {
            val x = (i % hole.w) * TILE + 4 - ix
            val y = (i / hole.w) * TILE + 4 - iy
            if (!on(x, y)) continue
            val col = if (hole.portalOf[i] == 0) PORTAL_A else PORTAL_B
            stamp(c, key(15, hole.portalOf[i]), x, y, 11, 11, 5, 5) { b -> portal(b, 5, 5, col) }
            for (s in 0 until 3) {
                val a = time * 3f + s * 2.094f
                px(c, (x + cos(a) * 2.5f).roundToInt(), (y + sin(a) * 2.5f).roundToInt(), Palette.PAPER[3])
            }
        }
        // Quicksand: a slow swirl turning in to the middle of each patch.
        for (i in hole.tilesOf(Tile.QUICKSAND)) {
            if (Terrain.hash(i, 0, 108) > 0.5f) continue
            val cx = hole.quickX[i] - ix
            val cy = hole.quickY[i] - iy
            val tx = (i % hole.w) * TILE + 4f - ix
            val ty = (i / hole.w) * TILE + 4f - iy
            if (!on(tx.toInt(), ty.toInt())) continue
            val r = kotlin.math.hypot(tx - cx, ty - cy) * (1f - (time * 0.25f + Terrain.hash(i, 1, 108)) % 1f) + 1f
            val a = kotlin.math.atan2(ty - cy, tx - cx) + time * 0.8f
            px(c, (cx + cos(a) * r).roundToInt(), (cy + sin(a) * r).roundToInt(), Palette.SANDS[1])
        }
        // Gravity wells: sparks falling in.
        var wk = 0
        while (wk < hole.wells.size) {
            val cx = hole.wells[wk] - ix
            val cy = hole.wells[wk + 1] - iy
            if (on(cx.toInt(), cy.toInt())) for (s in 0 until 4) {
                val ph = (time * 0.5f + s * 0.25f) % 1f
                val r = 14f * (1f - ph) + 1f
                val a = s * 1.571f + ph * 4f
                px(c, (cx + cos(a) * r).roundToInt(), (cy + sin(a) * r).roundToInt(), Palette.alpha(Palette.PAPER[3], (220 * ph).toInt() + 30))
            }
            wk += 2
        }
        // Pulse walls: up, a coloured block; down, an outline pulsing (it rises after this shot).
        for (t in PULSES) {
            val col = if (t == Tile.PULSE_A) PULSE_A else PULSE_B
            val up = st.pulseUp(t)
            for (i in hole.tilesOf(t)) {
                val x = (i % hole.w) * TILE - ix
                val y = (i / hole.w) * TILE - iy
                if (!on(x, y)) continue
                val set = if (t == Tile.PULSE_A) 0 else 1
                if (!up) {
                    val level = ((sin(time * 4f) + 1f) * 3.5f).toInt().coerceIn(0, 7)
                    stamp(c, key(9, set * 8 + level), x, y, TILE, TILE, 0, 0) { b -> pulseDown(b, 0, 0, col, 150 + (level - 3.5f) / 3.5f * 90f, hole.style) }
                }
                // Mid-swap the block rises out of (or sinks into) the ground.
                val shown = if (pulseK >= 1f) (if (up) 1f else 0f) else if (up) pulseK else 1f - pulseK
                if (shown <= 0f) continue
                if (shown < 1f) { c.save(); c.clipRect(x, y + ((1f - shown) * TILE).roundToInt(), x + TILE + 1, y + TILE) }
                stamp(c, key(8, set), x, y, TILE + 1, TILE, 0, 0) { b -> pulseUp(b, 0, 0, col, hole.style) }
                if (shown < 1f) c.restore()
            }
        }

        // Wind on the ridges: quick pale streaks the way it blows.
        if (hole.windDeg != null) for (i in hole.tilesOf(Tile.RIDGE)) {
            val tx = i % hole.w
            val ty = i / hole.w
            if (Terrain.hash(tx, ty, 86) > 0.45f || !on(tx * TILE - ix, ty * TILE - iy)) continue
            val ph = ((time * 1.6f + Terrain.hash(tx, ty, 87)) % 1f)
            val x = (tx * TILE + 4 + hole.windX * (ph * 12f - 6f)).roundToInt() - ix
            val y = (ty * TILE + 4 + hole.windY * (ph * 12f - 6f)).roundToInt() - iy
            val col = Palette.alpha(Palette.PAPER[3], (170 * kotlin.math.sin(ph * Math.PI.toFloat())).toInt())
            px(c, x, y, col)
            px(c, x - hole.windX.roundToInt(), y - hole.windY.roundToInt(), col)
            px(c, x - 2 * hole.windX.roundToInt(), y - 2 * hole.windY.roundToInt(), Palette.alpha(Palette.PAPER[3], 60))
        }
        // Vents breathe steam.
        for (i in hole.tilesOf(Tile.VENT)) {
            val x = (i % hole.w) * TILE + 4 - ix
            val y = (i / hole.w) * TILE + 4 - iy
            if (!on(x, y)) continue
            for (s in 0 until 3) {
                val ph = ((time * 0.8f + s / 3f + Terrain.hash(i, s, 88)) % 1f)
                val sx = x + (kotlin.math.sin(ph * 6f + s) * 1.5f).roundToInt()
                val sy = y - (ph * 9f).roundToInt()
                px(c, sx, sy, Palette.alpha(Palette.PAPER[3], (150 * (1f - ph)).toInt()))
            }
        }
        // Plates: a glyph for their gates, lit once pressed (a decoy has none).
        for (i in hole.tilesOf(Tile.PLATE)) {
            val g = hole.plateOf[i]
            val x = (i % hole.w) * TILE - ix
            val y = (i / hole.w) * TILE - iy
            if (!on(x, y)) continue
            if (g < 0) { px(c, x + 3, y + 3, Palette.STONES[1]); px(c, x + 4, y + 4, Palette.STONES[1]); continue }
            val lit = st.gates[g]
            stamp(c, key(11, g * 2 + (if (lit) 1 else 0)), x, y, TILE, TILE, 0, 0) { b ->
                rect(b, 1, 1, TILE - 2, TILE - 2, if (lit) Palette.SANDS[2] else Palette.SANDS[1])
                glyph(b, 2, 2, g, true)
            }
        }
        // Gates: a carved stone slab while shut; rubble once open.
        for (i in hole.tilesOf(Tile.GATE)) {
            val g = hole.plateOf[i]
            val tx = i % hole.w
            val ty = i / hole.w
            val x = tx * TILE - ix
            val y = ty * TILE - iy
            if (!on(x, y)) continue
            val sinking = if (g == 0) gateK0 else gateK1
            if (st.gates[g]) {
                px(c, x + 1, y + 6, Palette.STONES[1]); px(c, x + 5, y + 2, Palette.STONES[1]); px(c, x + 6, y + 5, Palette.STONES[3])
                if (sinking >= 1f) continue
                // Just opened: the slab sinks into the ground.
                c.save(); c.clipRect(x, y, x + TILE + 1, y + TILE)
                c.translate(0f, (sinking * TILE).roundToInt().toFloat())
            }
            // Sandstone, so a gate never reads as plain wall.
            stamp(c, key(10, g), x, y, TILE + 1, TILE, 0, 0) { b ->
                rect(b, 1, 6, TILE, 2, shadow)
                rect(b, 0, 0, TILE, 5, Palette.SANDS[3])
                rect(b, 0, 5, TILE, 3, Palette.SANDS[1])
                rect(b, 0, 0, TILE, 1, Palette.SANDS[4])
                px(b, TILE - 1, 1, Palette.SANDS[2])
                glyph(b, 2, 1, g, false)
            }
            if (st.gates[g]) c.restore()
        }
        // Tunnels: a dark pit for the mouth, an arch where it comes out.
        for (i in hole.tilesOf(Tile.TUNNEL_IN)) {
            val x = (i % hole.w) * TILE - ix
            val y = (i / hole.w) * TILE - iy
            stamp(c, key(13, 0), x, y, TILE, TILE, 0, 0) { b ->
                Pix.oval(b, 4f, 4f, 3.5f, 3f, Palette.STONES[3])
                Pix.oval(b, 4f, 4.5f, 2.5f, 2f, 0xFF0E0A12.toInt())
            }
        }
        for (k in 0..1) {
            if (hole.exitX[k] == 0f) continue
            val x = (hole.exitX[k] - 4f).roundToInt() - ix - (hole.exitDX[k] * 4f).roundToInt()
            val y = (hole.exitY[k] - 4f).roundToInt() - iy - (hole.exitDY[k] * 4f).roundToInt()
            stamp(c, key(14, 0), x, y, TILE, TILE, 0, 0) { b ->
                Pix.oval(b, 4f, 4f, 3.5f, 3.5f, Palette.STONES[3])
                Pix.oval(b, 4f, 4.5f, 2.5f, 2.5f, 0xFF0E0A12.toInt())
            }
        }
        // Thin ice: hairline cracks once it's been crossed; open water once it's gone.
        if (hole.thinCount > 0) for (i in hole.tilesOf(Tile.THIN_ICE)) {
            val k = hole.thinOf[i]
            val tx = i % hole.w
            val ty = i / hole.w
            val x = tx * TILE - ix
            val y = ty * TILE - iy
            if (!on(x, y)) continue
            if (st.thinOpen[k]) {
                rect(c, x, y, TILE, TILE, Palette.SEA[2])
                for (u in 0 until TILE) {
                    if (Terrain.hash(tx * 8 + u, ty, 89) < 0.5f && !st.thinOpenAt(tx, ty - 1)) px(c, x + u, y, Palette.PAPER[3])
                    if (Terrain.hash(tx, ty * 8 + u, 90) < 0.5f && !st.thinOpenAt(tx - 1, ty)) px(c, x, y + u, Palette.PAPER[3])
                }
                px(c, x + 3, y + 4, Palette.SEA[4]); px(c, x + 5, y + 2, Palette.SEA[4])
            } else if (st.cracked[k]) {
                val col = Palette.PAPER[3]
                var cx = x + 1
                var cy = y + 3
                for (s in 0 until 7) { px(c, cx, cy, col); cx++; cy += if (Terrain.hash(tx * 7 + s, ty, 91) < 0.5f) 1 else -1; cy = cy.coerceIn(y, y + TILE - 1) }
                px(c, x + 4, y + 1, col); px(c, x + 4, y + 2, col)
            }
        }

        // Cracked planks still standing: a board wall with a split down it.
        if (hole.plankCount > 0) for (i in hole.tilesOf(Tile.PLANK)) {
            val tx = i % hole.w
            val ty = i / hole.w
            val g = hole.plankOf[i]
            val x = tx * TILE - ix
            val y = ty * TILE - iy
            if (!on(x, y)) continue
            if (st.broken[g]) splinters(c, x, y, tx, ty)
            else {
                val across = hole.plankAt(tx - 1, ty) >= 0 || hole.plankAt(tx + 1, ty) >= 0
                val crack = (tx * 7 + ty * 3) % 3
                stamp(c, key(7, (if (across) 4 else 0) + crack), x, y, TILE + 1, TILE, 0, 0) { b -> plank(b, 0, 0, across, crack) }
            }
        }
        var k = 0
        while (k < hole.corals.size) {
            val x = hole.corals[k].roundToInt() - ix
            val y = hole.corals[k + 1].roundToInt() - iy
            if (on(x, y)) stamp(c, key(1, 0), x, y, 11, 10, 5, 4) { b -> coral(b, 5, 4, 0) }
            k += 2
        }
        k = 0
        while (k < hole.buoys.size) {
            val bx = hole.buoys[k]
            val by = hole.buoys[k + 1]
            if (!on(bx.roundToInt() - ix, by.roundToInt() - iy)) { k += 2; continue }
            val hit = bumpT in 0f..0.15f && kotlin.math.hypot(bx - bumpX, by - bumpY) < 9f
            val x = bx.roundToInt() - ix
            val y = by.roundToInt() - iy
            val h = if (hit) 1 else 0
            when (hole.style) {
                "mushroom" -> stamp(c, key(2, h), x, y, 13, 11, 6, 5) { b -> mushroom(b, 6, 5, hit) }
                "carnival", "clock" -> {
                    // Carnival bumpers chase their lights round; the clockwork's stay put.
                    val chase = if (hole.style == "carnival") (time * 3f).toInt() else 0
                    val col = (k / 2 + chase) % 3
                    stamp(c, key(3, h * 4 + col), x, y, 13, 13, 6, 6) { b -> bumper(b, 6, 6, hit, col) }
                }
                else -> stamp(c, key(4, h), x, y, 11, 11, 5, 5) { b -> buoy(b, 5, 5, hit) }
            }
            k += 2
        }
        for (i in hole.crabs.indices) {
            if (showSteps && (st.crabNextX(i) != st.crabX(i) || st.crabNextY(i) != st.crabY(i))) {
                footprints(c, st.crabNextX(i).roundToInt() - ix, st.crabNextY(i).roundToInt() - iy)
            }
            val cx = crabFromX[i] + (st.crabX(i) - crabFromX[i]) * crabK
            val cy = crabFromY[i] + (st.crabY(i) - crabFromY[i]) * crabK
            val walking = crabK < 1f
            val step = walking && ((time * 12f).toInt() % 2 == 0)
            val left = (st.crabX(i) - crabFromX[i]) < 0f
            stamp(c, key(5, (if (step) 2 else 0) + (if (left) 1 else 0)), cx.roundToInt() - ix, cy.roundToInt() - iy, 11, 9, 5, 4) { b -> crab(b, 5, 4, step, left) }
        }
        for (b in st.bx.indices) if (!st.sunk[b]) {
            val x = st.bx[b].roundToInt() - ix
            val y = st.by[b].roundToInt() - iy
            stamp(c, key(6, 0), x, y, 11, 10, 5, 4) { bc -> barrel(bc, 5, 4) }
            // A stave line that turns while it rolls.
            val a = if (st.bvx[b] + st.bvy[b] != 0f) time * 8f else 0.6f
            px(c, x + (cos(a) * 1.5f).roundToInt(), y + (sin(a) * 1.5f).roundToInt(), Palette.WOODS[4])
        }
    }

    private fun plank(c: Canvas, x: Int, y: Int, across: Boolean, crack: Int) {
        // 3/4 view: a pale top, a darker face, a jagged crack.
        rect(c, x + 1, y + 6, TILE, 2, shadow)
        rect(c, x, y, TILE, 5, Palette.WOODS[3])
        rect(c, x, y + 5, TILE, 3, Palette.WOODS[1])
        rect(c, x, y, TILE, 1, Palette.WOODS[4])
        if (across) { for (u in 0 until TILE step 3) rect(c, x + u, y + 1, 1, 4, Palette.WOODS[2]) }
        val crackX = x + 3 + crack
        px(c, crackX, y + 1, Palette.WOODS[0]); px(c, crackX + 1, y + 2, Palette.WOODS[0]); px(c, crackX, y + 3, Palette.WOODS[0])
        px(c, crackX - 1, y + 4, Palette.WOODS[0]); px(c, crackX, y + 5, Palette.WOODS[0]); px(c, crackX + 1, y + 6, Palette.WOODS[0])
    }

    private fun splinters(c: Canvas, x: Int, y: Int, tx: Int, ty: Int) {
        for (s in 0 until 4) {
            val h = Terrain.hash(tx * 4 + s, ty, 71)
            val sx = x + (h * 7).toInt()
            val sy = y + (Terrain.hash(tx, ty * 4 + s, 72) * 7).toInt()
            px(c, sx, sy, Palette.WOODS[2]); px(c, sx + 1, sy, Palette.WOODS[3])
        }
    }

    private fun coral(c: Canvas, x: Int, y: Int, seed: Int) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 3.5f, 1.5f, shadow)
        for (n in 0 until 4) {
            val lx = LOBES[2 * n]
            val ly = LOBES[2 * n + 1]
            val r = if (n == 0) 2 else 1
            rect(c, x + lx - r, y + ly - r, 2 * r + 1, 2 * r + 1, if ((n + seed) % 2 == 0) CORAL else CORAL_DARK)
            px(c, x + lx, y + ly - r, CORAL_LIGHT)
        }
        px(c, x, y - 2, CORAL_LIGHT); px(c, x - 1, y + 1, CORAL_LIGHT)
    }

    private fun buoy(c: Canvas, x: Int, y: Int, hit: Boolean) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 4f, 2f, shadow)
        val r2 = if (hit) 14 else 11
        for (dy in -4..4) for (dx in -4..4) {
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            val band = dy in -1..0
            var col = if (band) Palette.PAPER[3] else Palette.FLAG
            if (dx >= 2 || dy >= 3) col = if (band) Palette.PAPER[1] else Palette.FLAG_DARK
            if (d2 >= r2 - 3) col = Palette.FLAG_DARK
            px(c, x + dx, y + dy, col)
        }
        px(c, x - 1, y - 2, Palette.PAPER[3]); px(c, x - 2, y - 1, Palette.PAPER[3])
        px(c, x, y - 4, Palette.STONES[3]) // the bell's ring
    }

    /** A big springy mushroom (the hollow's bumper). */
    private fun mushroom(c: Canvas, x: Int, y: Int, hit: Boolean) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 4f, 2f, shadow)
        rect(c, x - 1, y, 3, 3, Palette.PAPER[2])
        val r = if (hit) 5f else 4.2f
        Pix.oval(c, x + 0.5f, y - 0.5f, r, r * 0.7f, 0xFFB050C0.toInt())
        Pix.oval(c, x + 0.5f, y - 1.5f, r - 1.2f, r * 0.45f, 0xFFD878E0.toInt())
        px(c, x - 2, y - 2, Palette.PAPER[3]); px(c, x + 2, y - 1, Palette.PAPER[3]); px(c, x, y - 3, Palette.PAPER[3])
    }

    /** A pinball bumper: a coloured ring with a lit cap. */
    private fun bumper(c: Canvas, x: Int, y: Int, hit: Boolean, seed: Int) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 4f, 2f, shadow)
        val col = when (seed) { 0 -> Palette.FLAG; 1 -> Palette.SEA[3]; else -> Palette.GOLD }
        Pix.oval(c, x + 0.5f, y + 0.5f, if (hit) 4.6f else 4f, if (hit) 4.6f else 4f, Palette.darker(col))
        Pix.oval(c, x + 0.5f, y + 0.5f, 3f, 3f, if (hit) Palette.PAPER[3] else col)
        Pix.oval(c, x + 0.5f, y - 0.5f, 1.5f, 1.2f, Palette.PAPER[3])
    }

    /** A cactus (the oasis's palm): a ribbed column with two arms. */
    fun cactus(c: Canvas, x: Int, y: Int) {
        Pix.oval(c, x + 3f, y + 2f, 4f, 1.6f, shadow)
        val g = Palette.GRASS[3]
        val d = Palette.GRASS[1]
        rect(c, x - 1, y - 11, 3, 13, g)
        rect(c, x + 1, y - 11, 1, 13, d)
        rect(c, x - 4, y - 7, 2, 4, g); rect(c, x - 4, y - 4, 3, 1, g)
        rect(c, x + 3, y - 9, 2, 4, g); rect(c, x + 2, y - 6, 3, 1, d)
        px(c, x, y - 12, 0xFFF29AC0.toInt()) // a flower on top
    }

    // ------------------------------------------------------------ each course's tree (where the palms stand)

    private val TREES = setOf("mushroom", "carnival", "sky", "clock", "mangrove", "crystal", "zen")

    /** Whether [style] has its own tree in place of the palm. */
    fun ownTree(style: String) = style in TREES

    /** The part of the tree under the ball: its shadow and trunk (or stem, pole, roots). */
    fun treeBase(c: Canvas, style: String, x: Int, y: Int) {
        styleCode = styleOf(style)
        stamp(c, key(18, 0), x, y, 24, 22, 11, 17) { b -> treeBaseArt(b, style, 11, 17) }
    }

    /** The part over the ball: cap, crown or lamp, swaying a pixel; [frame] turns gears and glints crystals. */
    fun treeTop(c: Canvas, style: String, x: Int, y: Int, sway: Int, frame: Int = 0) {
        styleCode = styleOf(style)
        val f = if (style == "clock" || style == "crystal") frame and 3 else 0
        stamp(c, key(19, f * 4 + sway + 1), x, y, 24, 22, 11, 19) { b -> treeTopArt(b, style, 11 + sway, 19, f) }
    }

    /** Whether the ball at ([bx], [by]) is hidden by the tree standing at ([x], [y]). */
    fun treeHides(style: String, bx: Float, by: Float, x: Float, y: Float): Boolean = when (style) {
        "mushroom", "mangrove", "zen" -> bx > x - 6f && bx < x + 6f && by > y - 14f && by < y - 6f
        "sky" -> bx > x - 4f && bx < x + 4f && by > y - 15f && by < y - 6f
        else -> false
    }

    private fun treeBaseArt(c: Canvas, style: String, x: Int, y: Int) {
        Pix.oval(c, x + 3f, y + 1.5f, 6f, 2f, shadow)
        when (style) {
            "mushroom" -> { rect(c, x - 1, y - 8, 3, 9, Palette.PAPER[2]); rect(c, x + 1, y - 8, 1, 9, Palette.PAPER[1]) }
            "carnival" -> { // a striped lamp post
                for (k in 0 until 14) px(c, x, y - k, if ((k / 2) % 2 == 0) Palette.FLAG else Palette.PAPER[3])
                rect(c, x - 1, y, 3, 1, Palette.STONES[1])
            }
            "sky" -> rect(c, x, y - 6, 1, 7, Palette.WOODS[1])
            "clock" -> { // a brass pylon
                rect(c, x - 1, y - 12, 3, 13, Palette.lerp(Palette.SANDS[3], Palette.WOODS[3], 0.4f))
                rect(c, x + 1, y - 12, 1, 13, Palette.WOODS[1])
                rect(c, x - 2, y, 5, 1, Palette.WOODS[0])
            }
            "mangrove" -> { // a trunk on arching stilt roots
                rect(c, x, y - 9, 2, 5, Palette.WOODS[1])
                for (k in 0..4) { px(c, x - k, y - 4 + k, Palette.WOODS[2]); px(c, x + 1 + k, y - 4 + k, Palette.WOODS[1]) }
                px(c, x - 2, y - 4, Palette.WOODS[2]); px(c, x + 3, y - 4, Palette.WOODS[1])
            }
            "crystal" -> { // a spire of crystal
                val cy = 0xFF7AD0E0.toInt()
                rect(c, x - 1, y - 12, 3, 13, cy)
                rect(c, x - 1, y - 12, 1, 13, 0xFFC8F4FA.toInt())
                rect(c, x + 2, y - 6, 2, 7, Palette.darker(cy))
                rect(c, x - 3, y - 4, 2, 5, Palette.lerp(cy, Palette.PAPER[3], 0.3f))
                px(c, x, y - 13, 0xFFE8FCFF.toInt())
            }
            "zen" -> rect(c, x, y - 6, 2, 7, Palette.WOODS[1])
        }
    }

    private fun treeTopArt(c: Canvas, style: String, x: Int, y: Int, frame: Int) {
        when (style) {
            "mushroom" -> { // a giant cap with spots
                Pix.oval(c, x + 0.5f, y - 10.5f, 7f, 4f, 0xFF8A3898.toInt())
                Pix.oval(c, x + 0.5f, y - 11.5f, 6f, 3f, 0xFFB050C0.toInt())
                Pix.oval(c, x - 1.5f, y - 12.5f, 3f, 1.5f, 0xFFD878E0.toInt())
                px(c, x - 3, y - 11, Palette.PAPER[3]); px(c, x + 3, y - 12, Palette.PAPER[3]); px(c, x + 1, y - 9, Palette.PAPER[3])
            }
            "carnival" -> { // the lamp, with its glow
                Pix.oval(c, x + 0.5f, y - 15.5f, 5f, 4f, Palette.alpha(Palette.GOLD, 60))
                rect(c, x - 2, y - 17, 5, 3, Palette.GOLD)
                rect(c, x - 1, y - 18, 3, 1, Palette.STONES[1])
                px(c, x - 1, y - 16, Palette.PAPER[3])
            }
            "sky" -> { // a pine bent by the wind
                val tiers = intArrayOf(1, 2, 3, 4, 4, 5)
                for ((k, half) in tiers.withIndex()) {
                    val yy = y - 16 + k * 2
                    val lean = (5 - k) / 2
                    rect(c, x - half + lean, yy, half * 2 + 1, 2, if (k % 2 == 0) Palette.LEAF[2] else Palette.LEAF[1])
                    px(c, x - half + lean, yy, Palette.LEAF[3])
                }
            }
            "clock" -> { // a gear on top
                val brass = Palette.lerp(Palette.SANDS[3], Palette.WOODS[3], 0.4f)
                Pix.oval(c, x + 0.5f, y - 13.5f, 3.5f, 3.5f, brass)
                Pix.oval(c, x + 0.5f, y - 13.5f, 1.2f, 1.2f, Palette.WOODS[0])
                for (k in 0 until 8) {
                    val a = k * 0.785f + frame * 0.196f
                    px(c, (x + 0.5f + cos(a) * 4.5f).roundToInt(), (y - 13.5f + sin(a) * 4.5f).roundToInt(), brass)
                }
            }
            "mangrove" -> { // a dark, glossy crown
                Pix.oval(c, x + 0.5f, y - 11.5f, 7f, 4.5f, Palette.LEAF[0])
                Pix.oval(c, x - 0.5f, y - 12.5f, 6f, 3.5f, Palette.LEAF[1])
                Pix.oval(c, x - 2.5f, y - 13.5f, 3f, 1.5f, Palette.LEAF[2])
            }
            "crystal" -> if (frame == 0) { // a glint running up the spire
                px(c, x - 1, y - 9, 0xFFFFFFFF.toInt()); px(c, x, y - 10, 0xFFE8FCFF.toInt())
            } else if (frame == 2) px(c, x + 2, y - 5, 0xFFFFFFFF.toInt())
            "zen" -> { // a cherry tree in blossom
                Pix.oval(c, x + 0.5f, y - 10.5f, 7f, 4.5f, 0xFFC07090.toInt())
                Pix.oval(c, x - 0.5f, y - 11.5f, 6f, 3.5f, 0xFFE89AB8.toInt())
                Pix.oval(c, x - 2.5f, y - 12.5f, 3f, 1.5f, 0xFFF8C8D8.toInt())
                px(c, x + 4, y - 8, 0xFFF8C8D8.toInt()); px(c, x - 5, y - 9, 0xFFF8C8D8.toInt())
            }
        }
    }

    /** An autumn tree's crown: a round mass of orange and red leaves. */
    fun autumnCanopy(c: Canvas, x: Int, y: Int, sway: Int) {
        val cx = x + sway
        Pix.oval(c, cx + 0.5f, y - 9.5f, 7f, 5.5f, 0xFF8A3A1A.toInt())
        Pix.oval(c, cx - 0.5f, y - 10.5f, 6f, 4.5f, 0xFFD8702A.toInt())
        Pix.oval(c, cx - 1.5f, y - 11.5f, 3.5f, 2.5f, 0xFFF0A040.toInt())
        px(c, cx + 3, y - 8, 0xFFB83A2A.toInt()); px(c, cx - 4, y - 9, 0xFFB83A2A.toInt())
    }

    private fun footprints(c: Canvas, x: Int, y: Int) {
        val col = Palette.alpha(Palette.INK_SHADOW, 110)
        px(c, x - 2, y - 1, col); px(c, x - 2, y + 1, col); px(c, x + 2, y - 1, col); px(c, x + 2, y + 1, col)
        px(c, x - 1, y, col); px(c, x + 1, y, col)
    }

    /** A hermit crab in a pale shell; legs scuttle while it walks. */
    private fun crab(c: Canvas, x: Int, y: Int, step: Boolean, left: Boolean) {
        Pix.oval(c, x + 1f, y + 2.5f, 3.5f, 1.5f, shadow)
        // Legs and claws.
        val ly = if (step) 1 else 0
        px(c, x - 3, y + ly, CRAB_DARK); px(c, x + 3, y + 1 - ly, CRAB_DARK)
        px(c, x - 3, y + 2 - ly, CRAB_DARK); px(c, x + 3, y + 2 + ly - 1, CRAB_DARK)
        val fx = if (left) x - 3 else x + 3
        rect(c, fx - 1, y - 2, 2, 2, CRAB)
        // The shell, with its spiral.
        rect(c, x - 2, y - 2, 5, 4, Palette.SHELL_PINK)
        rect(c, x - 1, y - 3, 3, 1, Palette.PAPER[3])
        px(c, x, y - 1, Palette.SANDS[1]); px(c, x + 1, y, Palette.SANDS[1]); px(c, x - 1, y, Palette.PAPER[3])
        rect(c, x - 2, y + 1, 5, 1, Palette.SANDS[1])
        // Eyes.
        px(c, fx, y - 3, Palette.INK_SHADOW)
    }

    private fun barrel(c: Canvas, x: Int, y: Int) {
        Pix.oval(c, x + 1.5f, y + 2.5f, 4f, 2f, shadow)
        for (dy in -3..3) for (dx in -3..3) {
            val d2 = dx * dx + dy * dy
            if (d2 > 11) continue
            var col = Palette.WOODS[3]
            if (d2 in 5..7) col = Palette.WOODS[1] // the hoop
            if (d2 >= 10) col = Palette.WOODS[0]
            if (dx + dy <= -3 && d2 < 5) col = Palette.WOODS[4]
            px(c, x + dx, y + dy, col)
        }
    }

    /** A gate group's glyph: a sun for 'G' (gold), a moon for 'J' (blue); bright when [lit]. */
    private fun glyph(c: Canvas, x: Int, y: Int, g: Int, lit: Boolean) {
        val col = if (g == 0) (if (lit) Palette.GOLD else Palette.FLAG_DARK) else (if (lit) Palette.SEA[5] else Palette.SEA[1])
        if (g == 0) {
            rect(c, x + 1, y + 1, 2, 2, col); px(c, x + 2, y, col); px(c, x + 2, y + 3, col); px(c, x, y + 2, col); px(c, x + 3, y + 1, col)
        } else {
            rect(c, x + 1, y, 2, 1, col); px(c, x, y + 1, col); px(c, x, y + 2, col); rect(c, x + 1, y + 3, 2, 1, col)
        }
    }

    /** Windmills over everything: a white tower on the hub, and blades at the angle the physics uses. */
    fun windmills(c: Canvas, hole: Hole, ix: Int, iy: Int, shotTime: Float) {
        for (m in hole.windmills) {
            val hx = m.hubX.roundToInt() - ix
            val hy = m.hubY.roundToInt() - iy
            stamp(c, key(16, 0), hx, hy, 13, 13, 5, 7) { b ->
                Pix.oval(b, 7f, 10f, 5f, 2.5f, shadow)
                rect(b, 2, 3, 7, 7, Palette.PAPER[3])
                rect(b, 7, 3, 2, 7, Palette.PAPER[1])
                rect(b, 1, 1, 9, 2, Palette.FLAG_DARK)
            }
            // The blades, drawn at the nearest of 64 angles (under a pixel from the physics at the tip).
            val turn = 2f * Math.PI.toFloat()
            val a0 = ((m.angle(shotTime) % turn) + turn) % turn
            val step = ((a0 / turn * BLADE_STEPS).roundToInt()) % BLADE_STEPS
            val r = m.length.roundToInt() + 2
            val size = r * 2 + 1
            stamp(c, key(17, (m.length * 4).roundToInt() shl 16 or (m.blades shl 8) or step), hx, hy, size, size, r, r) { b ->
                blades(b, r, r, step * turn / BLADE_STEPS, m.blades, m.length)
            }
            rect(c, hx - 1, hy - 1, 3, 3, Palette.WOODS[0])
        }
    }

    private const val BLADE_STEPS = 64

    private fun blades(c: Canvas, hx: Int, hy: Int, a0: Float, n: Int, length: Float) {
        for (k in 0 until n) {
            val a = a0 + k * (2f * Math.PI.toFloat() / n)
            val ca = cos(a)
            val sa = sin(a)
            var s = 2f
            while (s <= length) {
                val x = (hx + ca * s).roundToInt()
                val y = (hy + sa * s).roundToInt()
                px(c, x, y, Palette.WOODS[1])
                if (s > 4f) px(c, (x - sa * 1.2f).roundToInt(), (y + ca * 1.2f).roundToInt(), Palette.PAPER[2]) // the sail
                s += 0.7f
            }
        }
    }

    /** A portal: a ring of its colour round a dark swirl. */
    private fun portal(c: Canvas, x: Int, y: Int, col: Int) {
        Pix.oval(c, x + 0.5f, y + 0.5f, 4f, 4f, Palette.alpha(col, 90))
        Pix.oval(c, x + 0.5f, y + 0.5f, 3f, 3f, 0xFF1A1030.toInt())
        Pix.ring(c, x + 0.5f, y + 0.5f, 4f, col)
        // A spiral arm in the middle.
        for (k in 0 until 7) {
            val a = k * 0.9f
            val r = 0.4f + k * 0.38f
            px(c, (x + cos(a) * r).roundToInt(), (y + sin(a) * r).roundToInt(), Palette.alpha(col, 150 + k * 15))
        }
    }

    /** A pulse wall that's up: a coloured block (a brass piston in the clockwork, a crystal in the caverns). */
    private fun pulseUp(c: Canvas, x: Int, y: Int, col: Int, style: String) {
        rect(c, x + 1, y + 6, TILE, 2, shadow)
        when (style) {
            "clock" -> {
                val brass = Palette.lerp(col, Palette.SANDS[3], 0.45f)
                rect(c, x, y, TILE, 5, brass)
                rect(c, x, y + 5, TILE, 3, Palette.darker(brass))
                rect(c, x, y, TILE, 1, Palette.PAPER[3])
                rect(c, x + 1, y + 2, TILE - 2, 1, Palette.darker(Palette.darker(brass))) // the piston's slot
                px(c, x + 1, y + 6, Palette.PAPER[2]); px(c, x + TILE - 2, y + 6, Palette.PAPER[2]) // rivets
            }
            "crystal" -> {
                rect(c, x, y + 1, TILE, 4, Palette.lerp(col, Palette.PAPER[3], 0.35f))
                rect(c, x, y + 5, TILE, 3, Palette.darker(col))
                for (k in 0 until 3) rect(c, x + 1 + k * 3, y + (k % 2), 2, 2, Palette.lerp(col, Palette.PAPER[3], 0.7f)) // facets
            }
            else -> {
                rect(c, x, y, TILE, 5, Palette.lerp(col, Palette.PAPER[3], 0.25f))
                rect(c, x, y + 5, TILE, 3, Palette.darker(col))
                rect(c, x, y, TILE, 1, Palette.PAPER[3])
            }
        }
    }

    /** A pulse wall that's down: its outline, pulsing (it rises after this shot). */
    private fun pulseDown(c: Canvas, x: Int, y: Int, col: Int, alpha: Float, style: String) {
        val oc = Palette.alpha(col, alpha.toInt().coerceIn(0, 255))
        rect(c, x, y, TILE, 1, oc); rect(c, x, y + TILE - 1, TILE, 1, oc)
        rect(c, x, y, 1, TILE, oc); rect(c, x + TILE - 1, y, 1, TILE, oc)
        if (style == "clock") rect(c, x + 2, y + 3, TILE - 4, 2, Palette.alpha(Palette.INK_SHADOW, 90)) // the piston's well
        if (style == "crystal") px(c, x + 3, y + 3, oc)
    }

    // ------------------------------------------------------------ night scenery

    private val beamPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val beam = Path()

    /** The lighthouse tower on its tile, and lanterns on posts with pools of warm light. */
    fun scenery(c: Canvas, hole: Hole, ix: Int, iy: Int, time: Float, still: Boolean) {
        for ((tx, ty) in hole.lanterns) {
            val x = ((tx + 0.5f) * TILE).roundToInt() - ix
            val y = ((ty + 0.5f) * TILE).roundToInt() - iy
            Pix.oval(c, x + 0.5f, y + 3f, 13f, 8f, Palette.alpha(LAMP, 26))
            Pix.oval(c, x + 0.5f, y + 2f, 8f, 5f, Palette.alpha(LAMP, 30))
            rect(c, x, y - 4, 1, 7, Palette.WOODS[0])
            val flick = if (still) 0 else ((time * 7f).toInt() % 3)
            rect(c, x - 1, y - 7, 3, 3, if (flick == 0) LAMP else Palette.GOLD)
            px(c, x, y - 8, Palette.WOODS[0])
        }
        val lh = hole.lighthouse ?: return
        val x = ((lh.first + 0.5f) * TILE).roundToInt() - ix
        val y = ((lh.second + 0.5f) * TILE).roundToInt() - iy
        // The tower: white with a red band, a lantern room on top.
        Pix.oval(c, x + 4f, y + 4f, 7f, 3f, shadow)
        rect(c, x - 3, y - 10, 7, 14, Palette.PAPER[3])
        rect(c, x - 3, y - 4, 7, 3, Palette.FLAG)
        rect(c, x + 2, y - 10, 2, 14, Palette.PAPER[1])
        rect(c, x - 4, y - 13, 9, 3, Palette.STONES[1])
        rect(c, x - 2, y - 15, 5, 2, LAMP)
        rect(c, x - 1, y - 17, 3, 2, Palette.STONES[1])
        // The beam sweeps round (scenery only; it never hides anything).
        if (!still) {
            val a = time * 0.7f
            val len = 260f
            beam.reset()
            beam.moveTo(x + 0.5f, y - 14f)
            beam.lineTo(x + cos(a - 0.12f) * len, y - 14f + sin(a - 0.12f) * len)
            beam.lineTo(x + cos(a + 0.12f) * len, y - 14f + sin(a + 0.12f) * len)
            beam.close()
            beamPaint.color = Palette.alpha(LAMP, 22)
            c.drawPath(beam, beamPaint)
        }
    }

    /** Whether a tile has something drawn on it here (for tests and spot pickers). */
    fun isBodyTile(t: Tile) = t == Tile.PLANK
}

package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A sprite written as a string grid: one character per pixel, mapped to palette colours. */
class Sprite(val name: String, rows: List<String>, colours: Map<Char, Int>) {
    val w = rows.maxOf { it.length }
    val h = rows.size
    val bitmap: Bitmap = Pix.bitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        for (y in rows.indices) for (x in rows[y].indices) {
            val col = colours[rows[y][x]] ?: continue
            b.setPixel(x, y, col)
        }
    }

    fun draw(c: Canvas, x: Int, y: Int, paint: Paint = Sprites.blit) = c.drawBitmap(bitmap, x.toFloat(), y.toFloat(), paint)
}

/**
 * The game's sprites, as string grids baked once into bitmaps (see `SpriteSheetTest` for
 * a contact sheet): the ball, flag, gulls and icons. Palms and clouds are generated, and
 * shadows are the sprite's own shape offset to the bottom right.
 */
object Sprites {
    val blit = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val tint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    private val ballColours = mapOf('o' to Palette.BALL_OUTLINE, 'w' to Palette.BALL, 's' to Palette.BALL_SHADE, 'x' to Palette.BALL_SHADE)
    private val ballRows = listOf(
        "..oo..",
        ".owwo.",
        "owwwso",
        "owwsso",
        ".osso.",
        "..oo..",
    )
    /** Four roll frames: a seam pixel walks round the ball. */
    private val ballFrames: List<Sprite> = listOf(2 to 1, 3 to 2, 2 to 3, 1 to 2).mapIndexed { i, (sx, sy) ->
        val rows = ballRows.mapIndexed { y, r -> if (y == sy) r.substring(0, sx) + "x" + r.substring(sx + 1) else r }
        Sprite("ball-$i", rows, ballColours)
    }
    private val ballShadow = Sprite("ball-shadow", ballRows.map { it.replace(Regex("[^.]"), "k") }, mapOf('k' to Palette.SHADOW))
    private val ballGhost = Sprite("ball-ghost", listOf("..oo..", ".o..o.", "o....o", "o....o", ".o..o.", "..oo.."),
        mapOf('o' to Palette.alpha(Palette.BALL, 170)))

    /** The ball centred on ([x], [y]); [spin] picks the roll frame, [ghost] draws a faint ring (under a palm). */
    fun ball(c: Canvas, x: Int, y: Int, spin: Int, ghost: Boolean = false) {
        if (ghost) { ballGhost.draw(c, x - 3, y - 3); return }
        ballShadow.draw(c, x - 2, y - 2)
        ballFrames[spin and 3].draw(c, x - 3, y - 3)
    }

    private val flagColours = mapOf('r' to Palette.FLAG, 'd' to Palette.FLAG_DARK, 'o' to Palette.alpha(Palette.CUP, 200))
    private val flagFrames = listOf(
        Sprite("flag-0", listOf("rrrrro.", "rrrrrro", "ddddo..", "ddo...."), flagColours),
        Sprite("flag-1", listOf("rrrrrro", "rrrrro.", "dddddo.", "ddo...."), flagColours),
    )

    /** Flag on its pole above the cup at ([cx], [cy]); [wave] 0/1, [lift] raises it when holed. */
    fun flag(c: Canvas, cx: Int, cy: Int, wave: Int, lift: Int) {
        val top = cy - 16 - lift
        for (i in 1..8) px(c, cx + i, cy - 1 + i / 3, Palette.alpha(Palette.CUP, 70)) // pole shadow
        rect(c, cx, top, 1, cy - top, Palette.POLE)
        px(c, cx, top - 1, Palette.GOLD)
        flagFrames[wave and 1].draw(c, cx + 1, top)
    }

    private val cupSprite = Sprite("cup", listOf(".ooooo.", "ooooooo", "ooooooo", ".lllll."),
        mapOf('o' to Palette.CUP, 'l' to Palette.CUP_LIP))

    fun cup(c: Canvas, cx: Int, cy: Int) = cupSprite.draw(c, cx - 3, cy - 2)

    // ------------------------------------------------------------ palms

    private val frondDx = intArrayOf(-7, 7, -6, 6, -3, 3, -1, 2)
    private val frondDy = intArrayOf(-1, -1, 3, 3, -4, -4, 4, 4)

    /** Where the canopy centre sits relative to the trunk base. */
    const val CANOPY_DX = -3
    const val CANOPY_DY = -11
    private const val CANOPY_OX = 9
    private const val CANOPY_OY = 7

    /** The canopy, generated once and outlined; [palmCanopy] only shifts it as it sways. */
    private val canopy: Bitmap by lazy {
        val w = 19
        val h = 17
        val pts = HashSet<Int>()
        fun key(x: Int, y: Int) = y * w + x
        for (f in frondDx.indices) {
            val dx = frondDx[f]
            val dy = frondDy[f]
            val len = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            for (s in 0..len) {
                val k = s / len.toFloat()
                pts += key(CANOPY_OX + (dx * k).roundToInt(), CANOPY_OY + (dy * k + k * k * 2.5f).roundToInt())
            }
        }
        for (dy in -1..1) for (dx in -1..1) pts += key(CANOPY_OX + dx, CANOPY_OY + dy)
        val b = Pix.bitmap(w, h, Bitmap.Config.ARGB_8888)
        for (p in pts) {
            val x = p % w
            val y = p / w
            for ((ox, oy) in arrayOf(-1 to 0, 1 to 0, 0 to 1, 0 to 2)) {
                val q = key(x + ox, y + oy)
                if (x + ox in 0 until w && y + oy in 0 until h && q !in pts) b.setPixel(x + ox, y + oy, Palette.PALM_DARK)
            }
        }
        for (p in pts) {
            val x = p % w
            val y = p / w
            b.setPixel(x, y, if (key(x, y - 1) !in pts) Palette.PALM_LIGHT else Palette.PALM)
        }
        b.setPixel(CANOPY_OX - 1, CANOPY_OY + 1, Palette.TRUNK_DARK)
        b.setPixel(CANOPY_OX + 1, CANOPY_OY + 1, Palette.TRUNK_DARK)
        b
    }

    fun palmShadow(c: Canvas, bx: Int, by: Int, sway: Int) {
        Pix.oval(c, bx + 4f + sway, by + 1.5f, 8f, 3.5f, Palette.alpha(Palette.OUTLINE_GREEN, 70))
    }

    fun palmTrunk(c: Canvas, bx: Int, by: Int) {
        rect(c, bx - 1, by, 3, 1, Palette.alpha(Palette.OUTLINE_GREEN, 90))
        for (i in 0..10) {
            val x = bx + (-i * 0.28f).roundToInt()
            val y = by - i
            px(c, x - 1, y, Palette.TRUNK_DARK)
            px(c, x, y, if (i % 3 == 2) Palette.WOOD else Palette.TRUNK)
            px(c, x + 1, y, Palette.TRUNK_DARK)
        }
    }

    /** The canopy, drawn over the ball. [sway] shifts it by a pixel in the breeze. */
    fun palmCanopy(c: Canvas, bx: Int, by: Int, sway: Int) {
        c.drawBitmap(canopy, (bx + CANOPY_DX + sway - CANOPY_OX).toFloat(), (by + CANOPY_DY - CANOPY_OY).toFloat(), blit)
    }

    /** True if ([x], [y]) is under the canopy of the palm based at ([bx], [by]). */
    fun underCanopy(x: Float, y: Float, bx: Float, by: Float): Boolean {
        val cx = bx + CANOPY_DX
        val cy = by + CANOPY_DY + 1
        return x > cx - 9 && x < cx + 9 && y > cy - 7 && y < cy + 7
    }

    fun palmSway(time: Float, seed: Int): Int = (sin(time * 1.1f + seed * 1.7f) * 0.9f).roundToInt()

    // ------------------------------------------------------------ gull

    private val gullUp = listOf("o...o", ".o.o.", "..o..")
    private val gullDown = listOf(".....", "oo.oo", "..o..")

    fun gull(c: Canvas, x: Int, y: Int, flap: Boolean, col: Int) {
        val rows = if (flap) gullUp else gullDown
        for (ry in rows.indices) for (rx in rows[ry].indices) if (rows[ry][rx] == 'o') px(c, x - 2 + rx, y - 1 + ry, col)
    }

    // ------------------------------------------------------------ clouds

    /** A soft cloud shadow: overlapping blobs, solid inside and dithered at the edges. */
    fun cloudShadow(seed: Int): Bitmap {
        val w = 84
        val h = 44
        val b = Pix.bitmap(w, h, Bitmap.Config.ARGB_8888)
        val blobs = 4
        val bx = FloatArray(blobs) { 18f + Terrain.hash(it, 1, seed) * 48f }
        val by = FloatArray(blobs) { 14f + Terrain.hash(it, 2, seed) * 16f }
        val br = FloatArray(blobs) { 9f + Terrain.hash(it, 3, seed) * 7f }
        val col = Palette.alpha(Palette.NIGHT[0], 40)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (i in 0 until blobs) {
                val dx = (x - bx[i]) / (br[i] * 1.4f)
                val dy = (y - by[i]) / br[i]
                m = maxOf(m, 1f - sqrt(dx * dx + dy * dy))
            }
            val on = m > 0.18f || (m > 0f && Pix.bayer(x, y) < (m / 0.18f * 16f).toInt())
            if (on) b.setPixel(x, y, col)
        }
        return b
    }

    // ------------------------------------------------------------ icons (UI), drawn in one colour

    val iconPause = Sprite("pause", listOf("##.##", "##.##", "##.##", "##.##", "##.##", "##.##"), mapOf('#' to Palette.INK))
    val iconLeft = Sprite("left", listOf("...#", "..##", ".###", "####", ".###", "..##", "...#"), mapOf('#' to Palette.INK))
    val iconRight = Sprite("right", listOf("#...", "##..", "###.", "####", "###.", "##..", "#..."), mapOf('#' to Palette.INK))
    val iconGear = Sprite("gear", listOf("..#..", ".###.", "##.##", ".###.", "..#.."), mapOf('#' to Palette.INK))
    val iconLock = Sprite("lock", listOf(".###.", "#...#", "#...#", "#####", "##.##", "##.##", "#####"), mapOf('#' to Palette.INK))
    val icons = listOf(iconPause, iconLeft, iconRight, iconGear, iconLock)

    /** Draws a one-colour icon tinted [col]. */
    fun icon(c: Canvas, s: Sprite, x: Int, y: Int, col: Int) {
        tint.colorFilter = android.graphics.PorterDuffColorFilter(col, android.graphics.PorterDuff.Mode.SRC_IN)
        s.draw(c, x, y, tint)
    }

    /** A small flag: [col] is the cloth colour. */
    fun flagIcon(c: Canvas, x: Int, y: Int, col: Int, h: Int = 8) {
        rect(c, x, y, 1, h, Palette.POLE)
        rect(c, x + 1, y, 4, 1, col)
        rect(c, x + 1, y + 1, 5, 1, col)
        rect(c, x + 1, y + 2, 3, 1, col)
    }

    /** Every baked sprite, for the contact sheet. */
    val all: List<Sprite> get() = ballFrames + ballShadow + ballGhost + flagFrames + cupSprite + icons
}

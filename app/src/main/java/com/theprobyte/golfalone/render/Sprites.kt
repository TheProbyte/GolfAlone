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

    /** Palm canopy, hand-drawn (docs/style/water-trees-board.png, candidate A), on the leaf ramp. */
    private val palmRows = listOf(
        "...........h...........",
        "...mml.....l.......m...",
        "..mlhlm....l....mlhlm..",
        ".ddmmlll...l...lllmmdd.",
        "...ddmmlll.l.lllmmdd...",
        "..mllllhlllmlllhllllm..",
        ".mlhhllllllmllllllhhlm.",
        "mllddmllllcCcllllmddllm",
        "ldo..ddllccCcclldd..odl",
        "do....mmllcccllmm....od",
        "o....mlhd.lcl.dhlm....o",
        "....mlho...l...ohlm....",
        "....lho....l....ohl....",
        "....do.....d.....od....",
        "....o......o......o....",
    )
    private val leafColours = mapOf(
        'o' to Palette.LEAF[0], 'd' to Palette.LEAF[1], 'm' to Palette.LEAF[2], 'l' to Palette.LEAF[3], 'h' to Palette.LEAF[4],
        'c' to Palette.WOODS[1], 'C' to Palette.WOODS[3],
    )

    /** Where the crown sits in the canopy sprite, and where that sits over the trunk base. */
    private const val CROWN_X = 11
    private const val CROWN_Y = 7
    const val CANOPY_DX = -3
    const val CANOPY_DY = -11

    /** Three poses: the frond tips (lower rows) sway a pixel either way while the crown stays put. */
    private val palmPoses: List<Sprite> = (-1..1).map { s ->
        val rows = palmRows.mapIndexed { y, r ->
            if (y < 9 || s == 0) r else if (s > 0) "." + r.dropLast(1) else r.drop(1) + "."
        }
        Sprite("palm${if (s < 0) "-left" else if (s > 0) "-right" else ""}", rows, leafColours)
    }

    /** The canopy's silhouette, flattened onto the ground and dithered: its shadow. */
    private val palmShadowSprite: Sprite = run {
        val w = palmRows[0].length
        val h = (palmRows.size + 1) / 2
        val rows = List(h) { y ->
            buildString {
                for (x in 0 until w) {
                    val a = palmRows.getOrNull(y * 2)?.getOrNull(x) ?: '.'
                    val b = palmRows.getOrNull(y * 2 + 1)?.getOrNull(x) ?: '.'
                    val solid = a != '.' || b != '.'
                    val edge = y == 0 || y == h - 1 || x == 0 || x == w - 1
                    append(if (solid && (!edge || (x + y) % 2 == 0)) 's' else '.')
                }
            }
        }
        Sprite("palm-shadow", rows, mapOf('s' to Palette.alpha(Palette.GRASS[0], 70)))
    }

    fun palmShadow(c: Canvas, bx: Int, by: Int, sway: Int) {
        palmShadowSprite.draw(c, bx + 4 + sway - palmShadowSprite.w / 2, by - 2)
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

    /** The canopy, drawn over the ball, in the pose for [sway] (-1, 0 or 1). */
    fun palmCanopy(c: Canvas, bx: Int, by: Int, sway: Int) {
        palmPoses[(sway + 1).coerceIn(0, 2)].draw(c, bx + CANOPY_DX - CROWN_X, by + CANOPY_DY - CROWN_Y)
    }

    /** True if ([x], [y]) is under the canopy of the palm based at ([bx], [by]). */
    fun underCanopy(x: Float, y: Float, bx: Float, by: Float): Boolean {
        val left = bx + CANOPY_DX - CROWN_X
        val top = by + CANOPY_DY - CROWN_Y
        return x > left + 2 && x < left + palmRows[0].length - 2 && y > top + 1 && y < top + palmRows.size - 1
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
    val all: List<Sprite> get() = ballFrames + ballShadow + ballGhost + flagFrames + cupSprite + icons + palmPoses + palmShadowSprite
}

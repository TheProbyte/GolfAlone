package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Two pixel fonts, each baked once into a glyph atlas (one alpha bitmap per character),
 * so text costs one bitmap draw per letter:
 * - [Face.BIG]: 5×7 and proportional (narrow I, 1, ! and .), for headings, buttons and the HUD.
 * - [Face.SMALL]: 3×5, for dense labels such as the scorecard grid.
 */
object PixelFont {
    enum class Face(val h: Int) { SMALL(5), BIG(7) }

    /** Height of the small face (kept for older callers). */
    const val H = 5
    const val W = 3

    private val small: Map<Char, String> = mapOf(
        'A' to ".#./#.#/###/#.#/#.#", 'B' to "##./#.#/##./#.#/##.", 'C' to ".##/#../#../#../.##",
        'D' to "##./#.#/#.#/#.#/##.", 'E' to "###/#../##./#../###", 'F' to "###/#../##./#../#..",
        'G' to ".##/#../#.#/#.#/.##", 'H' to "#.#/#.#/###/#.#/#.#", 'I' to "###/.#./.#./.#./###",
        'J' to "..#/..#/..#/#.#/.#.", 'K' to "#.#/#.#/##./#.#/#.#", 'L' to "#../#../#../#../###",
        'M' to "#.#/###/###/#.#/#.#", 'N' to "##./#.#/#.#/#.#/#.#", 'O' to ".#./#.#/#.#/#.#/.#.",
        'P' to "##./#.#/##./#../#..", 'Q' to ".#./#.#/#.#/##./.##", 'R' to "##./#.#/##./#.#/#.#",
        'S' to ".##/#../.#./..#/##.", 'T' to "###/.#./.#./.#./.#.", 'U' to "#.#/#.#/#.#/#.#/###",
        'V' to "#.#/#.#/#.#/#.#/.#.", 'W' to "#.#/#.#/#.#/###/#.#", 'X' to "#.#/#.#/.#./#.#/#.#",
        'Y' to "#.#/#.#/.#./.#./.#.", 'Z' to "###/..#/.#./#../###",
        '0' to "###/#.#/#.#/#.#/###", '1' to ".#./##./.#./.#./###", '2' to "##./..#/.#./#../###",
        '3' to "##./..#/.#./..#/##.", '4' to "#.#/#.#/###/..#/..#", '5' to "###/#../##./..#/##.",
        '6' to ".##/#../###/#.#/###", '7' to "###/..#/.#./.#./.#.", '8' to "###/#.#/###/#.#/###",
        '9' to "###/#.#/###/..#/##.",
        '!' to "#/#/#/./#", '.' to "././././#", ':' to "./#/./#/.",
        '-' to ".../.../###/.../...", '+' to ".../.#./###/.#./...", '/' to "..#/..#/.#./#../#..",
        '?' to "##./..#/.#./.../.#.", '\'' to "#/#/././.", '%' to "#.#/..#/.#./#../#.#",
        '<' to "..#/.#./#../.#./..#", '>' to "#../.#./..#/.#./#..", '*' to "././#/./.",
        ' ' to "../../../../..", '(' to ".#/#./#./#./.#", ')' to "#./.#/.#/.#/#.",
    )

    private val big: Map<Char, String> = mapOf(
        'A' to ".###.|#...#|#...#|#####|#...#|#...#|#...#", 'B' to "####.|#...#|#...#|####.|#...#|#...#|####.",
        'C' to ".###.|#...#|#....|#....|#....|#...#|.###.", 'D' to "####.|#...#|#...#|#...#|#...#|#...#|####.",
        'E' to "#####|#....|#....|####.|#....|#....|#####", 'F' to "#####|#....|#....|####.|#....|#....|#....",
        'G' to ".###.|#...#|#....|#.###|#...#|#...#|.####", 'H' to "#...#|#...#|#...#|#####|#...#|#...#|#...#",
        'I' to "###|.#.|.#.|.#.|.#.|.#.|###", 'J' to "..###|...#.|...#.|...#.|#..#.|#..#.|.##..",
        'K' to "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#", 'L' to "#....|#....|#....|#....|#....|#....|#####",
        'M' to "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#", 'N' to "#...#|##..#|#.#.#|#..##|#...#|#...#|#...#",
        'O' to ".###.|#...#|#...#|#...#|#...#|#...#|.###.", 'P' to "####.|#...#|#...#|####.|#....|#....|#....",
        'Q' to ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#", 'R' to "####.|#...#|#...#|####.|#.#..|#..#.|#...#",
        'S' to ".####|#....|#....|.###.|....#|....#|####.", 'T' to "#####|..#..|..#..|..#..|..#..|..#..|..#..",
        'U' to "#...#|#...#|#...#|#...#|#...#|#...#|.###.", 'V' to "#...#|#...#|#...#|#...#|#...#|.#.#.|..#..",
        'W' to "#...#|#...#|#...#|#.#.#|#.#.#|##.##|#...#", 'X' to "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#",
        'Y' to "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..", 'Z' to "#####|....#|...#.|..#..|.#...|#....|#####",
        '0' to ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.", '1' to ".#.|##.|.#.|.#.|.#.|.#.|###",
        '2' to ".###.|#...#|....#|...#.|..#..|.#...|#####", '3' to "####.|....#|....#|.###.|....#|....#|####.",
        '4' to "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.", '5' to "#####|#....|####.|....#|....#|#...#|.###.",
        '6' to ".###.|#....|#....|####.|#...#|#...#|.###.", '7' to "#####|....#|...#.|..#..|..#..|..#..|..#..",
        '8' to ".###.|#...#|#...#|.###.|#...#|#...#|.###.", '9' to ".###.|#...#|#...#|.####|....#|....#|.###.",
        '!' to "#|#|#|#|#|.|#", '.' to ".|.|.|.|.|.|#", ',' to "..|..|..|..|..|.#|#.", ':' to ".|.|#|.|.|#|.",
        '-' to "....|....|....|####|....|....|....", '+' to ".....|..#..|..#..|#####|..#..|..#..|.....",
        '/' to "....#|...#.|...#.|..#..|.#...|.#...|#....", '?' to ".###.|#...#|....#|...#.|..#..|.....|..#..",
        '\'' to "#|#|.|.|.|.|.", '%' to "##..#|##.#.|...#.|..#..|.#...|.#.##|#..##",
        '<' to "...#|..#.|.#..|#...|.#..|..#.|...#", '>' to "#...|.#..|..#.|...#|..#.|.#..|#...",
        '*' to ".|.|.|#|.|.|.", '(' to ".#|#.|#.|#.|#.|#.|.#", ')' to "#.|.#|.#|.#|.#|.#|#.",
        ' ' to "...|...|...|...|...|...|...",
    )

    private class Glyph(val w: Int, val h: Int, val bitmap: Bitmap)

    private fun bake(map: Map<Char, String>, sep: Char): Map<Char, Glyph> = map.mapValues { (_, s) ->
        val rows = s.split(sep)
        val w = rows.maxOf { it.length }
        val h = rows.size
        val b = Pix.bitmap(w, h, Bitmap.Config.ALPHA_8)
        for (y in rows.indices) for (x in rows[y].indices) if (rows[y][x] == '#') b.setPixel(x, y, 0xFF000000.toInt())
        Glyph(w, h, b)
    }

    private val smallGlyphs by lazy { bake(small, '/') }
    private val bigGlyphs by lazy { bake(big, '|') }
    private fun glyphs(face: Face) = if (face == Face.BIG) bigGlyphs else smallGlyphs

    private val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val dst = RectF()

    /** Width in pixels of [text] at [scale] (1 px gap between glyphs). */
    fun width(text: String, scale: Int = 1, face: Face = Face.SMALL): Int {
        if (text.isEmpty()) return 0
        val g = glyphs(face)
        var w = 0
        for (ch in text) w += ((g[ch.uppercaseChar()] ?: g['?']!!).w + 1)
        return (w - 1) * scale
    }

    fun draw(c: Canvas, text: String, x: Int, y: Int, color: Int, scale: Int = 1, shadow: Int = 0, face: Face = Face.SMALL) {
        if (shadow != 0) drawRaw(c, text, x, y + scale, shadow, scale, face)
        drawRaw(c, text, x, y, color, scale, face)
    }

    fun drawCentered(c: Canvas, text: String, cx: Int, y: Int, color: Int, scale: Int = 1, shadow: Int = 0, face: Face = Face.SMALL) =
        draw(c, text, cx - width(text, scale, face) / 2, y, color, scale, shadow, face)

    private fun drawRaw(c: Canvas, text: String, x: Int, y: Int, color: Int, scale: Int, face: Face) {
        paint.color = color
        val g = glyphs(face)
        var gx = x
        for (ch in text) {
            val gl = g[ch.uppercaseChar()] ?: g['?']!!
            dst.set(gx.toFloat(), y.toFloat(), (gx + gl.w * scale).toFloat(), (y + gl.h * scale).toFloat())
            c.drawBitmap(gl.bitmap, null, dst, paint)
            gx += (gl.w + 1) * scale
        }
    }
}

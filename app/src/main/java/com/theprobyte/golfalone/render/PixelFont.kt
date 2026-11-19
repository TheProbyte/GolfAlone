package com.theprobyte.golfalone.render

import android.graphics.Canvas
import android.graphics.Paint

/** A 3×5 pixel font, drawn as solid rects onto a low-resolution canvas. */
object PixelFont {
    const val W = 3
    const val H = 5

    private val glyphs: Map<Char, BooleanArray> = mapOf(
        'A' to ".#./#.#/###/#.#/#.#", 'B' to "##./#.#/##./#.#/##.", 'C' to ".##/#../#../#../.##",
        'D' to "##./#.#/#.#/#.#/##.", 'E' to "###/#../##./#../###", 'F' to "###/#../##./#../#..",
        'G' to ".##/#../#.#/#.#/.##", 'H' to "#.#/#.#/###/#.#/#.#", 'I' to "###/.#./.#./.#./###",
        'J' to "..#/..#/..#/#.#/.#.", 'K' to "#.#/#.#/##./#.#/#.#", 'L' to "#../#../#../#../###",
        'M' to "#.#/###/###/#.#/#.#", 'N' to "##./#.#/#.#/#.#/#.#", 'O' to ".#./#.#/#.#/#.#/.#.",
        'P' to "##./#.#/##./#../#..", 'Q' to ".#./#.#/#.#/##./.##", 'R' to "##./#.#/##./#.#/#.#",
        'S' to ".##/#../.#./..#/##.", 'T' to "###/.#./.#./.#./.#.", 'U' to "#.#/#.#/#.#/#.#/###",
        'V' to "#.#/#.#/#.#/#.#/.#.", 'W' to "#.#/#.#/###/###/#.#", 'X' to "#.#/#.#/.#./#.#/#.#",
        'Y' to "#.#/#.#/.#./.#./.#.", 'Z' to "###/..#/.#./#../###",
        '0' to "###/#.#/#.#/#.#/###", '1' to ".#./##./.#./.#./###", '2' to "##./..#/.#./#../###",
        '3' to "##./..#/.#./..#/##.", '4' to "#.#/#.#/###/..#/..#", '5' to "###/#../##./..#/##.",
        '6' to ".##/#../###/#.#/###", '7' to "###/..#/.#./.#./.#.", '8' to "###/#.#/###/#.#/###",
        '9' to "###/#.#/###/..#/##.",
        '!' to ".#./.#./.#./.../.#.", '.' to ".../.../.../.../.#.", ':' to ".../.#./.../.#./...",
        '-' to ".../.../###/.../...", '+' to ".../.#./###/.#./...", '/' to "..#/..#/.#./#../#..",
        '?' to "##./..#/.#./.../.#.", '\'' to ".#./.#./.../.../...", '%' to "#.#/..#/.#./#../#.#",
        '<' to "..#/.#./#../.#./..#", '>' to "#../.#./..#/.#./#..", '*' to ".../.../.#./.../...",
        ' ' to ".../.../.../.../...",
    ).mapValues { (_, s) -> s.replace("/", "").map { it == '#' }.toBooleanArray() }

    private val paint = Paint().apply { isAntiAlias = false }

    /** Width in pixels of [text] at [scale] (1 px gap between glyphs). */
    fun width(text: String, scale: Int = 1): Int =
        if (text.isEmpty()) 0 else (text.length * (W + 1) - 1) * scale

    fun draw(c: Canvas, text: String, x: Int, y: Int, color: Int, scale: Int = 1, shadow: Int = 0) {
        if (shadow != 0) drawRaw(c, text, x, y + scale, shadow, scale)
        drawRaw(c, text, x, y, color, scale)
    }

    fun drawCentered(c: Canvas, text: String, cx: Int, y: Int, color: Int, scale: Int = 1, shadow: Int = 0) =
        draw(c, text, cx - width(text, scale) / 2, y, color, scale, shadow)

    private fun drawRaw(c: Canvas, text: String, x: Int, y: Int, color: Int, scale: Int) {
        paint.color = color
        var gx = x
        for (ch in text) {
            val g = glyphs[ch.uppercaseChar()] ?: glyphs['?']!!
            for (i in 0 until W * H) if (g[i]) {
                val px = gx + (i % W) * scale
                val py = y + (i / W) * scale
                c.drawRect(px.toFloat(), py.toFloat(), (px + scale).toFloat(), (py + scale).toFloat(), paint)
            }
            gx += (W + 1) * scale
        }
    }
}

package com.theprobyte.golfalone.render

/**
 * Time of day for a course, applied as a swap of the world's colour ramps (never a
 * filter over the picture), so the pixel art stays crisp and every ramp step stays distinct.
 * [hi] tints the light end of a ramp and [lo] the shaded end, by [amtHi] and [amtLo].
 */
enum class Mood(val hi: Int, val lo: Int, val amtHi: Float, val amtLo: Float) {
    DAY(0, 0, 0f, 0f),
    /** First light: peach highlights, lilac shade. */
    DAWN(0xFFFFC8CE.toInt(), 0xFF46347A.toInt(), 0.16f, 0.24f),
    /** Golden hour: amber light, teal shade. */
    GOLDEN(0xFFFFCD78.toInt(), 0xFF1C3E60.toInt(), 0.18f, 0.22f);

    /** One colour through this mood: weighted by its brightness towards [hi], or towards [lo]. */
    fun grade(c: Int): Int {
        if (this == DAY) return c
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        val l = (0.3f * r + 0.59f * g + 0.11f * b) / 255f
        val th = amtHi * l
        val tl = amtLo * (1f - l)
        fun ch(v: Int, s: Int): Int {
            val hv = (hi shr s) and 0xff
            val lv = (lo shr s) and 0xff
            return (v + (hv - v) * th + (lv - v) * tl).toInt().coerceIn(0, 255)
        }
        return (c and 0xff000000.toInt()) or (ch(r, 16) shl 16) or (ch(g, 8) shl 8) or ch(b, 0)
    }
}

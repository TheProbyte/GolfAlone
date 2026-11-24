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
    GOLDEN(0xFFFFCD78.toInt(), 0xFF1C3E60.toInt(), 0.18f, 0.22f),
    /** Moonlight: cool, dimmer, deep blue shade (lanterns carry the warmth). */
    NIGHT(0xFF9FB4E6.toInt(), 0xFF0A1030.toInt(), 0.15f, 0.5f),
    /** Tropical noon: bright, clean light and teal shade. */
    NOON(0xFFFFFFE8.toInt(), 0xFF1E6A9A.toInt(), 0.12f, 0.15f),
    /** A storm: grey-green light and slate shade. */
    STORM(0xFFB8C4C0.toInt(), 0xFF1E2A30.toInt(), 0.22f, 0.32f),
    /** Volcanic dusk: ember-orange light, plum shade. */
    DUSK(0xFFFF9A60.toInt(), 0xFF301838.toInt(), 0.18f, 0.3f),
    /** A cold morning: icy white light, blue shade. */
    COLD(0xFFE8F4FF.toInt(), 0xFF2A3A60.toInt(), 0.15f, 0.25f),
    /** Jungle light: warm gold through the leaves, deep green shade. */
    JUNGLE(0xFFFFE9A0.toInt(), 0xFF0E3A2A.toInt(), 0.14f, 0.25f),
    DESERT(0xFFFFE0A0.toInt(), 0xFF6A3A20.toInt(), 0.18f, 0.2f),
    TWILIGHT(0xFFE0C8FF.toInt(), 0xFF241838.toInt(), 0.15f, 0.3f),
    EVENING(0xFFFFC890.toInt(), 0xFF2A2050.toInt(), 0.15f, 0.28f),
    SKY(0xFFF0F8FF.toInt(), 0xFF3A6AA0.toInt(), 0.12f, 0.18f),
    BRONZE(0xFFFFD8A0.toInt(), 0xFF3A2A30.toInt(), 0.16f, 0.28f),
    SWAMP(0xFFE8F0B0.toInt(), 0xFF1A3028.toInt(), 0.12f, 0.3f),
    CAVE(0xFFC0F0FF.toInt(), 0xFF140A28.toInt(), 0.15f, 0.35f),
    AUTUMN(0xFFFFC070.toInt(), 0xFF3A2030.toInt(), 0.2f, 0.25f),
    SAKURA(0xFFFFE0F0.toInt(), 0xFF304050.toInt(), 0.12f, 0.2f),
    STARRY(0xFFB0C8FF.toInt(), 0xFF080C28.toInt(), 0.15f, 0.4f);

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

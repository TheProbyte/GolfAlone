package com.theprobyte.golfalone.render

/** The whole game's colours: a small late-afternoon island palette. */
object Palette {
    const val DEEP = 0xFF1E4E79.toInt()
    const val MID = 0xFF2A6F97.toInt()
    const val SHALLOW = 0xFF3E95B0.toInt()
    const val FOAM = 0xFFD8F1F0.toInt()
    const val SPARKLE = 0xFF9ED8E0.toInt()

    const val SAND = 0xFFE8D29A.toInt()
    const val SAND_DARK = 0xFFC9AE72.toInt()
    const val SAND_LIGHT = 0xFFF4E4B8.toInt()

    const val FAIR_A = 0xFF5FB85C.toInt()
    const val FAIR_B = 0xFF57AE54.toInt()
    const val FAIR_DOT = 0xFF4C9F49.toInt()

    const val ROUGH = 0xFF3F8A45.toInt()
    const val ROUGH_DARK = 0xFF2F6F37.toInt()
    const val ROUGH_LIGHT = 0xFF58A459.toInt()

    const val GREEN_A = 0xFF7FD47B.toInt()
    const val GREEN_B = 0xFF78CC74.toInt()
    const val SLOPE_DARK = 0xFF66B963.toInt()
    const val SLOPE_GLINT = 0xFFB4EEAE.toInt()

    const val STONE = 0xFF8C8C96.toInt()
    const val STONE_LIGHT = 0xFFB9B9C2.toInt()
    const val STONE_DARK = 0xFF5E5E6A.toInt()
    const val STONE_DARKER = 0xFF45454F.toInt()

    const val CUP = 0xFF1D2B22.toInt()
    const val CUP_LIP = 0xFFA6E8A0.toInt()
    const val POLE = 0xFFF2EBD9.toInt()
    const val FLAG = 0xFFE8483E.toInt()
    const val FLAG_DARK = 0xFFB8322B.toInt()

    const val BALL = 0xFFFFFFFF.toInt()
    const val BALL_SHADE = 0xFFC9D3DB.toInt()
    const val SHADOW = 0x55102018

    const val INK = 0xFFF7F3E3.toInt()
    const val INK_DIM = 0xFFB9C4C9.toInt()
    const val INK_SHADOW = 0xFF13202C.toInt()
    const val PANEL = 0xEE1B2A3A.toInt()
    const val PANEL_EDGE = 0xFF3D5A70.toInt()
    const val GOLD = 0xFFFFD866.toInt()
    const val HOT = 0xFFFF8A3D.toInt()
    const val RED = 0xFFE8483E.toInt()

    /** Mixes two opaque colours, t in 0..1. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xff) + (((b shr s) and 0xff) - ((a shr s) and 0xff)) * u).toInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Darkens an opaque colour by [f] (0..1 kept). */
    fun shade(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xff) * f).toInt().coerceIn(0, 255)
        return (c and 0xff000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun alpha(c: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (c and 0xffffff)
}

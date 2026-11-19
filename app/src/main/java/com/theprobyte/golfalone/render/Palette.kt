package com.theprobyte.golfalone.render

/**
 * The whole game's colours, as hue-shifted ramps: each material steps cooler into
 * shadow and warmer into light. Shade by moving along a ramp ([darker], [lighter]),
 * never by multiplying.
 */
object Palette {
    private fun c(hex: Long) = hex.toInt()

    // ---------------------------------------------------------------- ramps (dark → light)
    val SEA = intArrayOf(c(0xFF16213E), c(0xFF1D3A6B), c(0xFF24598F), c(0xFF2F86AC), c(0xFF5FBCC4), c(0xFFC9EFE6))
    val GRASS = intArrayOf(c(0xFF173B34), c(0xFF23573F), c(0xFF2F7445), c(0xFF43924B), c(0xFF63AD50), c(0xFF8FC95A), c(0xFFC4E27A))
    val SANDS = intArrayOf(c(0xFF6E4B3E), c(0xFF9B6A4F), c(0xFFC69A6C), c(0xFFE3C48E), c(0xFFF5E6B8))
    val STONES = intArrayOf(c(0xFF2A2438), c(0xFF4A4461), c(0xFF6F6A85), c(0xFF9A97A8), c(0xFFCFCBC6))
    val WOODS = intArrayOf(c(0xFF3A2422), c(0xFF5F3A2C), c(0xFF8A5A3A), c(0xFFB88752), c(0xFFDDB57A))
    val PAPER = intArrayOf(c(0xFF9C8763), c(0xFFC9B48A), c(0xFFE6D6B0), c(0xFFF6EEDB))
    val NIGHT = intArrayOf(c(0xFF0E1522), c(0xFF121C2A), c(0xFF1B2A3A), c(0xFF24384D), c(0xFF4D6F8A))
    private val ramps = arrayOf(SEA, GRASS, SANDS, STONES, WOODS, PAPER, NIGHT)

    // ---------------------------------------------------------------- named colours
    // Open sea sits one step up the ramp, so the course reads as daytime.
    val DEEP = SEA[1]
    val MID = SEA[2]
    val SHALLOW = SEA[3]
    val SHOAL = SEA[4]
    val RIPPLE = SEA[4]
    val FOAM = SEA[5]
    val SPARKLE = SEA[4]

    val SAND_DARK = SANDS[2]
    val SAND = SANDS[3]
    val SAND_LIGHT = SANDS[4]
    val BANK = SANDS[2]
    val BANK_DARK = SANDS[1]
    val BANK_DARKER = SANDS[0]

    val FAIR_A = GRASS[5]
    val FAIR_B = GRASS[4]
    val FAIR_DOT = GRASS[3]
    val ROUGH = GRASS[3]
    val ROUGH_DARK = GRASS[2]
    val ROUGH_LIGHT = GRASS[5]
    val GREEN_A = GRASS[6]
    val GREEN_B = GRASS[5]
    val COLLAR = GRASS[4]
    val SLOPE_DARK = GRASS[4]
    val SLOPE_GLINT = c(0xFFE4F3A8)
    val OUTLINE_GREEN = GRASS[0]

    val STONE_DARKER = STONES[0]
    val STONE_DARK = STONES[1]
    val STONE = STONES[2]
    val STONE_TOP = STONES[3]
    val STONE_LIGHT = STONES[4]

    val WOOD_DARKER = WOODS[0]
    val WOOD_DARK = WOODS[1]
    val WOOD = WOODS[2]
    val WOOD_LIGHT = WOODS[3]
    val WOOD_PALE = WOODS[4]

    val PALM_DARK = GRASS[1]
    val PALM = GRASS[3]
    val PALM_LIGHT = GRASS[5]
    val TRUNK = WOODS[3]
    val TRUNK_DARK = WOODS[1]

    val FLOWER_Y = c(0xFFF5D76E)
    val FLOWER_P = c(0xFFF29AC0)
    val FLOWER_W = PAPER[3]
    val SHELL = PAPER[3]
    val SHELL_PINK = c(0xFFE3A99A)
    val PEBBLE = STONES[3]

    val CUP = GRASS[0]
    val CUP_LIP = GRASS[3]
    val POLE = PAPER[3]
    val FLAG = c(0xFFE0483E)
    val FLAG_DARK = c(0xFFA8303A)

    val BALL = c(0xFFFFFFFF)
    val BALL_SHADE = c(0xFFC8D6E5)
    val BALL_OUTLINE = c(0xFF1D2B3A)
    const val SHADOW = 0x55102018

    // UI: dark strip (HUD, banners) and scorecard paper.
    val INK = c(0xFFF7F3E3)
    val INK_DIM = c(0xFFB9C4C9)
    val INK_SHADOW = NIGHT[1]
    val PANEL = c(0xEE1B2A3A)
    val PANEL_EDGE = NIGHT[4]
    val GOLD = c(0xFFFFD866)
    val HOT = c(0xFFFF8A3D)
    val RED = FLAG

    val PAPER_INK = c(0xFF2A2333)
    val PAPER_INK_DIM = c(0xFF7A6B5C)
    val STAMP_RED = c(0xFFC8453A)
    val STAMP_GREEN = c(0xFF3F7A45)

    private val darkerMap = HashMap<Int, Int>().apply {
        for (r in ramps) for (i in r.indices) put(r[i], r[maxOf(0, i - 1)])
    }
    private val lighterMap = HashMap<Int, Int>().apply {
        for (r in ramps) for (i in r.indices) put(r[i], r[minOf(r.size - 1, i + 1)])
    }

    /** One step down this colour's ramp (or a plain darken if it's not on a ramp). */
    fun darker(col: Int): Int = darkerMap[col] ?: shade(col, 0.78f)

    fun lighter(col: Int): Int = lighterMap[col] ?: lerp(col, 0xFFFFFFFF.toInt(), 0.2f)

    /** Mixes two opaque colours, t in 0..1. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xff) + (((b shr s) and 0xff) - ((a shr s) and 0xff)) * u).toInt()
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Darkens an opaque colour by [f] (0..1 kept). Only for colours off the ramps. */
    fun shade(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xff) * f).toInt().coerceIn(0, 255)
        return (c and 0xff000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun alpha(c: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (c and 0xffffff)
}

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
    /** Leaves: cooler and deeper than the grass, so canopies stand out from the fairway. */
    val LEAF = intArrayOf(c(0xFF12352B), c(0xFF1F5540), c(0xFF2F7A4A), c(0xFF5AA64E), c(0xFF9FD364))
    /** Bleached driftwood: grey-tan, sun-dried. */
    val DRIFT = intArrayOf(c(0xFF3B322E), c(0xFF5E534C), c(0xFF8A7D70), c(0xFFB5A690), c(0xFFDDD0B8))
    /** Inland ponds: still and a little greener than the sea. */
    val POND = intArrayOf(c(0xFF163B44), c(0xFF1D5260), c(0xFF276B74), c(0xFF3F8F86), c(0xFF9FD1BF))
    val NIGHT = intArrayOf(c(0xFF0E1522), c(0xFF121C2A), c(0xFF1B2A3A), c(0xFF24384D), c(0xFF4D6F8A))
    private val ramps = arrayOf(SEA, GRASS, SANDS, STONES, WOODS, PAPER, LEAF, DRIFT, POND, NIGHT)
    /** The world's ramps (the UI's paper and night ramps are never graded). */
    private val worldRamps = arrayOf(SEA, GRASS, SANDS, STONES, WOODS, LEAF, DRIFT, POND)
    private val base = worldRamps.map { it.copyOf() }

    private fun r(vararg v: Long) = IntArray(v.size) { c(v[it]) }
    private const val R_SEA = 0
    private const val R_GRASS = 1
    private const val R_LEAF = 5
    private const val R_POND = 7

    /**
     * Courses 10-19 bring their own ground and water before the mood's light is graded over them:
     * a dry sage desert round a turquoise oasis, violet water under the mushrooms, olive mangroves
     * in murky water, a cave's black lake, gold autumn grass, jade moss in the zen garden.
     */
    private val own: Map<Mood, Map<Int, IntArray>> = mapOf(
        Mood.DESERT to mapOf(
            R_GRASS to r(0xFF2A3424, 0xFF3E4A2C, 0xFF56622F, 0xFF6E7E40, 0xFF889646, 0xFFA0A85A, 0xFFB8DC86),
            R_SEA to r(0xFF103A48, 0xFF145A68, 0xFF1C7E88, 0xFF2CA4A4, 0xFF6CCCC0, 0xFFD0F4E8),
            R_POND to r(0xFF0E4048, 0xFF136068, 0xFF1A8088, 0xFF34A8A0, 0xFFA8E8D8)),
        Mood.TWILIGHT to mapOf(
            R_GRASS to r(0xFF142C34, 0xFF1C443F, 0xFF27604A, 0xFF3A7C56, 0xFF549A62, 0xFF76B46E, 0xFFA8D48C),
            R_SEA to r(0xFF1A1636, 0xFF2A2258, 0xFF3A3478, 0xFF4E56A0, 0xFF7880C0, 0xFFD0D4F0)),
        Mood.EVENING to mapOf(
            R_SEA to r(0xFF1A1838, 0xFF28285E, 0xFF343E84, 0xFF3F62A6, 0xFF74A0C8, 0xFFD8E8F0)),
        Mood.BRONZE to mapOf(
            R_GRASS to r(0xFF26301E, 0xFF3A4826, 0xFF52622E, 0xFF6C7E3A, 0xFF8A9A48, 0xFFA8B45C, 0xFFB0D88A),
            R_SEA to r(0xFF10282E, 0xFF163E48, 0xFF1E5A64, 0xFF308A8C, 0xFF5CA8A0, 0xFFC8E8D8)),
        Mood.SWAMP to mapOf(
            R_GRASS to r(0xFF1C2A1C, 0xFF2A3E22, 0xFF3C5628, 0xFF547030, 0xFF6E8A3A, 0xFF8CA44A, 0xFFA8D880),
            R_SEA to r(0xFF12241E, 0xFF1A3830, 0xFF244E40, 0xFF3C6E6E, 0xFF5E9080, 0xFFC0D8B0),
            R_POND to r(0xFF12281E, 0xFF1C3C2C, 0xFF28543C, 0xFF3E7050, 0xFF98C098)),
        Mood.CAVE to mapOf(
            R_GRASS to r(0xFF102830, 0xFF163C44, 0xFF2A6650, 0xFF3C8460, 0xFF4E9C74, 0xFF5CA888, 0xFF98D0B0),
            R_SEA to r(0xFF0C0C20, 0xFF141838, 0xFF1E2858, 0xFF304C88, 0xFF4E70A8, 0xFFB0D0F0)),
        Mood.AUTUMN to mapOf(
            R_GRASS to r(0xFF2A2818, 0xFF443E20, 0xFF625A28, 0xFF8C7C34, 0xFFA6943E, 0xFFC0AC50, 0xFF8CD87C),
            R_SEA to r(0xFF16223A, 0xFF1E3656, 0xFF284E74, 0xFF367092, 0xFF6AA0B4, 0xFFD0E8E8),
            R_LEAF to r(0xFF4A1C18, 0xFF7A2C1C, 0xFFB04A22, 0xFFD8762E, 0xFFF0B048)),
        Mood.SAKURA to mapOf(
            R_GRASS to r(0xFF1A3634, 0xFF25504A, 0xFF32705E, 0xFF4A8E70, 0xFF6AAA80, 0xFF8CC490, 0xFFC0E2B0),
            R_SEA to r(0xFF1A2438, 0xFF24385A, 0xFF2E5478, 0xFF3C789A, 0xFF70A8BC, 0xFFD8ECEC)),
        Mood.STARRY to mapOf(
            R_SEA to r(0xFF080A1C, 0xFF0E1430, 0xFF182454, 0xFF2A4C96, 0xFF4A68A8, 0xFFB0C8F0)),
    )

    /** The current time of day; set before anything is baked (see [setMood]). */
    var mood = Mood.DAY
        private set

    /**
     * Swaps the world's ramps to [m]. Anything baked from the palette (terrain, sprites, the
     * sea tile) must be rebuilt afterwards; [Sprites] and [Terrain.sea] check [mood] themselves.
     */
    fun setMood(m: Mood) {
        if (m == mood) return
        for ((k, ramp) in worldRamps.withIndex()) {
            val from = own[m.ramps ?: m]?.get(k) ?: base[k]
            for (i in ramp.indices) ramp[i] = m.grade(from[i])
        }
        mood = m
        rebuildMaps()
    }

    // ---------------------------------------------------------------- named colours
    // Open sea sits one step up the ramp, so the course reads as daytime.
    val DEEP: Int get() = SEA[1]
    val MID: Int get() = SEA[2]
    val SHALLOW: Int get() = SEA[3]
    val SHOAL: Int get() = SEA[4]
    val RIPPLE: Int get() = SEA[4]
    val FOAM: Int get() = SEA[5]
    val SPARKLE: Int get() = SEA[4]

    val SAND_DARK: Int get() = SANDS[2]
    val SAND: Int get() = SANDS[3]
    val SAND_LIGHT: Int get() = SANDS[4]
    val BANK: Int get() = SANDS[2]
    val BANK_DARK: Int get() = SANDS[1]
    val BANK_DARKER: Int get() = SANDS[0]

    val FAIR_A: Int get() = GRASS[5]
    val FAIR_B: Int get() = GRASS[4]
    val FAIR_DOT: Int get() = GRASS[3]
    val ROUGH: Int get() = GRASS[3]
    val ROUGH_DARK: Int get() = GRASS[2]
    val ROUGH_LIGHT: Int get() = GRASS[5]
    val GREEN_A: Int get() = GRASS[6]
    val GREEN_B: Int get() = GRASS[5]
    val COLLAR: Int get() = GRASS[4]
    val SLOPE_DARK: Int get() = GRASS[4]
    val SLOPE_GLINT = c(0xFFE4F3A8)
    val OUTLINE_GREEN: Int get() = GRASS[0]

    val STONE_DARKER: Int get() = STONES[0]
    val STONE_DARK: Int get() = STONES[1]
    val STONE: Int get() = STONES[2]
    val STONE_TOP: Int get() = STONES[3]
    val STONE_LIGHT: Int get() = STONES[4]

    val WOOD_DARKER: Int get() = WOODS[0]
    val WOOD_DARK: Int get() = WOODS[1]
    val WOOD: Int get() = WOODS[2]
    val WOOD_LIGHT: Int get() = WOODS[3]
    val WOOD_PALE: Int get() = WOODS[4]

    val PALM_DARK: Int get() = GRASS[1]
    val PALM: Int get() = GRASS[3]
    val PALM_LIGHT: Int get() = GRASS[5]
    val TRUNK: Int get() = WOODS[3]
    val TRUNK_DARK: Int get() = WOODS[1]

    val FLOWER_Y = c(0xFFF5D76E)
    val FLOWER_P = c(0xFFF29AC0)
    val FLOWER_W: Int get() = PAPER[3]
    val SHELL: Int get() = PAPER[3]
    val SHELL_PINK = c(0xFFE3A99A)
    val STARFISH = c(0xFFE8823A)
    val STARFISH_DARK = c(0xFFB5552A)
    val PEBBLE: Int get() = STONES[3]

    val CUP: Int get() = GRASS[0]
    val CUP_LIP: Int get() = GRASS[3]
    val POLE: Int get() = PAPER[3]
    val FLAG = c(0xFFE0483E)
    val FLAG_DARK = c(0xFFA8303A)

    val BALL = c(0xFFFFFFFF)
    val BALL_SHADE = c(0xFFC8D6E5)
    val BALL_OUTLINE = c(0xFF1D2B3A)
    const val SHADOW = 0x55102018

    // UI: dark strip (HUD, banners) and scorecard paper.
    val INK = c(0xFFF7F3E3)
    val INK_DIM = c(0xFFB9C4C9)
    val INK_SHADOW: Int get() = NIGHT[1]
    val PANEL = c(0xEE1B2A3A)
    val PANEL_EDGE: Int get() = NIGHT[4]
    val GOLD = c(0xFFFFD866)
    val HOT = c(0xFFFF8A3D)
    val RED = FLAG

    val PAPER_INK = c(0xFF2A2333)
    val PAPER_INK_DIM = c(0xFF7A6B5C)
    val STAMP_RED = c(0xFFC8453A)
    val STAMP_GREEN = c(0xFF3F7A45)

    private val darkerMap = HashMap<Int, Int>()
    private val lighterMap = HashMap<Int, Int>()

    private fun rebuildMaps() {
        darkerMap.clear(); lighterMap.clear()
        for (r in ramps) for (i in r.indices) {
            darkerMap[r[i]] = r[maxOf(0, i - 1)]
            lighterMap[r[i]] = r[minOf(r.size - 1, i + 1)]
        }
    }

    init { rebuildMaps() }

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

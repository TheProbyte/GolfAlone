package com.theprobyte.golfalone

import android.content.Context

/**
 * Bests, the round in progress, stats and settings, saved on the device.
 *
 * Course records are keyed by course id ([course]), so a second course never collides
 * with the first. Saves from before course ids are migrated once.
 */
class Progress(context: Context, course: String = "cove") {
    private val prefs = context.getSharedPreferences("golf", Context.MODE_PRIVATE)

    /** The course whose bests and round are read and written (switch it with the course). */
    var course: String = course

    init { migrate() }

    /** Fewest strokes for a full round of course [id], or 0. */
    fun courseBestOf(id: String): Int = prefs.getInt("course_$id", 0)

    /** The course last chosen on the title screen. */
    var lastCourse: String
        get() = prefs.getString("last_course", "cove") ?: "cove"
        set(v) = prefs.edit().putString("last_course", v).apply()

    /** Version 1 keyed everything by hole number alone (all of it Driftwood Cove); copy it to the cove's keys once. */
    private fun migrate() {
        if (prefs.getBoolean("save_v2", false)) return
        val e = prefs.edit()
        for (i in 0 until 32) {
            val old = prefs.getInt("best_$i", 0)
            if (old > 0 && prefs.getInt("best_cove_$i", 0) == 0) e.putInt("best_cove_$i", old)
        }
        val oldCourse = prefs.getInt("course_DRIFTWOOD COVE", 0)
        if (oldCourse > 0 && prefs.getInt("course_cove", 0) == 0) e.putInt("course_cove", oldCourse)
        if (prefs.contains("run_hole")) {
            e.putInt("run_cove_hole", prefs.getInt("run_hole", -1))
            e.putString("run_cove_scores", prefs.getString("run_scores", ""))
            e.putInt("run_cove_strokes", prefs.getInt("run_strokes", 0))
            e.putFloat("run_cove_ball_x", prefs.getFloat("run_ball_x", 0f))
            e.putFloat("run_cove_ball_y", prefs.getFloat("run_ball_y", 0f))
        }
        e.putBoolean("save_v2", true).apply()
    }

    private fun bestKey(index: Int) = "best_${course}_$index"
    private fun runKey(k: String) = "run_${course}_$k"

    /** Fewest strokes on hole [index], or 0 if never finished. */
    fun best(index: Int): Int = prefs.getInt(bestKey(index), 0)

    /** Records a finished hole (for bests and stats); returns true if it's a new best. */
    fun record(index: Int, strokes: Int, par: Int = 0): Boolean {
        if (par > 0) recordStats(index, strokes, par)
        val b = best(index)
        if (b != 0 && strokes >= b) return false
        prefs.edit().putInt(bestKey(index), strokes).apply()
        return true
    }

    /** Fewest total strokes for a full round of this course, or 0. */
    fun courseBest(): Int = prefs.getInt("course_$course", 0)

    fun recordCourse(strokes: Int): Boolean {
        stat("rounds", 1)
        val b = courseBest()
        if (b != 0 && strokes >= b) return false
        prefs.edit().putInt("course_$course", strokes).apply()
        return true
    }

    /** Hole [index] can be practised once the hole before it has been finished. */
    fun unlocked(index: Int): Boolean = index == 0 || best(index - 1) > 0

    // ------------------------------------------------------------ the round in progress

    /** A course round in progress: the next hole to play, or -1. */
    val runHole: Int get() = prefs.getInt(runKey("hole"), -1)

    /** Strokes so far in the round in progress, one per hole (0 = not played). */
    fun runScores(n: Int): IntArray {
        val parts = (prefs.getString(runKey("scores"), "") ?: "").split(',')
        return IntArray(n) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
    }

    /** Strokes already taken on [runHole] (0 = not started), and where the ball lies. */
    val runStrokes: Int get() = prefs.getInt(runKey("strokes"), 0)
    val runBallX: Float get() = prefs.getFloat(runKey("ball_x"), 0f)
    val runBallY: Float get() = prefs.getFloat(runKey("ball_y"), 0f)

    /** The hole's own state when it was left (tide, crabs, planks, barrels; see HoleState.encode). */
    val runState: String get() = prefs.getString(runKey("state"), "") ?: ""

    fun saveRun(hole: Int, scores: IntArray, strokes: Int = 0, ballX: Float = 0f, ballY: Float = 0f, state: String = "") =
        prefs.edit().putInt(runKey("hole"), hole).putString(runKey("scores"), scores.joinToString(","))
            .putInt(runKey("strokes"), strokes).putFloat(runKey("ball_x"), ballX).putFloat(runKey("ball_y"), ballY)
            .putString(runKey("state"), state).apply()

    fun clearRun() = prefs.edit().remove(runKey("hole")).remove(runKey("scores")).remove(runKey("strokes"))
        .remove(runKey("ball_x")).remove(runKey("ball_y")).remove(runKey("state")).apply()

    // ------------------------------------------------------------ stats (kept from this version on)

    private fun stat(k: String, add: Int) = prefs.edit().putInt("stat_$k", prefs.getInt("stat_$k", 0) + add).apply()
    fun statCount(k: String): Int = prefs.getInt("stat_$k", 0)

    private fun recordStats(index: Int, strokes: Int, par: Int) {
        stat("holes", 1)
        when {
            strokes == 1 -> stat("aces", 1)
            strokes <= par - 2 -> stat("eagles", 1)
            strokes == par - 1 -> stat("birdies", 1)
            strokes == par -> stat("pars", 1)
        }
        stat("strokes_${course}_$index", strokes)
        stat("plays_${course}_$index", 1)
    }

    /** Average strokes on hole [index] (0 if never finished since stats began). */
    fun averageOn(index: Int): Float {
        val plays = statCount("plays_${course}_$index")
        return if (plays == 0) 0f else statCount("strokes_${course}_$index") / plays.toFloat()
    }

    fun recordJump() = stat("jumps", 1)

    /** Longest single shot, in pixels of the course (8 px to a tile). */
    var longestShot: Int
        get() = prefs.getInt("stat_longest", 0)
        set(v) { if (v > longestShot) prefs.edit().putInt("stat_longest", v).apply() }

    // ------------------------------------------------------------ settings

    var leftHanded: Boolean
        get() = prefs.getBoolean("left_handed", false)
        set(v) = prefs.edit().putBoolean("left_handed", v).apply()

    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(v) = prefs.edit().putBoolean("reduce_motion", v).apply()

    /** Full-screen play: the status bar hides while you play and comes back in menus. */
    var hideStatusBar: Boolean
        get() = prefs.getBoolean("hide_status_bar", true)
        set(v) = prefs.edit().putBoolean("hide_status_bar", v).apply()

    /** Pull length for full power: 0 short, 1 normal, 2 long. */
    var dragLength: Int
        get() = prefs.getInt("drag_length", 1)
        set(v) = prefs.edit().putInt("drag_length", v.coerceIn(0, 2)).apply()

    /** Effects and surf volume, 0..1. Players who had sound off keep it off. */
    var effectsVolume: Float
        get() = prefs.getFloat("effects_volume", if (prefs.getBoolean("sound", true)) 0.85f else 0f)
        set(v) = prefs.edit().putFloat("effects_volume", v.coerceIn(0f, 1f)).apply()

    var surfVolume: Float
        get() = prefs.getFloat("surf_volume", if (prefs.getBoolean("sound", true)) 0.7f else 0f)
        set(v) = prefs.edit().putFloat("surf_volume", v.coerceIn(0f, 1f)).apply()

    var debugOverlay: Boolean
        get() = prefs.getBoolean("debug_overlay", false)
        set(v) = prefs.edit().putBoolean("debug_overlay", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
}

package com.theprobyte.golfalone

import android.content.Context

/** Best scores and settings, saved on the device. */
class Progress(context: Context) {
    private val prefs = context.getSharedPreferences("golf", Context.MODE_PRIVATE)

    /** Fewest strokes on hole [index], or 0 if never finished. */
    fun best(index: Int): Int = prefs.getInt("best_$index", 0)

    /** Records a finished hole; returns true if it's a new best. */
    fun record(index: Int, strokes: Int): Boolean {
        val b = best(index)
        if (b != 0 && strokes >= b) return false
        prefs.edit().putInt("best_$index", strokes).apply()
        return true
    }

    /** Fewest total strokes for a full round of [course], or 0. */
    fun courseBest(course: String): Int = prefs.getInt("course_$course", 0)

    fun recordCourse(course: String, strokes: Int): Boolean {
        val b = courseBest(course)
        if (b != 0 && strokes >= b) return false
        prefs.edit().putInt("course_$course", strokes).apply()
        return true
    }

    /** Hole [index] can be practised once the hole before it has been finished. */
    fun unlocked(index: Int): Boolean = index == 0 || best(index - 1) > 0

    /** A course round in progress: the next hole to play, or -1. */
    val runHole: Int get() = prefs.getInt("run_hole", -1)

    /** Strokes so far in the round in progress, one per hole (0 = not played). */
    fun runScores(n: Int): IntArray {
        val parts = (prefs.getString("run_scores", "") ?: "").split(',')
        return IntArray(n) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
    }

    /** Strokes already taken on [runHole] (0 = not started), and where the ball lies. */
    val runStrokes: Int get() = prefs.getInt("run_strokes", 0)
    val runBallX: Float get() = prefs.getFloat("run_ball_x", 0f)
    val runBallY: Float get() = prefs.getFloat("run_ball_y", 0f)

    fun saveRun(hole: Int, scores: IntArray, strokes: Int = 0, ballX: Float = 0f, ballY: Float = 0f) =
        prefs.edit().putInt("run_hole", hole).putString("run_scores", scores.joinToString(","))
            .putInt("run_strokes", strokes).putFloat("run_ball_x", ballX).putFloat("run_ball_y", ballY).apply()

    fun clearRun() = prefs.edit().remove("run_hole").remove("run_scores").remove("run_strokes")
        .remove("run_ball_x").remove("run_ball_y").apply()

    var leftHanded: Boolean
        get() = prefs.getBoolean("left_handed", false)
        set(v) = prefs.edit().putBoolean("left_handed", v).apply()

    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(v) = prefs.edit().putBoolean("reduce_motion", v).apply()

    /** The one-time ramp tip has done its job once a ball has flown off a ramp. */
    var seenRampTip: Boolean
        get() = prefs.getBoolean("seen_ramp_tip", false)
        set(v) = prefs.edit().putBoolean("seen_ramp_tip", v).apply()

    var debugOverlay: Boolean
        get() = prefs.getBoolean("debug_overlay", false)
        set(v) = prefs.edit().putBoolean("debug_overlay", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
}

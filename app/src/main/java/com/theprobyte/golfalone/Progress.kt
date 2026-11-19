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

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
}

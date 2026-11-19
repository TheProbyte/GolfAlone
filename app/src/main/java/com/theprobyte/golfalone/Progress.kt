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

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
}

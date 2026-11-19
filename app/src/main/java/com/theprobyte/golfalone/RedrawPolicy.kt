package com.theprobyte.golfalone

/**
 * When to draw the next frame. Anything moving draws on every vsync (up to 120 Hz); a still
 * screen drops to [IDLE_MS] (30 fps), where every remaining animation is stepped anyway:
 * water at 6 fps, the flag at 4, pulses and rings at about 15. A touch wakes it at once.
 */
object RedrawPolicy {
    const val IDLE_MS = 33L

    data class State(
        val touching: Boolean,
        val ballMoving: Boolean,
        val transitioning: Boolean,
        val effects: Boolean,
        val cameraMoving: Boolean,
    )

    /** 0 means "on the next vsync"; otherwise the delay (ms) before the next frame. */
    fun intervalMs(s: State): Long =
        if (s.touching || s.ballMoving || s.transitioning || s.effects || s.cameraMoving) 0L else IDLE_MS
}

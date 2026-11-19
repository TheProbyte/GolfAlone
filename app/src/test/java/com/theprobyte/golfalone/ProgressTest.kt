package com.theprobyte.golfalone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val prefs get() = ctx.getSharedPreferences("golf", Context.MODE_PRIVATE)

    @Test fun oldSavesMoveToTheCourseOnce() {
        prefs.edit().clear().putInt("best_0", 3).putInt("best_2", 5).putInt("course_DRIFTWOOD COVE", 24)
            .putInt("run_hole", 2).putString("run_scores", "3,4,0").putInt("run_strokes", 1)
            .putFloat("run_ball_x", 10f).putFloat("run_ball_y", 20f).putBoolean("seen_ramp_tip", true).commit()
        val p = Progress(ctx)
        assertEquals(3, p.best(0)); assertEquals(0, p.best(1)); assertEquals(5, p.best(2))
        assertEquals(24, p.courseBest())
        assertEquals(2, p.runHole)
        assertEquals(listOf(3, 4, 0), p.runScores(3).toList())
        assertEquals(1, p.runStrokes); assertEquals(10f, p.runBallX); assertEquals(20f, p.runBallY)
        assertTrue(p.tipSeen("ramp"))
        // A second load doesn't copy old keys over newer records.
        p.record(0, 2)
        prefs.edit().putInt("best_0", 9).commit()
        assertEquals(2, Progress(ctx).best(0))
    }

    @Test fun anotherCourseHasItsOwnRecords() {
        prefs.edit().clear().commit()
        Progress(ctx).record(0, 3)
        assertEquals(0, Progress(ctx, "reef").best(0))
        assertTrue(Progress(ctx, "reef").unlocked(0))
        assertTrue(!Progress(ctx, "reef").unlocked(1))
    }

    @Test fun statsCountResults() {
        prefs.edit().clear().commit()
        val p = Progress(ctx)
        p.record(0, 1, par = 3) // ace
        p.record(1, 2, par = 4) // eagle
        p.record(2, 3, par = 4) // birdie
        p.record(2, 4, par = 4) // par
        p.record(3, 7, par = 4) // worse
        p.recordJump(); p.recordJump()
        p.longestShot = 120; p.longestShot = 80
        p.recordCourse(30)
        assertEquals(5, p.statCount("holes"))
        assertEquals(1, p.statCount("aces")); assertEquals(1, p.statCount("eagles"))
        assertEquals(1, p.statCount("birdies")); assertEquals(1, p.statCount("pars"))
        assertEquals(2, p.statCount("jumps")); assertEquals(1, p.statCount("rounds"))
        assertEquals(120, p.longestShot)
        assertEquals(3.5f, p.averageOn(2), 1e-4f)
        assertEquals(0f, p.averageOn(5))
    }

    @Test fun settingsKeepTheirRanges() {
        prefs.edit().clear().putBoolean("sound", false).commit()
        val p = Progress(ctx)
        assertEquals("sound off stays off", 0f, p.effectsVolume)
        p.effectsVolume = 3f; assertEquals(1f, p.effectsVolume)
        p.dragLength = 7; assertEquals(2, p.dragLength)
        assertTrue(p.hideStatusBar)
    }
}

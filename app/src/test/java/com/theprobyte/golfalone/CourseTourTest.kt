package com.theprobyte.golfalone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.theprobyte.golfalone.game.ShotPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import kotlin.math.cos
import kotlin.math.sin

/** Courses 1-9, unlocked: instant switching, a tidal save, and the hole that shows off each idea. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseTourTest : UiHarness() {

    @Test fun theFirstNineCourses() {
        openEveryCourse()
        // Switching courses is instant: ten quick taps along, then PLAY starts the course showing.
        val start = game.courseForTest
        repeat(10) {
            val (ax, ay) = game.buttonCenter(GameView.B_NEXT_COURSE)!!
            tap(ax, ay)
        }
        val shown = game.courseForTest
        assertFalse("ten taps moved along", shown === start)
        assertFalse("no dissolve when changing course", game.fading)
        frame("c-switch-quick")
        press(GameView.B_PLAY)
        runUntil(4000) { !game.busy }
        assertEquals(GameView.Mode.PLAY, game.mode)
        assertTrue("playing the course that was showing", shown.holes.contains(game.hole))
        run(200)
        frame("c-switch-played")
        press(GameView.B_PAUSE); press(GameView.B_QUIT)
        goTo("cove")

        // A round on a tidal hole: two shots, Save + Quit, Continue. The tide is where it was.
        goTo("marsh")
        press(GameView.B_PLAY)
        runUntil(4000) { !game.busy }
        playHole("c-marsh-round", maxShots = 1)
        assertFalse("one shot doesn't hole Low Water", game.ballInCup)
        if (!game.ballInCup) {
            assertEquals("the tide moved on a step", 1, game.holeStateForTest.shots)
            val strokes = game.strokesForTest
            val state = game.holeStateForTest.encode()
            press(GameView.B_PAUSE)
            press(GameView.B_QUIT)
            assertEquals(GameView.Mode.TITLE, game.mode)
            press(GameView.B_PLAY)
            assertEquals(strokes, game.strokesForTest)
            assertEquals("tide, crabs and planks as they were", state, game.holeStateForTest.encode())
            press(GameView.B_PAUSE)
            press(GameView.B_QUIT)
        } else {
            run(1500)
            press(GameView.B_NEXT); runUntil(4000) { !game.busy }
            press(GameView.B_PAUSE); press(GameView.B_QUIT)
        }
        assertEquals(GameView.Mode.TITLE, game.mode)

        tour(mapOf("marsh" to listOf(0, 3), "light" to listOf(1, 3), "lagoon" to listOf(1, 3), "wreck" to listOf(1, 2),
            "ember" to listOf(3), "frost" to listOf(2), "temple" to listOf(0, 1), "bluff" to listOf(1, 2, 5),
            "cove2" to listOf(0), "light2" to listOf(2), "temple2" to listOf(1), "frost2" to listOf(0)))
        assertTrue("labels that don't fit: ${game.textOverflows}", game.textOverflows.isEmpty())
    }
}

/** Courses 10-19, unlocked: the hole that shows off each idea, and a whole nine-hole round. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewCourseTourTest : UiHarness() {

    @Test fun theGeneratedCourses() {
        openEveryCourse()
        tour(mapOf("oasis" to listOf(0, 2), "mushroom" to listOf(0, 6), "carnival" to listOf(0, 8), "sky" to listOf(0, 6), "clock" to listOf(0, 2),
            "mangrove" to listOf(0, 1), "crystal" to listOf(0, 4), "autumn" to listOf(0, 2), "zen" to listOf(0), "starfall" to listOf(0, 3), "oasis2" to listOf(0, 8),
            "carnival2" to listOf(0), "clock2" to listOf(4), "crystal2" to listOf(0), "starfall2" to listOf(8)))
        // A whole nine-hole round, to the scorecard: the card strip and scorecard fit nine holes.
        assertEquals(GameView.Mode.TITLE, game.mode)
        goTo("zen")
        press(GameView.B_PLAY)
        runUntil(4000) { !game.busy }
        val course = game.course
        assertEquals("zen", course.id)
        assertEquals(9, course.holes.size)
        for (i in course.holes.indices) {
            assertEquals(course.holes[i], game.hole)
            playHole("c-zen-round-${i + 1}")
            assertTrue("${game.hole.name} holed", game.ballInCup)
            run(1500)
            if (i == 7 || i == 8) frame("c-zen-round-${i + 1}-card")
            press(GameView.B_NEXT); runUntil(4000) { !game.busy }
        }
        assertEquals(GameView.Mode.FINAL, game.mode)
        run(800)
        frame("c-zen-scorecard")
        val scores = game.scoresForTest
        assertEquals(9, scores.size)
        assertTrue(scores.all { it > 0 })
        println("zen round: ${scores.toList()} = ${scores.sum()} (par ${course.par})")
        assertTrue("labels that don't fit: ${game.textOverflows}", game.textOverflows.isEmpty())
    }
}

/** A second round: shown locked on its course's card until a round of that course is finished. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondRoundTest : UiHarness() {

    private fun launch(oasisFinished: Boolean) {
        val e = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("golf", android.content.Context.MODE_PRIVATE).edit().clear()
        // Every course up to Mirage Oasis is open.
        for (c in com.theprobyte.golfalone.game.Holes.COURSES.takeWhile { it.id != "oasis" }) e.putInt("course_${c.id}", 30)
        if (oasisFinished) e.putInt("course_oasis", 40)
        e.putString("last_course", "oasis").putBoolean("save_v2", true).commit()
        game = Robolectric.buildActivity(MainActivity::class.java).setup().get().game
        game.dispatchApplyWindowInsets(pixelInsets())
        run(600)
    }

    @Test fun lockedUntilTheFirstRoundIsFinished() {
        launch(oasisFinished = false)
        goTo("oasis2")
        assertEquals("oasis2", game.courseForTest.id)
        run(400)
        frame("ii-locked")
        press(GameView.B_PLAY)
        assertEquals("a locked second round doesn't start", GameView.Mode.TITLE, game.mode)
        press(GameView.B_VARIANT)
        assertEquals("oasis", game.courseForTest.id)
        assertTrue("labels that don't fit: ${game.textOverflows}", game.textOverflows.isEmpty())
    }

    @Test fun openOnceItIs() {
        launch(oasisFinished = true)
        goTo("oasis2")
        assertEquals("oasis2", game.courseForTest.id)
        run(400)
        frame("ii-open")
        press(GameView.B_PLAY)
        runUntil(4000) { !game.busy }
        assertEquals(GameView.Mode.PLAY, game.mode)
        assertTrue(game.course.holes.contains(game.hole))
        run(300)
        frame("ii-tee")
    }
}

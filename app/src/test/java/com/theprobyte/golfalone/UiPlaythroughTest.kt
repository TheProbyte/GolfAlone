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

/**
 * Plays a full round of Driftwood Cove through real touch events on a Pixel-sized screen
 * with Pixel-like system bars, then visits hole select, practice, pause and the edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPlaythroughTest {

    private val shotDir: File? = System.getProperty("golfalone.shots")?.let { File(it).apply { mkdirs() } }
    private lateinit var game: GameView
    private var shot: Bitmap? = null

    /** Advances time by [ms] in 16 ms frames, drawing each one (drawing drives the game). */
    private fun run(ms: Long) {
        var t = 0L
        while (t < ms) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            frame()
            t += 16
        }
    }

    private fun runUntil(maxMs: Long, cond: () -> Boolean) {
        var t = 0L
        while (!cond() && t < maxMs) { run(16); t += 16 }
        assertTrue("timed out", cond())
    }

    private fun frame(name: String? = null) {
        val b = shot ?: Bitmap.createBitmap(game.width, game.height, Bitmap.Config.ARGB_8888).also { shot = it }
        game.draw(Canvas(b))
        if (name != null && shotDir != null) FileOutputStream(File(shotDir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        game.dispatchTouchEvent(MotionEvent.obtain(now, now, action, x, y, 0))
    }

    private fun tap(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        run(48)
        touch(MotionEvent.ACTION_UP, x, y)
        run(32)
    }

    private fun press(id: Int) {
        run(320) // buttons register as they're drawn; let sheets finish sliding in
        val c = game.buttonCenter(id)
        assertNotNull("button $id is showing", c)
        tap(c!!.first, c.second)
        runUntil(2000) { !game.fading }
    }

    /** Pulls back from the middle of the screen, opposite to [angle], for [power]. */
    private fun drag(angle: Float, power: Float, shotName: String? = null) {
        val (cancel, maxDrag) = game.dragRangePx
        val len = cancel + power * (maxDrag - cancel)
        val sx = game.width / 2f
        val sy = game.height * 0.55f
        touch(MotionEvent.ACTION_DOWN, sx, sy)
        run(32)
        for (i in 1..8) {
            val f = i / 8f
            touch(MotionEvent.ACTION_MOVE, sx - cos(angle) * len * f, sy - sin(angle) * len * f)
            run(16)
        }
        if (shotName != null) frame(shotName)
        touch(MotionEvent.ACTION_UP, sx - cos(angle) * len, sy - sin(angle) * len)
    }

    private fun waitForRest() {
        run(48)
        runUntil(30_000) { !game.ballMoving }
    }

    /** Plays the current hole out, re-planning from wherever the ball actually rests. */
    private fun playHole(tag: String) {
        val planner = ShotPlanner(game.hole)
        var shots = 0
        while (!game.ballInCup) {
            val plan = (1..4).firstNotNullOfOrNull { planner.solveFrom(game.ballX, game.ballY, it) }
            assertNotNull("${game.hole.name}: a way to the cup from ${game.ballX},${game.ballY}", plan)
            val s = plan!!.shots.first()
            drag(s.angle, s.power, if (shots == 0) "$tag-aim" else null)
            if (shots == 0) { run(300); frame("$tag-rolling") }
            waitForRest()
            shots++
            assertTrue("${game.hole.name} took too many shots", shots <= 10)
        }
    }

    /** Thumb-first check: tall enough and in the lower part of the screen. */
    private fun assertReachable(id: Int, minDp: Float, minTop: Float) {
        run(32)
        val m = game.buttonMetrics(id)
        assertNotNull("button $id is showing", m)
        assertTrue("button $id is ${m!!.first} dp tall", m.first >= minDp)
        assertTrue("button $id starts ${(m.second * 100).toInt()}% down the screen", m.second >= minTop)
    }

    private fun swipe(dx: Float) {
        val x = game.width / 2f
        val y = game.height * 0.35f
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..6) { touch(MotionEvent.ACTION_MOVE, x + dx * i / 6f, y); run(16) }
        touch(MotionEvent.ACTION_UP, x + dx, y)
        run(300)
    }

    @Test fun playTheCourse() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        game = act.game
        val insets = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, 63, 0, 0))
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 42))
            .setInsets(android.view.WindowInsets.Type.systemGestures(), android.graphics.Insets.of(80, 0, 80, 84))
            .build()
        game.dispatchApplyWindowInsets(insets)
        run(600)
        val (lw, lh, scale) = game.logicalSize
        println("logical ${lw}x$lh at ${scale}x for ${game.width}x${game.height}")
        assertEquals(GameView.Mode.TITLE, game.mode)
        frame("00-title")
        assertReachable(GameView.B_PLAY, 56f, 0.6f)
        assertReachable(GameView.B_HOLES, 48f, 0.6f)

        // Settings sheet.
        press(GameView.B_SETTINGS)
        assertEquals(GameView.Sheet.SETTINGS, game.sheet)
        run(300)
        frame("01-settings")
        assertReachable(GameView.B_DONE, 56f, 0.6f)
        press(GameView.B_DONE)
        assertEquals(GameView.Sheet.NONE, game.sheet)

        // A new player's hole select: only hole 1 is open. Arrows and swipes move the carousel.
        press(GameView.B_HOLES)
        assertEquals(GameView.Mode.SELECT, game.mode)
        run(300)
        frame("02-select")
        press(GameView.B_NEXT_HOLE)
        press(GameView.B_NEXT_HOLE)
        assertEquals(2, game.selectedHole)
        run(300)
        frame("03-select-locked")
        press(GameView.B_PLAY_HOLE)
        assertEquals(GameView.Mode.SELECT, game.mode)
        assertEquals("FINISH HOLE 2 TO UNLOCK", game.messageForTest)
        swipe(-400f)
        assertEquals(3, game.selectedHole)
        swipe(400f)
        assertEquals(2, game.selectedHole)
        press(GameView.B_BACK)
        assertEquals(GameView.Mode.TITLE, game.mode)

        press(GameView.B_PLAY)
        assertEquals(GameView.Mode.PLAY, game.mode)
        assertTrue(game.courseRun)

        // A pull that starts in the left gesture zone is left to the system (Back): no shot.
        touch(MotionEvent.ACTION_DOWN, 20f, game.height * 0.5f)
        touch(MotionEvent.ACTION_MOVE, 400f, game.height * 0.5f)
        run(32)
        touch(MotionEvent.ACTION_UP, 400f, game.height * 0.5f)
        run(64)
        assertEquals(0, game.strokesForTest)

        val n = game.course.holes.size
        for (i in 0 until n) {
            assertEquals(i, game.holeIndex)
            run(200)
            frame("h${i + 1}-0-banner")
            runUntil(3000) { !game.busy }
            frame("h${i + 1}-1-tee")
            if (i == 0) {
                // Hold still to peek: no stroke. The pause chip opens a sheet without Restart in a round.
                touch(MotionEvent.ACTION_DOWN, game.width / 2f, game.height * 0.5f)
                run(600)
                assertTrue(game.isPeeking)
                frame("h1-1-peek")
                touch(MotionEvent.ACTION_UP, game.width / 2f, game.height * 0.5f)
                run(100)
                assertFalse(game.isPeeking)
                assertEquals(0, game.strokesForTest)
                assertReachable(GameView.B_PAUSE, 48f, 0.75f)
                press(GameView.B_PAUSE)
                assertTrue(game.isPaused)
                run(300)
                frame("h1-1-pause")
                assertEquals(null, game.buttonCenter(GameView.B_RESTART))
                assertReachable(GameView.B_RESUME, 56f, 0.6f)
                press(GameView.B_RESUME)
                assertFalse(game.isPaused)
            }
            if (i == 3) {
                // Save and quit mid-round, then continue from the title.
                press(GameView.B_PAUSE)
                press(GameView.B_QUIT)
                assertEquals(GameView.Mode.TITLE, game.mode)
                run(300)
                frame("h4-quit-title")
                val before = game.scoresForTest
                press(GameView.B_PLAY)
                assertEquals(GameView.Mode.PLAY, game.mode)
                assertEquals(3, game.holeIndex)
                assertEquals(before.toList(), game.scoresForTest.toList())
                runUntil(3000) { !game.busy }
            }
            playHole("h${i + 1}-2")
            run(1200)
            assertEquals(GameView.Mode.CARD, game.mode)
            run(500)
            frame("h${i + 1}-3-card")
            assertReachable(GameView.B_NEXT, 56f, 0.6f)
            println("${game.hole.name}: ${game.strokesForTest} (par ${game.hole.par})")
            if (i == 0) {
                assertTrue(game.onBack()) // Back on a card doesn't throw the round away
                assertEquals(GameView.Mode.CARD, game.mode)
                tap(game.width / 2f, game.height * 0.25f) // tap anywhere carries on
                runUntil(2000) { !game.fading }
            } else press(GameView.B_NEXT)
        }
        assertEquals(GameView.Mode.FINAL, game.mode)
        run(800)
        frame("90-scorecard")
        val scores = game.scoresForTest
        assertTrue(scores.all { it > 0 })
        println("round: ${scores.toList()} = ${scores.sum()} (par ${game.course.par})")
        assertReachable(GameView.B_AGAIN, 56f, 0.6f)

        press(GameView.B_MENU)
        assertEquals(GameView.Mode.TITLE, game.mode)
        run(300)
        frame("91-title-best")

        // Practise hole 1: everything is unlocked now.
        press(GameView.B_HOLES)
        while (game.selectedHole > 0) press(GameView.B_PREV_HOLE)
        run(300)
        frame("92-select")
        press(GameView.B_PLAY_HOLE)
        assertEquals(GameView.Mode.PLAY, game.mode)
        assertFalse(game.courseRun)
        runUntil(3000) { !game.busy }

        // A pull-back that returns to the start is cancelled: no stroke.
        val sx = game.width / 2f
        val sy = game.height * 0.5f
        touch(MotionEvent.ACTION_DOWN, sx, sy)
        touch(MotionEvent.ACTION_MOVE, sx, sy + 200f)
        run(32)
        touch(MotionEvent.ACTION_MOVE, sx, sy + 2f)
        touch(MotionEvent.ACTION_UP, sx, sy + 2f)
        run(100)
        assertEquals(0, game.strokesForTest)

        // Into the sea off the tee: penalty stroke and back to the tee.
        drag(Math.PI.toFloat(), 1f)
        run(100)
        waitForRest()
        frame("93-after-splash")
        assertEquals(2, game.strokesForTest)
        assertEquals(game.hole.teeX, game.ballX, 0.01f)

        // A drag that starts on the pause chip is a shot, not a pause.
        run(32)
        val (cx, cy) = game.buttonCenter(GameView.B_PAUSE)!!
        touch(MotionEvent.ACTION_DOWN, cx, cy)
        for (k in 1..6) { touch(MotionEvent.ACTION_MOVE, cx, cy + 40f * k); run(16) }
        touch(MotionEvent.ACTION_UP, cx, cy + 240f)
        run(32)
        assertFalse(game.isPaused)
        assertEquals(3, game.strokesForTest)
        waitForRest()

        // Practice pause offers Restart; Back closes the sheet.
        press(GameView.B_PAUSE)
        assertNotNull(game.buttonCenter(GameView.B_RESTART))
        assertTrue(game.onBack())
        assertFalse(game.isPaused)
        press(GameView.B_PAUSE)
        press(GameView.B_QUIT)
        assertEquals(GameView.Mode.TITLE, game.mode)

        assertTrue("labels that don't fit: ${game.textOverflows}", game.textOverflows.isEmpty())
    }
}

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

/** Drives the real game through touch events on a Pixel-sized screen; shared by the UI tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class UiHarness {

    protected val shotDir: File? = System.getProperty("golfalone.shots")?.let { File(it).apply { mkdirs() } }
    protected lateinit var game: GameView
    protected var shot: Bitmap? = null

    /** Advances time by [ms] in 16 ms frames, drawing each one (drawing drives the game). */
    protected fun run(ms: Long) {
        var t = 0L
        while (t < ms) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            frame()
            t += 16
        }
    }

    protected fun runUntil(maxMs: Long, cond: () -> Boolean) {
        var t = 0L
        while (!cond() && t < maxMs) { run(16); t += 16 }
        assertTrue("timed out", cond())
    }

    protected fun frame(name: String? = null) {
        val b = shot ?: Bitmap.createBitmap(game.width, game.height, Bitmap.Config.ARGB_8888).also { shot = it }
        game.draw(Canvas(b))
        if (name != null && shotDir != null) FileOutputStream(File(shotDir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        game.dispatchTouchEvent(MotionEvent.obtain(now, now, action, x, y, 0))
    }

    /** A real finger wiggles a pixel or two while it taps. */
    protected fun tap(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        run(24)
        touch(MotionEvent.ACTION_MOVE, x + 2f, y + 1f)
        run(24)
        touch(MotionEvent.ACTION_UP, x + 2f, y + 1f)
        run(32)
    }

    protected fun longPress(x: Float, y: Float, ms: Long) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        var t = 0L
        while (t < ms) { touch(MotionEvent.ACTION_MOVE, x + (t / 100 % 2), y); run(50); t += 50 }
        touch(MotionEvent.ACTION_UP, x, y)
        run(32)
    }

    protected fun press(id: Int) {
        run(320) // buttons register as they're drawn; let sheets finish sliding in
        val c = game.buttonCenter(id)
        assertNotNull("button $id is showing", c)
        tap(c!!.first, c.second)
        runUntil(2000) { !game.fading && !game.gliding }
    }

    /**
     * Pulls back from the middle of the screen, opposite to [angle], for [power]. With
     * [holdMs] the finger rests first, as a hesitant player's does; [expectPeek] asserts
     * that the rest armed a peek (long holes only).
     */
    protected fun drag(angle: Float, power: Float, shotName: String? = null, holdMs: Long = 0, expectPeek: Boolean? = null) {
        val (cancel, maxDrag) = game.dragRangePx
        val len = cancel + power * (maxDrag - cancel)
        val sx = game.width / 2f
        val sy = game.height * 0.55f
        touch(MotionEvent.ACTION_DOWN, sx, sy)
        run(32 + holdMs)
        if (expectPeek != null) assertEquals("peeking after holding still", expectPeek, game.isPeeking)
        for (i in 1..8) {
            val f = i / 8f
            touch(MotionEvent.ACTION_MOVE, sx - cos(angle) * len * f, sy - sin(angle) * len * f)
            run(16)
        }
        if (shotName != null) frame(shotName)
        touch(MotionEvent.ACTION_UP, sx - cos(angle) * len, sy - sin(angle) * len)
    }

    protected fun waitForRest() {
        run(48)
        runUntil(30_000) { !game.ballMoving }
    }

    /** Plays the current hole out, re-planning from wherever the ball actually rests. */
    protected fun playHole(tag: String, firstHoldMs: Long = 0, expectPeek: Boolean? = null, maxShots: Int = 99) {
        val planner = ShotPlanner(game.hole)
        var shots = 0
        while (!game.ballInCup && shots < maxShots) {
            val plan = (1..4).firstNotNullOfOrNull { planner.solveFrom(game.ballX, game.ballY, it, game.holeStateForTest) }
            assertNotNull("${game.hole.name}: a way to the cup from ${game.ballX},${game.ballY}", plan)
            val s = plan!!.shots.first()
            val before = game.strokesForTest
            if (shots == 0 && firstHoldMs > 0) drag(s.angle, s.power, "$tag-aim", firstHoldMs, expectPeek)
            else drag(s.angle, s.power, if (shots == 0) "$tag-aim" else null)
            run(32)
            assertEquals("${game.hole.name}: the pull-back shot", before + 1, game.strokesForTest)
            if (shots == 0) { run(300); frame("$tag-rolling") }
            waitForRest()
            shots++
            assertTrue("${game.hole.name} took too many shots", shots <= 10)
        }
    }

    /** Thumb-first check: tall enough and in the lower part of the screen. */
    protected fun assertReachable(id: Int, minDp: Float, minTop: Float) {
        run(32)
        val m = game.buttonMetrics(id)
        assertNotNull("button $id is showing", m)
        assertTrue("button $id is ${m!!.first} dp tall", m.first >= minDp)
        assertTrue("button $id starts ${(m.second * 100).toInt()}% down the screen", m.second >= minTop)
    }

    protected fun swipe(dx: Float) {
        val x = game.width / 2f
        val y = game.height * 0.35f
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..6) { touch(MotionEvent.ACTION_MOVE, x + dx * i / 6f, y); run(16) }
        touch(MotionEvent.ACTION_UP, x + dx, y)
        run(300)
    }

    /** A Pixel's system bars and gesture zones, in px. */
    protected fun pixelInsets(statusBar: Int = 63) = android.view.WindowInsets.Builder()
        .setInsets(android.view.WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, statusBar, 0, 0))
        .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 42))
        .setInsets(android.view.WindowInsets.Type.systemGestures(), android.graphics.Insets.of(80, 0, 80, 84))
        .build()

    /** Every course open, with a best on each, then the title. */
    protected fun openEveryCourse() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("golf", android.content.Context.MODE_PRIVATE)
        val e = prefs.edit()
        for (c in com.theprobyte.golfalone.game.Holes.COURSES) {
            e.putInt("course_${c.id}", 30)
            for (i in c.holes.indices) e.putInt("best_${c.id}_$i", 9)
        }
        e.putBoolean("save_v2", true).commit()
        game = Robolectric.buildActivity(MainActivity::class.java).setup().get().game
        game.dispatchApplyWindowInsets(pixelInsets())
        game.onImmersive = { hide -> game.dispatchApplyWindowInsets(pixelInsets(if (hide) 0 else 63)) }
        run(600)
    }

    /** Visits each course in [plays] on the title and plays the listed holes from hole select. */
    protected fun tour(plays: Map<String, List<Int>>) {
        for ((id, holes) in plays) {
            while (game.courseForTest.id != id) { press(GameView.B_NEXT_COURSE); runUntil(3000) { !game.fading } }
            run(400)
            frame("c-$id-0-title")
            for (h in holes) {
                press(GameView.B_HOLES)
                while (game.selectedHole < h) press(GameView.B_NEXT_HOLE)
                press(GameView.B_PLAY_HOLE)
                runUntil(4000) { !game.busy }
                // A practice jump never builds water on the main thread.
                runUntil(4000) { game.terrainForTest.waterReady }
                assertEquals("${game.hole.name}: water built off the main thread", "water", game.terrainForTest.waterThread)
                run(300)
                frame("c-$id-${h + 1}-tee")
                playHole("c-$id-${h + 1}")
                assertTrue("${game.hole.name} holed", game.ballInCup)
                println("${game.hole.name}: ${game.strokesForTest} (par ${game.hole.par})")
                run(1500)
                frame("c-$id-${h + 1}-card")
                press(GameView.B_SELECT)
                press(GameView.B_BACK)
            }
        }
    }
}

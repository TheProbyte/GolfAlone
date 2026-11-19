package com.theprobyte.golfalone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.theprobyte.golfalone.game.ShotPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Plays hole 1 end to end through real touch events on a Pixel-sized screen. */
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
        var guard = 0
        run(48)
        while (game.ballMoving && guard++ < 1500) run(16)
        assertFalse("ball came to rest", game.ballMoving)
    }

    @Test fun playHoleOneToTheScorecard() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        game = act.game
        // Pixel-like system bars and gesture-navigation zones.
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
        frame("01-title")

        tap(game.width / 2f, game.height * 0.45f)
        assertEquals(GameView.Mode.PLAY, game.mode)
        run(700)
        frame("02-tee")

        // A pull that starts in the left gesture zone is left to the system (Back): no shot.
        touch(MotionEvent.ACTION_DOWN, 20f, game.height * 0.5f)
        touch(MotionEvent.ACTION_MOVE, 400f, game.height * 0.5f)
        run(32)
        touch(MotionEvent.ACTION_UP, 400f, game.height * 0.5f)
        run(64)
        assertEquals(0, game.strokesForTest)
        assertFalse(game.ballMoving)

        val plan = ShotPlanner(game.hole).solve(game.hole.par)!!
        plan.shots.forEachIndexed { i, s ->
            drag(s.angle, s.power, if (i == 0) "03-aim" else null)
            run(250)
            if (i == 0) frame("04-rolling")
            waitForRest()
            frame("05-shot-${i + 1}")
        }
        assertTrue("holed out", game.ballInCup)
        assertEquals(plan.shots.size, game.strokesForTest)
        run(1600)
        assertEquals(GameView.Mode.CARD, game.mode)
        frame("06-card")

        // Again: back on the tee with no strokes.
        val (ax, ay) = game.buttonCenter(primary = true)
        tap(ax, ay)
        assertEquals(GameView.Mode.PLAY, game.mode)
        assertEquals(0, game.strokesForTest)

        // A short pull-back that returns to the start is cancelled: no stroke.
        val sx = game.width / 2f
        val sy = game.height * 0.5f
        touch(MotionEvent.ACTION_DOWN, sx, sy)
        touch(MotionEvent.ACTION_MOVE, sx, sy + 200f)
        run(32)
        touch(MotionEvent.ACTION_MOVE, sx, sy + 2f)
        touch(MotionEvent.ACTION_UP, sx, sy + 2f)
        run(100)
        assertEquals(0, game.strokesForTest)
        assertFalse(game.ballMoving)

        // Into the sea off the tee: penalty stroke and back to the tee.
        drag(Math.PI.toFloat(), 1f)
        run(100)
        waitForRest()
        frame("07-after-splash")
        assertEquals(2, game.strokesForTest)
        assertEquals(game.hole.teeX, game.ballX, 0.01f)

        // Back goes to the title.
        assertTrue(game.onBack())
        assertEquals(GameView.Mode.TITLE, game.mode)
    }
}

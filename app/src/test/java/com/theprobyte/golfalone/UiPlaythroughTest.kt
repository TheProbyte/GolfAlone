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
class UiPlaythroughTest : UiHarness() {

    @Test fun aRampJumpOnFirstLight() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        game = ctl.get().game
        run(600)
        press(GameView.B_PLAY)
        runUntil(3000) { !game.busy }
        // The rocks hide the ramp from the tee, so it's a second-shot shortcut: first lay up
        // below it, then jump the pool. Both shots are found with the real physics, then played
        // with real drags.
        val hole = game.hole
        fun sim(fx: Float, fy: Float, a: Float, pw: Float): Triple<Boolean, Float, Float> {
            val p = com.theprobyte.golfalone.game.Physics(hole)
            p.placeAt(fx, fy)
            var launched = false
            p.shootAngle(a, pw)
            p.settle(object : com.theprobyte.golfalone.game.PhysicsEvents {
                override fun onLaunch(x: Float, y: Float, speed: Float) { launched = true }
            })
            return Triple(launched && !p.inCup && hole.coast.isLand(p.x, p.y) && p.y < 16 * 8f, p.x, p.y)
        }
        var layUp: Pair<Float, Float>? = null
        var jump: Pair<Float, Float>? = null
        search@ for (a in 0 until 360 step 2) for (pw in 6..14) {
            val ang = (a * Math.PI / 180).toFloat()
            val (_, ex, ey) = sim(hole.teeX, hole.teeY, ang, pw / 20f)
            if (kotlin.math.abs(ex - 60f) > 6f || ey !in 206f..236f) continue
            for (b in -25..25) for (qw in 8..16) {
                val ang2 = ((-90 + b) * Math.PI / 180).toFloat()
                if (sim(ex, ey, ang2, qw / 20f).first) { layUp = ang to pw / 20f; jump = ang2 to qw / 20f; break@search }
            }
        }
        assertNotNull("a lay-up below the ramp, then a jump over the pool", jump)
        drag(layUp!!.first, layUp.second)
        waitForRest()
        frame("ramp-0-below")
        val best = jump
        drag(best!!.first, best.second)
        var flew = false
        var t = 0
        while (game.ballMoving && t++ < 400) {
            run(16)
            if (game.ballAirborne && !flew) { flew = true; run(80); frame("ramp-1-flight"); run(80); frame("ramp-2-flight") }
        }
        assertTrue("it flew", flew)
        run(200)
        frame("ramp-3-landed")
    }

    @Test fun backgroundingWhileAHolingShotRollsStillShowsTheCard() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        game = ctl.get().game
        run(600)
        press(GameView.B_PLAY)
        runUntil(3000) { !game.busy }
        val planner = ShotPlanner(game.hole)
        var guard = 0
        while (!game.ballInCup && guard++ < 10) {
            val plan = (1..4).firstNotNullOfOrNull { planner.solveFrom(game.ballX, game.ballY, it, game.holeStateForTest) }!!
            val s = plan.shots.first()
            drag(s.angle, s.power)
            if (plan.shots.size == 1) {
                run(64)
                assertTrue("still rolling", game.ballMoving)
                ctl.pause() // the app goes to the background mid-roll
                ctl.resume()
                break
            }
            waitForRest()
        }
        assertTrue("holed", game.ballInCup)
        run(1500)
        assertEquals("the card appears", GameView.Mode.CARD, game.mode)
    }

    @Test fun playTheCourse() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        game = act.game
        game.dispatchApplyWindowInsets(pixelInsets())
        // Play hides the status bar; the phone then reports insets without it (gesture zones stay).
        game.onImmersive = { hide -> game.dispatchApplyWindowInsets(pixelInsets(if (hide) 0 else 63)) }
        run(600)
        val (lw, lh, scale) = game.logicalSize
        println("logical ${lw}x$lh at ${scale}x for ${game.width}x${game.height}")
        assertEquals(GameView.Mode.TITLE, game.mode)
        frame("00-title")
        // Long-press the logo: the frame-time readout comes on, and off again.
        longPress(game.width / 2f, game.height * 0.15f, 1200)
        assertTrue(game.debugOverlayOn)
        frame("00-title-frametimes")
        longPress(game.width / 2f, game.height * 0.15f, 1200)
        assertFalse(game.debugOverlayOn)
        assertReachable(GameView.B_PLAY, 56f, 0.6f)
        assertReachable(GameView.B_HOLES, 48f, 0.6f)
        assertReachable(GameView.B_BOOK, 48f, 0.6f)

        // A new player can look at the next course, but it's locked.
        press(GameView.B_NEXT_COURSE)
        runUntil(3000) { !game.fading }
        assertEquals("marsh", game.courseForTest.id)
        run(300)
        frame("00-title-locked")
        press(GameView.B_PLAY)
        assertEquals(GameView.Mode.TITLE, game.mode)
        press(GameView.B_PREV_COURSE)
        runUntil(3000) { !game.fading }
        assertEquals("cove", game.courseForTest.id)

        // The book, before anything's been played.
        press(GameView.B_BOOK)
        assertEquals(GameView.Sheet.BOOK, game.sheetForTest)
        run(300)
        frame("01-book-stats-new")
        press(GameView.B_TAB_CARDS)
        assertEquals(1, game.bookTabForTest)
        press(GameView.B_DONE)
        assertEquals(GameView.Sheet.NONE, game.sheet)

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
        assertTrue("edge swipes kept from system Back", game.excludingGestures)
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
        assertFalse(game.excludingGestures)

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
                // Holding still on a short hole is just a cancelled touch: no peek, no stroke.
                // The pause chip opens a sheet without Restart in a round.
                touch(MotionEvent.ACTION_DOWN, game.width / 2f, game.height * 0.5f)
                run(600)
                assertFalse(game.isPeeking)
                touch(MotionEvent.ACTION_UP, game.width / 2f, game.height * 0.5f)
                run(100)
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
            if (i == 4) {
                // One shot in, Save + Quit, then Continue: same strokes, same lie. No free retry.
                playHole("h5-2", maxShots = 1)
                waitForRest()
                val strokesBefore = game.strokesForTest
                val bx = game.ballX
                val by = game.ballY
                press(GameView.B_PAUSE)
                press(GameView.B_QUIT)
                assertEquals(GameView.Mode.TITLE, game.mode)
                run(300)
                frame("h5-quit-title")
                val before = game.scoresForTest
                press(GameView.B_PLAY)
                assertEquals(GameView.Mode.PLAY, game.mode)
                assertEquals(4, game.holeIndex)
                assertEquals(before.toList(), game.scoresForTest.toList())
                assertEquals(strokesBefore, game.strokesForTest)
                assertEquals(bx, game.ballX, 0.01f)
                assertEquals(by, game.ballY, 0.01f)
                runUntil(3000) { !game.busy }
            }
            when (i) {
                0 -> playHole("h1-2", firstHoldMs = 500, expectPeek = false) // a short hole never peeks
                3 -> playHole("h4-2", firstHoldMs = 600, expectPeek = true) // peek, then pull: still a shot
                else -> playHole("h${i + 1}-2")
            }
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
                runUntil(2000) { !game.fading && !game.gliding }
            } else if (i == 1) {
                // Next hole: the view glides off this island and the next one rises from the sea.
                run(320)
                val (nx, ny) = game.buttonCenter(GameView.B_NEXT)!!
                tap(nx, ny)
                run(100)
                assertTrue("gliding to the next island", game.gliding)
                assertEquals("the old island scrolls away first", 1, game.holeIndex)
                frame("h2-4-glide-out")
                tap(game.width / 2f, game.height * 0.5f) // input waits for the glide
                runUntil(2000) { game.holeIndex == 2 }
                run(100)
                assertTrue(game.gliding)
                frame("h2-5-glide-in")
                runUntil(2000) { !game.gliding }
                assertEquals(GameView.Mode.PLAY, game.mode)
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

        // The book after a round: stats counted, and postcards for holes beaten under par.
        press(GameView.B_BOOK)
        run(300)
        frame("91-book-stats")
        press(GameView.B_TAB_CARDS)
        run(100)
        frame("91-book-postcards")
        press(GameView.B_DONE)

        // Left alone, the title's backdrop drifts to the next hole.
        val backdrop = game.holeIndex
        run(9000)
        runUntil(2000) { !game.gliding }
        assertEquals((backdrop + 1) % n, game.holeIndex)
        assertEquals(GameView.Mode.TITLE, game.mode)

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

        // Into the sea off the tee: a penalty stroke, and the ball is dropped near where it went in.
        drag(Math.PI.toFloat(), 1f)
        run(100)
        waitForRest()
        frame("93-after-splash")
        assertEquals(2, game.strokesForTest)
        assertTrue("dropped ashore, not on the tee", game.ballX < game.hole.teeX - 8f)
        assertTrue(game.hole.coast.isLand(game.ballX, game.ballY))

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

        // Left-handed moves the chip; reduce motion drops the flyover.
        press(GameView.B_PAUSE)
        press(GameView.B_SETTINGS)
        press(GameView.B_LEFT)
        press(GameView.B_MOTION)
        run(300)
        frame("95-settings-on")
        press(GameView.B_DONE)
        assertTrue(game.isPaused)
        press(GameView.B_RESUME)
        run(32)
        assertTrue("chip on the left", game.buttonCenter(GameView.B_PAUSE)!!.first < game.width / 2f)
        frame("96-left-handed")

        // A tap while the ball rolls fast-forwards it.
        drag(-1.5708f, 0.5f)
        run(48)
        assertTrue(game.ballMoving)
        tap(game.width / 2f, game.height * 0.4f)
        assertTrue("fast-forwarding", game.fastForwarding || !game.ballMoving)
        waitForRest()
        assertFalse(game.fastForwarding)

        // Practice: holding the pause chip restarts the hole at once.
        val (px0, py0) = game.buttonCenter(GameView.B_PAUSE)!!
        longPress(px0, py0, 900)
        runUntil(2000) { !game.fading }
        assertEquals("restarted", 0, game.strokesForTest)
        assertFalse(game.isPaused)

        // Practice pause offers Restart; Back closes the sheet.
        press(GameView.B_PAUSE)
        assertNotNull(game.buttonCenter(GameView.B_RESTART))
        assertTrue(game.onBack())
        assertFalse(game.isPaused)
        press(GameView.B_PAUSE)
        press(GameView.B_QUIT)
        assertEquals(GameView.Mode.TITLE, game.mode)

        // Tide Pools with reduce motion: straight to the tee, no flyover.
        press(GameView.B_HOLES)
        while (game.selectedHole < 3) press(GameView.B_NEXT_HOLE)
        while (game.selectedHole > 3) press(GameView.B_PREV_HOLE)
        press(GameView.B_PLAY_HOLE)
        assertEquals(3, game.holeIndex)
        assertFalse("no flyover with reduce motion", game.busy)

        // The round opened Saltmarsh Flats: swipe to it on the title and play its first hole,
        // a tidal one, in practice.
        press(GameView.B_BACK.takeIf { game.mode == GameView.Mode.SELECT } ?: GameView.B_PAUSE)
        if (game.isPaused) press(GameView.B_QUIT)
        assertEquals(GameView.Mode.TITLE, game.mode)
        swipe(-400f)
        runUntil(3000) { !game.fading }
        assertEquals("marsh", game.courseForTest.id)
        run(300)
        frame("97-title-saltmarsh")
        assertReachable(GameView.B_PREV_COURSE, 48f, 0.6f)
        press(GameView.B_HOLES)
        press(GameView.B_PLAY_HOLE)
        assertEquals(GameView.Mode.PLAY, game.mode)
        runUntil(3000) { !game.busy }
        frame("98-saltmarsh-tee")
        playHole("98-saltmarsh")
        assertTrue(game.ballInCup)

        assertTrue("labels that don't fit: ${game.textOverflows}", game.textOverflows.isEmpty())

        // Every glide (round, practice and the title's drift, Tide Pools included) swapped
        // islands over open sea: the old one gone off the top, the new one still below.
        assertTrue(game.glideSwaps.size >= 6)
        for (s in game.glideSwaps) {
            assertTrue("old island gone: view top ${s[0]} vs map height ${s[1]}", s[0] >= s[1])
            assertTrue("new island below: view top ${s[2]} with screen ${s[3]}", s[2] + s[3] <= 0f)
        }
    }
}

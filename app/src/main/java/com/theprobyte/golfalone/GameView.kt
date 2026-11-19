package com.theprobyte.golfalone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.theprobyte.golfalone.game.Course
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.HoleState
import com.theprobyte.golfalone.game.Holes
import com.theprobyte.golfalone.game.Physics
import com.theprobyte.golfalone.game.PhysicsEvents
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import com.theprobyte.golfalone.render.Ambient
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import com.theprobyte.golfalone.render.SpriteFx
import com.theprobyte.golfalone.render.Sprites
import com.theprobyte.golfalone.render.Terrain
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game on one view, drawn as pixel art.
 *
 * Everything is drawn into two small bitmaps about 180 logical pixels wide: the world
 * (terrain, ball, effects) and the UI on top. They're scaled up to the screen by a whole
 * number with filtering off, so every pixel stays square and crisp. The world bitmap is
 * one pixel larger than the view and shifted by the camera's sub-pixel remainder in whole
 * screen pixels, so the camera glides smoothly while the art stays on its grid.
 *
 * The UI is thumb-first (docs/STYLE_AND_UI_PLAN.md):
 * - Menus are bottom sheets of scorecard paper, with the main driftwood button lowest.
 * - Nothing you tap sits in the top quarter of the screen.
 * - While playing, the lower half stays free for the pull-back. Pause is a chip that fires
 *   only on a clean tap, and holding still peeks at the green.
 *
 * Buttons are registered as they're drawn each frame and fire on release.
 */
class GameView(
    context: Context,
    internal val progress: Progress,
    internal val sfx: Sfx,
    internal val haptics: Haptics,
) : View(context), PhysicsEvents {

    enum class Mode { TITLE, SELECT, PLAY, CARD, FINAL }
    enum class Sheet { NONE, PAUSE, SETTINGS, BOOK }

    var mode = Mode.TITLE
        private set
    var sheet = Sheet.NONE
        private set
    internal var sheetReturn = Sheet.NONE
    internal var sheetT = 0f

    // ---------------------------------------------------------------- course

    /** The course being played (the last one chosen, if it's open). */
    var course: Course = Holes.course(progress.lastCourse)?.takeIf { courseOpen(it) } ?: Holes.COVE
        private set
    /** The course's time of day, set before anything is baked from the palette. */
    @Suppress("unused") internal val moodSet = run { progress.course = course.id; Palette.setMood(course.mood) }
    internal val n get() = course.holes.size
    internal val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "terrain").apply { isDaemon = true } }
    internal var terrains: List<Future<Terrain>> = course.holes.map { h -> builder.submit<Terrain> { Terrain(h) } }
    internal var thumbs = arrayOfNulls<Bitmap>(n)
    internal var thumbBox = 0L

    var holeIndex = 0
        private set
    var hole: Hole = course.holes[0]
        private set
    /** What changes on this hole as you play (tide, crabs, planks, barrels). */
    internal var holeState = HoleState(hole)
    internal var physics = Physics(hole, holeState)
    /** Looks ahead along a shot near the cup, to slow down the finish of a great one. */
    internal var scratch = Physics(hole, HoleState(hole))
    internal val ambient = Ambient()
    internal val spriteFx = SpriteFx()
    internal lateinit var terrain: Terrain
    /** False until the first map is built (off the main thread); a splash shows meanwhile. */
    internal var ready = false
    /** Time from the view's creation to the first real frame (ms), for the overlay. */
    internal val createdAt = SystemClock.uptimeMillis()
    internal var startupMs = -1L
        private set
    internal var slopeTiles = IntArray(0)

    /** A full round (scores count) rather than practising one hole. */
    var courseRun = false
        private set
    internal var scores = IntArray(n)
    internal var courseNewBest = false
    internal var flownOver = BooleanArray(n)

    /** The hole shown in the select carousel, and its slide animation. */
    internal var sel = 0
    internal var selT = 1f
    internal var selDir = 0

    // ---------------------------------------------------------------- buffers

    internal var scale = 1
    internal var lw = 0
    internal var lh = 0
    internal var offX = 0f
    internal var offY = 0f
    internal var world: Bitmap? = null
    internal var ui: Bitmap? = null
    internal val wc = Canvas()
    internal val uc = Canvas()
    internal val blit = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    internal val tidePaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    internal val dim = Paint().apply { isFilterBitmap = false; colorFilter = android.graphics.PorterDuffColorFilter(0xB0101828.toInt(), PorterDuff.Mode.SRC_ATOP) }
    internal val src = Rect()
    internal val dst = RectF()
    internal val density = resources.displayMetrics.density
    internal val clouds = Array(3) { Sprites.cloudShadow(it + 3) }

    // Insets (screen px)
    internal var insetTopPx = 0
    internal var insetBottomPx = 0
    internal var gestLeft = 0
    internal var gestRight = 0
    internal var gestBottom = 0
    internal val insetTop get() = ceil(insetTopPx / scale.toFloat()).toInt()
    internal val insetBottom get() = ceil(insetBottomPx / scale.toFloat()).toInt()
    /** Lowest y a button may reach: above the nav bar and the Home-swipe zone. */
    internal val safeBottom get() = lh - max(insetBottom, ceil(gestBottom / scale.toFloat()).toInt()) - 6

    // ---------------------------------------------------------------- state

    internal var camX = 0f
    internal var camY = 0f
    internal var camSnap = true
    internal var lastMs = 0L
    internal var time = 0f
    internal var acc = 0f

    internal var strokes = 0
    internal var sinkT = -1f
    internal var cardT = 0f
    internal var modeT = 0f
    internal var holeT = 0f
    internal var newBest = false
    internal var lastWallHaptic = 0f
    internal var popT = -1f
    internal var shakeT = -1f
    internal var rollDist = 0f
    internal var teeterT = -1f
    internal var cameraMoving = false
    /** [time] stepped at 15 Hz, for animations that must keep still between idle frames. */
    internal val steppedTime get() = floor(time * 15f) / 15f
    // Hole-to-hole glide: the view slides off the old island (offset 0 → +D, so it scrolls up)
    // and the new one rises from below (−D → 0), across open sea.
    internal var glideT = -1f
    internal var glideOffset = 0f
    internal var glideOffsetX = 0f
    internal var glideFromX = 0f
    internal var glideFromY = 0f
    internal var glideAction: (() -> Unit)? = null
    internal var glideBlocks = false
    internal var titlePanAt = TITLE_PAN_S
    /** The hole last really played (the title's backdrop drifts; hole select opens on this one). */
    internal var lastPlayed = 0
    // Celebrations: gulls rising from the cup, a camera punch, a slowed finish, the card stamp.
    internal var celebrateT = -1f
    internal var punchT = -1f
    internal var slowMo = false
    internal var slowChecked = false
    internal var stamped = false
    internal var stampT = -1f
    internal var greatHole = false
    // The shot in flight: its power (for the trail's colour) and where it started (longest shot).
    internal var shotPower = 0f
    internal var shotX = 0f
    internal var shotY = 0f
    internal var shotLive = false
    internal var bookTab = 0
    /** A course the round just finished has opened (shown on the scorecard), or null. */
    internal var opened: String? = null
    /** Time since the tide last turned (-1: settled). */
    internal var tideT = -1f
    /** The last buoy hit: when (-1: none) and where, for its wobble. */
    internal var bumpT = -1f
    internal var bumpX = 0f
    internal var bumpY = 0f
    /** Bumped on any input or state change the UI layer shows; see [uiNeedsRedraw]. */
    internal var uiVersion = 0
    internal var uiDrawnVersion = -1
    internal var uiDrawnKey = 0L
    internal var worstFrameMs = 0f
    internal var worstDrawMs = 0f
    /** The player tapped while the ball rolled: run the roll at [FAST_FORWARD]×. */
    internal var fastForward = false
    internal var flyover = false

    internal var fadeT = -1f
    internal var fadeAction: (() -> Unit)? = null
    internal var fadeDone = false

    internal val reduceMotion get() = progress.reduceMotion

    // Aim and peek
    internal var aiming = false
    internal var aimPointer = -1
    internal var aimStartT = 0f
    internal var aimMoved = false
    internal var peeking = false
    internal var downX = 0f
    internal var downY = 0f
    internal var curX = 0f
    internal var curY = 0f
    internal var power = 0f
    internal var tickStep = 0
    internal val cancelPx get() = 14f * density
    internal val slopPx get() = 8f * density
    internal val maxDragPx get() = DRAG_LENGTHS[progress.dragLength] * width

    // Other gestures
    internal var swipeX = Float.NaN
    internal var swipeY = 0f
    internal var tapAnywhere = false
    internal var tapOutside = false
    internal var logoDownT = -1f

    // Effects
    internal val rnd = Random(7)
    internal val pn = 160
    internal val pX = FloatArray(pn); internal val pY = FloatArray(pn)
    internal val pVX = FloatArray(pn); internal val pVY = FloatArray(pn)
    internal val pLife = FloatArray(pn); internal val pMax = FloatArray(pn)
    internal val pCol = IntArray(pn)
    internal var pNext = 0

    internal val rn = 8
    internal val rX = FloatArray(rn); internal val rY = FloatArray(rn)
    internal val rT = FloatArray(rn) { -1f }; internal val rDur = FloatArray(rn)
    internal val rRad = FloatArray(rn); internal val rCol = IntArray(rn)
    internal var rNext = 0

    internal val tn = 10
    internal val tX = FloatArray(tn); internal val tY = FloatArray(tn); internal val tAt = FloatArray(tn) { -9f }
    internal var tNext = 0
    internal var tLast = 0f

    internal var msg: String? = null
    internal var msgX = 0f
    internal var msgY = 0f
    internal var msgT = 0f
    internal var msgCol = Palette.INK
    internal var msgUi = false

    internal var gullT = -1f
    internal var gullY = 0f
    internal var gullDir = 1f
    internal var gullNext = 4f
    internal var surfNext = 1.5f

    // Frame times for the debug readout.
    internal val frameMs = FloatArray(120)
    internal var frameNext = 0
    /** How long each frame took to update and draw (ms), separate from the time between frames. */
    internal val drawMs = FloatArray(120)
    internal var drawNext = 0

    // ---------------------------------------------------------------- buttons

    internal val maxButtons = 24
    internal val btnIds = IntArray(maxButtons)
    internal val btnHit = Array(maxButtons) { Rect() }
    internal var btnCount = 0
    internal var pressedId = NONE
    internal var pressStartT = 0f
    internal var pressX = 0
    internal var pressY = 0
    internal val sheetRect = Rect()

    /** Labels that didn't fit their button (checked by the UI test). */
    internal val textOverflows = LinkedHashSet<String>()

    init {
        isFocusable = true
        setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
    }

    @Suppress("DEPRECATION")
    internal fun applyInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val g = insets.getInsets(WindowInsets.Type.systemGestures())
            insetTopPx = bars.top; insetBottomPx = bars.bottom
            gestLeft = g.left; gestRight = g.right; gestBottom = g.bottom
        } else {
            insetTopPx = insets.systemWindowInsetTop; insetBottomPx = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 29) {
                val g = insets.systemGestureInsets
                gestLeft = g.left; gestRight = g.right; gestBottom = g.bottom
            }
        }
        camSnap = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        scale = max(1, (w / LOGICAL_W.toFloat()).roundToInt())
        lw = ceil(w / scale.toFloat()).toInt()
        lh = ceil(h / scale.toFloat()).toInt()
        offX = ((w - lw * scale) / 2).toFloat()
        offY = ((h - lh * scale) / 2).toFloat()
        world?.recycle(); ui?.recycle()
        world = Pix.bitmap(lw + 1, lh + 1, Bitmap.Config.ARGB_8888).also { wc.setBitmap(it) }
        ui = Pix.bitmap(lw, lh, Bitmap.Config.ARGB_8888).also { uc.setBitmap(it) }
        if (mode == Mode.PLAY && strokes == 0 && holeT < 1.6f && wantsFlyover()) flyover = true
        camSnap = true
    }

    // ---------------------------------------------------------------- flow

    /** [backdrop]: only showing the hole behind the title, so it doesn't use up the hole's flyover. */
    internal fun loadHole(i: Int, backdrop: Boolean = false) {
        holeIndex = i
        if (!backdrop) lastPlayed = i
        hole = course.holes[i]
        holeState = HoleState(hole)
        physics = Physics(hole, holeState)
        scratch = Physics(hole, HoleState(hole))
        terrain = terrains[i].get()
        ambient.reset(terrain)
        spriteFx.clear()
        celebrateT = -1f; punchT = -1f; slowMo = false; slowChecked = false; shotLive = false
        // Keep water for this hole and the next one shown (the title drifts round to hole 1).
        val next = if (backdrop) (i + 1) % n else i + 1
        for (k in 0 until n) if (k != i && k != next && terrains[k].isDone) terrains[k].get().releaseWater()
        if (next < n) builder.submit { terrains[next].get().prepareWater() }
        slopeTiles = (0 until hole.w * hole.h)
            .filter { val t = hole.tiles[it]; t.slopeX != 0f || t.slopeY != 0f }.toIntArray()
        strokes = 0
        sinkT = -1f
        newBest = false
        aiming = false
        peeking = false
        sheet = Sheet.NONE
        popT = -1f
        rollDist = 0f
        holeT = 0f
        worstFrameMs = 0f
        worstDrawMs = 0f
        uiVersion++
        for (k in 0 until pn) pLife[k] = 0f
        for (k in 0 until rn) rT[k] = -1f
        for (k in 0 until tn) tAt[k] = -9f
        msg = null
        // A long hole opens on the flag the first time you see it, then glides back to the tee.
        flyover = !backdrop && lh > 0 && wantsFlyover()
        if (flyover) flownOver[i] = true
        camSnap = true
    }

    /** A course is open once the round before it has been finished. */
    internal fun courseOpen(c: Course): Boolean {
        val i = Holes.COURSES.indexOf(c)
        return i <= 0 || progress.courseBestOf(Holes.COURSES[i - 1].id) > 0
    }

    /**
     * Changes course: the old maps stop building, the palette moves to the new time of day
     * (only once the builder is idle, so no map is ever baked half in one mood), and the
     * new course's maps start building.
     */
    internal fun switchCourse(c: Course) {
        if (c === course) return
        for (f in terrains) f.cancel(false)
        builder.submit {}.get() // wait out a map that's mid-build
        for (f in terrains) if (f.isDone && !f.isCancelled) runCatching { f.get().releaseWater() }
        course = c
        progress.course = c.id
        progress.lastCourse = c.id
        Palette.setMood(c.mood)
        terrains = c.holes.map { h -> builder.submit<Terrain> { Terrain(h) } }
        thumbs = arrayOfNulls(n); thumbBox = 0L
        postcardThumbs = arrayOfNulls(n); postcardBox = 0L
        scores = IntArray(n)
        flownOver = BooleanArray(n)
        lastPlayed = 0
        sel = 0
        courseRun = false
        uiVersion++
    }

    /** On the title: show the next or previous course (locked ones too, as a preview). */
    internal fun moveCourse(d: Int) {
        val list = Holes.COURSES
        val k = (list.indexOf(course) + d).coerceIn(0, list.size - 1)
        if (list[k] === course || fadeT >= 0f) return
        sfx.tap()
        haptics.tap(this)
        fadeTo { switchCourse(list[k]); loadHole(0, backdrop = true); setMode(Mode.TITLE) }
    }

    internal fun wantsFlyover() = isLong() && !flownOver[holeIndex] && !reduceMotion

    internal fun fitsVertically() = hole.pxH <= playBottom - playTop

    /** The green is out of sight from the tee: worth a flyover, and a peek hint. */
    internal fun isLong() = hole.pxH - (playBottom - playTop) > LONG_SCROLL

    internal fun setMode(m: Mode) {
        mode = m
        modeT = 0f
        pressedId = NONE
        sheet = Sheet.NONE
        tapAnywhere = false
        if (m == Mode.CARD) { cardT = 0f; stamped = false; stampT = -1f }
        if (m == Mode.TITLE) titlePanAt = TITLE_PAN_S
        if (m != Mode.PLAY) flyover = false
        keepScreenOn = m == Mode.PLAY
        applyImmersive()
    }

    /** Called with true to hide the status bar (playing, if the setting is on), false to show it. */
    var onImmersive: ((Boolean) -> Unit)? = null

    internal fun applyImmersive() {
        // Cards count as play, so the bar doesn't slide in and out between holes.
        onImmersive?.invoke((mode == Mode.PLAY || mode == Mode.CARD) && progress.hideStatusBar)
    }

    /** Dissolves out, runs [action], dissolves back in. Input waits until it's done. */
    internal fun fadeTo(action: () -> Unit) {
        if (fadeT >= 0f) return
        if (glideT >= 0f) { glideT = -1f; glideAction = null; glideOffset = 0f; glideOffsetX = 0f } // the fade hides it
        fadeT = 0f
        fadeAction = action
        fadeDone = false
        sfx.whoosh()
    }

    /**
     * Slides the view off this island and onto the next one across the sea, running [action]
     * (which loads the hole) at the halfway point. [blockInput] holds input until it lands.
     */
    internal fun glideTo(blockInput: Boolean, action: () -> Unit) {
        if (glideT >= 0f || fadeT >= 0f) return
        glideT = 0f
        glideAction = action
        glideBlocks = blockInput
        sfx.whoosh()
    }

    internal fun updateGlide(dt: Float) {
        if (glideT < 0f) return
        glideT += dt
        val act = glideAction
        if (act != null) {
            // Out: far enough that the whole old island has left the top of the screen, with a
            // margin because the last frame drawn before the swap falls a little short.
            val out = max(lh + 16f, hole.pxH + 16f - camY) * 1.15f
            if (glideT < GLIDE_S) { val k = glideT / GLIDE_S; glideOffset = out * k * k; return }
            // Halfway: swap islands. The new view starts where the sea's pattern lines up with
            // the old one, so the water runs on without a jump.
            val oldX = camX + glideOffsetX
            val oldY = camY + glideOffset
            val oldH = hole.pxH.toFloat()
            // The title's drift gives way to anything you've opened since it began.
            if (glideBlocks || (mode == Mode.TITLE && sheet == Sheet.NONE)) act()
            glideAction = null
            updateCamera(0f)
            // In: start with the new island's top below the screen, however tall it is.
            val d = camY + lh + 16f
            glideFromY = -d - floorModF(camY - d - oldY, Terrain.SEA_H.toFloat())
            if (glideSwaps.size < 64) glideSwaps += floatArrayOf(oldY, oldH, camY + glideFromY, lh.toFloat())
            var gx = floorModF(oldX - camX, Terrain.SEA_W.toFloat())
            if (gx > Terrain.SEA_W / 2f) gx -= Terrain.SEA_W
            glideFromX = gx
            glideT = GLIDE_S
        }
        val k = ((glideT - GLIDE_S) / GLIDE_S).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k)
        glideOffset = glideFromY * (1f - e)
        glideOffsetX = glideFromX * (1f - e)
        if (k >= 1f) { glideT = -1f; glideOffset = 0f; glideOffsetX = 0f }
    }

    internal fun floorModF(a: Float, m: Float): Float { val r = a % m; return if (r < 0f) r + m else r }

    internal fun openSheet(s: Sheet) {
        sheetReturn = if (s == Sheet.SETTINGS) sheet else Sheet.NONE
        sheet = s
        sheetT = 0f
        aiming = false
        peeking = false
    }

    internal fun closeSheet() {
        sheet = if (sheet == Sheet.SETTINGS) sheetReturn else Sheet.NONE
        sheetReturn = Sheet.NONE
        sheetT = 1f
    }

    fun startCourse() = fadeTo {
        courseRun = true
        scores.fill(0)
        courseNewBest = false
        progress.clearRun()
        loadHole(0)
        setMode(Mode.PLAY)
    }

    internal fun continueRound() = fadeTo {
        courseRun = true
        val saved = progress.runScores(n)
        for (i in 0 until n) scores[i] = saved[i]
        courseNewBest = false
        loadHole(progress.runHole.coerceIn(0, n - 1))
        // Pick the hole up where it was left: strokes and the ball's lie.
        val held = progress.runStrokes
        if (held > 0) {
            strokes = held
            holeState.decode(progress.runState)
            physics.placeAt(progress.runBallX, progress.runBallY)
            flyover = false
            camSnap = true
        }
        setMode(Mode.PLAY)
    }

    internal fun practise(i: Int) = fadeTo {
        courseRun = false
        loadHole(i)
        setMode(Mode.PLAY)
    }

    internal fun toTitle() = fadeTo {
        physics.reset()
        aiming = false
        setMode(Mode.TITLE)
    }

    internal fun toSelect() = fadeTo {
        aiming = false
        sel = lastPlayed
        selT = 1f
        setMode(Mode.SELECT)
    }

    internal val hasSavedRound get() = progress.runHole in 0 until n && (progress.runHole > 0 || progress.runStrokes > 0)

    /** System back: close a sheet, pause a hole, or step back towards the title. */
    fun onBack(): Boolean {
        if (fadeT >= 0f || (glideT >= 0f && glideBlocks)) return true
        if (sheet != Sheet.NONE) { closeSheet(); sfx.tap(); return true }
        when (mode) {
            Mode.TITLE -> return false
            Mode.PLAY -> { openSheet(Sheet.PAUSE); sfx.tap() }
            Mode.SELECT -> toTitle()
            Mode.CARD -> if (courseRun) sayUi("TAP TO CARRY ON") else toSelect()
            Mode.FINAL -> toTitle()
        }
        return true
    }

    /** In a round, remember this hole's strokes and where the ball lies, so quitting is no free retry. */
    internal fun saveHoleInProgress() {
        if (!courseRun || mode != Mode.PLAY || sinkT >= 0f) return
        if (physics.moving) {
            // Play the shot out first, so the ball is saved where it really stops. It runs
            // through the normal events: a splash costs its stroke, and a holed ball goes down
            // the usual cup path (score, saved round, and the card when the game comes back).
            physics.settle(this)
            if (physics.inCup) return
            shotOver()
        }
        progress.saveRun(holeIndex, scores, strokes, physics.restX, physics.restY, holeState.encode())
    }

    /**
     * A shot has come to rest: the tide and crabs move on. If the tide covers the ball it's
     * lifted clear for free.
     */
    internal fun shotOver() {
        if (physics.inCup) return
        val wasHigh = holeState.high
        if (physics.endShot()) say("LIFTED CLEAR", physics.x, physics.y - 8f, Palette.INK)
        if (holeState.high != wasHigh) onTideTurn(holeState.high)
        uiVersion++
    }

    /** The tide has turned (drawn as a crossfade between the two maps). */
    internal fun onTideTurn(high: Boolean) {
        tideT = 0f
        sfx.tide(high)
    }

    /** The app went to the background: pause any hole in progress and save the round. */
    fun onHostPause() {
        saveHoleInProgress()
        if (mode == Mode.PLAY && sinkT < 0f && sheet == Sheet.NONE) openSheet(Sheet.PAUSE)
        aiming = false
        peeking = false
        pressedId = NONE
    }

    internal fun toPar(): Int {
        var s = 0
        for (i in 0 until n) if (scores[i] > 0) s += scores[i] - course.holes[i].par
        return s
    }

    internal fun parText(d: Int) = when { d == 0 -> "E"; d > 0 -> "+$d"; else -> "$d" }

    // ---------------------------------------------------------------- input

    internal fun inGestureZone(x: Float, y: Float) =
        x < gestLeft || x > width - gestRight || y > height - gestBottom

    internal fun toLX(sx: Float) = (sx - offX) / scale
    internal fun toLY(sy: Float) = (sy - offY) / scale

    internal fun hitButton(lx: Int, ly: Int): Int {
        for (i in btnCount - 1 downTo 0) if (btnHit[i].contains(lx, ly)) return btnIds[i]
        return NONE
    }

    internal val canAim get() = mode == Mode.PLAY && sheet == Sheet.NONE && !physics.moving && !physics.inCup && !flyover

    internal fun startAim(x: Float, y: Float, pointer: Int) {
        aiming = true
        aimPointer = pointer
        aimStartT = time
        aimMoved = false
        peeking = false
        downX = x; downY = y; curX = x; curY = y
        power = 0f; tickStep = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        wake()
        uiVersion++
        if (fadeT >= 0f || !ready || (glideT >= 0f && glideBlocks)) return true
        val lx = floor(toLX(e.x)).toInt()
        val ly = floor(toLY(e.y)).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapAnywhere = false; tapOutside = false; swipeX = Float.NaN; logoDownT = -1f
                downX = e.x; downY = e.y
                val id = hitButton(lx, ly)
                if (id != NONE) {
                    pressedId = id; pressX = lx; pressY = ly
                    pressStartT = time
                    if (id == B_VOL_FX || id == B_VOL_SURF) setSlider(id, lx)
                    haptics.tap(this)
                } else if (sheet != Sheet.NONE) {
                    tapOutside = !sheetRect.contains(lx, ly)
                } else when (mode) {
                    Mode.PLAY -> if (flyover) flyover = false // a tap skips the flyover
                    else if (physics.moving && sheet == Sheet.NONE) fastForward = true // a tap hurries a long roll
                    else if (canAim && !inGestureZone(e.x, e.y)) startAim(e.x, e.y, e.getPointerId(0))
                    Mode.SELECT -> { swipeX = e.x; swipeY = e.y }
                    Mode.CARD -> tapAnywhere = courseRun && cardT > CARD_READY
                    Mode.TITLE -> { if (ly < lh / 2) logoDownT = time; swipeX = e.x; swipeY = e.y }
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // A drag that starts on the pause chip is a shot, not a tap.
                if (pressedId == B_PAUSE && hypot(e.x - downX, e.y - downY) > slopPx && canAim) {
                    pressedId = NONE
                    startAim(downX, downY, e.getPointerId(0))
                }
                if (aiming) {
                    val i = e.findPointerIndex(aimPointer)
                    if (i >= 0) {
                        val mx = e.getX(i)
                        val my = e.getY(i)
                        val d = hypot(mx - downX, my - downY)
                        // Pulling past the cancel ring ends a peek and aims as usual.
                        if (peeking && d > cancelPx) peeking = false
                        if (!peeking) {
                            curX = mx; curY = my
                            if (d > slopPx) aimMoved = true
                            updatePower()
                        }
                    }
                }
                if (pressedId != NONE) { pressX = lx; pressY = ly }
                if (pressedId == B_VOL_FX || pressedId == B_VOL_SURF) setSlider(pressedId, lx)
                if (tapAnywhere && hypot(e.x - downX, e.y - downY) > slopPx) tapAnywhere = false
                if (logoDownT >= 0f && hypot(e.x - downX, e.y - downY) > slopPx) logoDownT = -1f
            }
            MotionEvent.ACTION_POINTER_UP -> if (aiming && e.getPointerId(e.actionIndex) == aimPointer) {
                finishAim(e.getX(e.actionIndex), e.getY(e.actionIndex))
            }
            MotionEvent.ACTION_UP -> {
                if (aiming) {
                    val i = e.findPointerIndex(aimPointer).coerceAtLeast(0)
                    finishAim(e.getX(i), e.getY(i))
                }
                val id = pressedId
                pressedId = NONE
                if (id == B_VOL_FX || id == B_VOL_SURF) sfx.tap() // a click at the new level
                else if (id != NONE && hitButton(lx, ly) == id) onButton(id)
                if (!swipeX.isNaN()) {
                    val dx = e.x - swipeX
                    if (abs(dx) > 36f * density && abs(dx) > abs(e.y - swipeY) * 1.5f) {
                        if (mode == Mode.TITLE) moveCourse(if (dx < 0) 1 else -1) else moveSelection(if (dx < 0) 1 else -1)
                    }
                    swipeX = Float.NaN
                }
                if (tapAnywhere && id == NONE) { tapAnywhere = false; nextHole() }
                if (tapOutside) { tapOutside = false; closeSheet(); sfx.tap() }
                logoDownT = -1f
            }
            MotionEvent.ACTION_CANCEL -> {
                aiming = false; peeking = false; pressedId = NONE; swipeX = Float.NaN
                tapAnywhere = false; tapOutside = false; logoDownT = -1f
            }
        }
        return true
    }

    internal fun finishAim(x: Float, y: Float) {
        aiming = false
        if (peeking) { peeking = false; return } // a peek never shoots
        curX = x; curY = y
        updatePower()
        if (power > 0f) shoot()
    }

    internal fun moveSelection(d: Int) {
        val next = (sel + d).coerceIn(0, n - 1)
        if (next == sel) return
        sel = next
        selDir = d
        selT = 0f
        sfx.tap()
        haptics.tap(this)
    }

    internal fun onButton(id: Int) {
        sfx.tap()
        when (id) {
            B_PLAY -> if (!courseOpen(course)) sayUi("FINISH ${Holes.COURSES[Holes.COURSES.indexOf(course) - 1].name} FIRST")
                else if (hasSavedRound) continueRound() else startCourse()
            B_PREV_COURSE -> moveCourse(-1)
            B_NEXT_COURSE -> moveCourse(1)
            B_NEW -> startCourse()
            B_HOLES -> if (courseOpen(course)) toSelect() else sayUi("LOCKED FOR NOW")
            B_BACK -> toTitle()
            B_SETTINGS -> openSheet(Sheet.SETTINGS)
            B_BOOK -> { bookTab = 0; openSheet(Sheet.BOOK) }
            B_TAB_STATS -> bookTab = 0
            B_TAB_CARDS -> bookTab = 1
            B_DONE -> closeSheet()
            B_STATUS -> { progress.hideStatusBar = !progress.hideStatusBar; applyImmersive() }
            B_PULL -> progress.dragLength = (progress.dragLength + 1) % 3
            B_BUZZ -> { progress.haptics = !progress.haptics; haptics.enabled = progress.haptics; haptics.tap(this) }
            B_LEFT -> progress.leftHanded = !progress.leftHanded
            B_MOTION -> progress.reduceMotion = !progress.reduceMotion
            B_PAUSE -> openSheet(Sheet.PAUSE)
            B_RESUME -> closeSheet()
            B_RESTART -> { val i = holeIndex; fadeTo { loadHole(i); setMode(Mode.PLAY) } }
            B_QUIT -> {
                saveHoleInProgress()
                // Holed out on the last hole while quitting: the round is complete, so keep its best.
                if (courseRun && physics.inCup && holeIndex == n - 1) {
                    progress.recordCourse(scores.sum())
                    progress.clearRun()
                }
                toTitle()
            }
            B_NEXT -> nextHole()
            B_RETRY -> practise(holeIndex)
            B_SELECT -> toSelect()
            B_MENU -> toTitle()
            B_AGAIN -> startCourse()
            B_PREV_HOLE -> moveSelection(-1)
            B_NEXT_HOLE -> moveSelection(1)
            B_PLAY_HOLE -> if (progress.unlocked(sel)) practise(sel) else sayUi("FINISH HOLE $sel TO UNLOCK")
        }
    }

    internal fun nextHole() {
        val next = holeIndex + 1
        if (courseRun && next >= n) {
            fadeTo {
                val ci = Holes.COURSES.indexOf(course)
                val nextCourse = Holes.COURSES.getOrNull(ci + 1)
                val wasOpen = nextCourse == null || courseOpen(nextCourse)
                courseNewBest = progress.recordCourse(scores.sum())
                opened = if (!wasOpen && nextCourse != null && courseOpen(nextCourse)) nextCourse.name else null
                progress.clearRun()
                setMode(Mode.FINAL)
            }
        } else if (next < n) {
            val go = { loadHole(next); setMode(Mode.PLAY) }
            if (reduceMotion) fadeTo(go) else glideTo(true, go)
        } else toSelect()
    }

    internal fun updatePower() {
        val len = hypot(downX - curX, downY - curY)
        power = if (len < cancelPx) 0f else ((len - cancelPx) / (maxDragPx - cancelPx)).coerceIn(0.02f, 1f)
        val step = floor(power * 10f + 1e-4f).toInt()
        if (step > tickStep) {
            haptics.tension(this, step, step >= 10)
            sfx.tension(step, step >= 10)
            tickStep = step
        } else if (power * 10f < tickStep - 0.6f) {
            tickStep = step // re-arm only once clearly back below, so a resting finger doesn't buzz
        }
    }

    internal fun shoot() {
        val dx = downX - curX
        val dy = downY - curY
        physics.shoot(dx, dy, power)
        strokes++
        shotPower = power
        shotX = physics.x; shotY = physics.y; shotLive = true
        slowChecked = false
        sfx.strike(power, pan(physics.x))
        haptics.strike(this, power)
        // Grass clippings kicked up behind the ball.
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        repeat(4 + (power * 6).toInt()) {
            val a = rnd.nextFloat() * 1.4f - 0.7f
            val s = 20f + rnd.nextFloat() * 50f * (0.4f + power)
            spawn(physics.x, physics.y, (-ux * cos(a) + uy * sin(a)) * s, (-uy * cos(a) - ux * sin(a)) * s,
                0.35f + rnd.nextFloat() * 0.3f, if (rnd.nextBoolean()) Palette.FAIR_DOT else Palette.ROUGH_LIGHT)
        }
        ring(physics.x, physics.y, 0.25f, 5f, Palette.INK)
        spriteFx.swoosh(physics.x, physics.y, ux, uy)
    }

    /** The aim and trail colour for a shot of [p] power: white, warming to gold, then hot. */
    internal fun powerColour(p: Float) = if (p < 0.6f) Palette.lerp(Palette.INK, Palette.GOLD, p / 0.6f)
        else Palette.lerp(Palette.GOLD, Palette.HOT, (p - 0.6f) / 0.4f)

    /** Records how far the shot went, from where it was struck to where it ended. */
    internal fun endShot(x: Float, y: Float) {
        if (!shotLive) return
        shotLive = false
        progress.longestShot = hypot(x - shotX, y - shotY).roundToInt()
    }

    // ---------------------------------------------------------------- physics events

    internal fun pan(wx: Float) = if (lw == 0) 0f else (((wx - camX) / lw) * 2f - 1f).coerceIn(-1f, 1f) * 0.8f

    override fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) {
        sfx.wall(impact, pan(x), wood)
        if (time - lastWallHaptic > 0.04f) { haptics.wall(this, impact); lastWallHaptic = time }
        if (impact > 170f && !reduceMotion) shakeT = 0f
        repeat((impact / 70f).toInt().coerceIn(1, 4)) {
            spawn(x, y, rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 40f - 20f, 0.3f,
                if (wood) Palette.WOOD_LIGHT else Palette.STONE_LIGHT)
        }
    }

    override fun onSand(x: Float, y: Float, speed: Float) {
        sfx.sand(pan(x))
        haptics.sand(this)
        repeat(10) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 15f + rnd.nextFloat() * 35f
            spawn(x, y, cos(a) * s, sin(a) * s, 0.4f + rnd.nextFloat() * 0.3f, if (it % 2 == 0) Palette.SAND_LIGHT else Palette.SAND_DARK)
        }
    }

    override fun onLaunch(x: Float, y: Float, speed: Float) {
        progress.recordJump()
        sfx.launch(speed, pan(x))
        haptics.tap(this)
        repeat(6) {
            spawn(x, y, rnd.nextFloat() * 30f - 15f, rnd.nextFloat() * 20f, 0.35f, Palette.WOODS[3])
        }
    }

    override fun onLand(x: Float, y: Float, impact: Float) {
        sfx.land(impact, pan(x), hole.at(x, y) == Tile.SAND)
        haptics.wall(this, impact)
        val col = when (hole.at(x, y)) { Tile.SAND -> Palette.SAND_LIGHT; else -> Palette.ROUGH_LIGHT }
        repeat((impact / 25f).toInt().coerceIn(2, 8)) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 10f + rnd.nextFloat() * 25f
            spawn(x, y, cos(a) * s, sin(a) * s * 0.5f, 0.35f, col)
        }
        ring(x, y, 0.3f, 4f, Palette.alpha(Palette.INK, 160))
        if (!hole.at(x, y).water) spriteFx.dust(x, y, if (hole.at(x, y) == Tile.SAND) Palette.SAND_LIGHT else Palette.PAPER[3])
    }

    override fun onTeeter(x: Float, y: Float) {
        teeterT = 0f
        haptics.lipOut(this)
        sfx.lipOut(pan(x))
        say("ON THE EDGE!", x, y - 8f, Palette.GOLD)
    }

    override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) {
        strokes++ // penalty
        shotLive = false
        spriteFx.splash(x, y)
        sfx.splash(pan(x))
        haptics.splash(this)
        if (!reduceMotion) shakeT = 0f
        repeat(16) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 20f + rnd.nextFloat() * 45f
            spawn(x, y, cos(a) * s, sin(a) * s, 0.45f + rnd.nextFloat() * 0.35f, if (it % 3 == 0) Palette.SPARKLE else Palette.FOAM)
        }
        ring(x, y, 0.7f, 10f, Palette.FOAM)
        ring(x, y, 1.0f, 16f, Palette.SPARKLE)
        say("+1 SPLASH", x, y - 8f, Palette.FOAM)
        popT = 0f
        sfx.pop(pan(dropX))
        ring(dropX, dropY, 0.5f, 6f, Palette.INK) // where it's dropped
        for (i in 0 until tn) tAt[i] = -9f
    }

    override fun onBumper(x: Float, y: Float) {
        sfx.bell(pan(x))
        haptics.wall(this, 200f)
        bumpT = 0f; bumpX = x; bumpY = y
        ring(x, y, 0.3f, 6f, Palette.GOLD)
    }

    override fun onBreak(x: Float, y: Float) {
        sfx.crack(pan(x))
        haptics.splash(this)
        if (!reduceMotion) shakeT = 0f
        repeat(14) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 20f + rnd.nextFloat() * 50f
            spawn(x, y, cos(a) * s, sin(a) * s, 0.5f + rnd.nextFloat() * 0.3f, if (it % 2 == 0) Palette.WOOD_LIGHT else Palette.WOOD_DARK)
        }
        say("SMASH!", x, y - 8f, Palette.GOLD)
    }

    override fun onBarrel(x: Float, y: Float, impact: Float) {
        sfx.barrel(impact, pan(x))
        haptics.wall(this, impact)
    }

    override fun onBarrelSink(x: Float, y: Float) {
        sfx.splash(pan(x))
        spriteFx.splash(x, y)
        ring(x, y, 0.7f, 9f, Palette.FOAM)
    }

    override fun onLipOut(x: Float, y: Float, speed: Float) {
        sfx.lipOut(pan(x))
        haptics.lipOut(this)
        say("LIP OUT!", hole.cupX, hole.cupY - 20f, Palette.GOLD)
    }

    override fun onStop(x: Float, y: Float) {
        endShot(x, y)
        if (hole.at(x, y) == Tile.ROUGH) repeat(4) {
            spawn(x, y + 1f, rnd.nextFloat() * 16f - 8f, -rnd.nextFloat() * 10f, 0.35f, Palette.ROUGH_LIGHT)
        }
        if (!physics.inCup && hypot(x - hole.cupX, y - hole.cupY) < SO_CLOSE) say("SO CLOSE!", hole.cupX, hole.cupY - 12f, Palette.GOLD)
    }

    override fun onCup(x: Float, y: Float, speed: Float) {
        sinkT = 0f
        endShot(hole.cupX, hole.cupY)
        val toParHole = strokes - hole.par
        // An ace, eagle or better: gulls lift off the green and the camera punches in.
        greatHole = strokes == 1 || toParHole <= -2
        if (greatHole && !reduceMotion) { celebrateT = 0f; punchT = 0f }
        newBest = progress.record(holeIndex, strokes, hole.par)
        if (courseRun) {
            scores[holeIndex] = strokes
            if (holeIndex + 1 < n) progress.saveRun(holeIndex + 1, scores)
        }
        sfx.cup(pan(x), toParHole, strokes == 1)
        haptics.cup(this, toParHole < 0 || strokes == 1)
        ring(hole.cupX, hole.cupY, 0.6f, 8f, Palette.INK)
        val gold = toParHole <= 0
        repeat(if (gold) 22 else 10) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 25f + rnd.nextFloat() * 50f
            spawn(hole.cupX, hole.cupY - 8f, cos(a) * s, sin(a) * s - 20f, 0.6f + rnd.nextFloat() * 0.5f,
                if (gold) (if (it % 2 == 0) Palette.GOLD else Palette.INK) else Palette.INK)
        }
    }

    // ---------------------------------------------------------------- effects

    internal fun spawn(x: Float, y: Float, vx: Float, vy: Float, life: Float, col: Int) {
        val i = pNext; pNext = (pNext + 1) % pn
        pX[i] = x; pY[i] = y; pVX[i] = vx; pVY[i] = vy; pLife[i] = life; pMax[i] = life; pCol[i] = col
    }

    internal fun ring(x: Float, y: Float, dur: Float, radius: Float, col: Int) {
        val i = rNext; rNext = (rNext + 1) % rn
        rX[i] = x; rY[i] = y; rT[i] = 0f; rDur[i] = dur; rRad[i] = radius; rCol[i] = col
    }

    /** A message floating up from a point in the world. */
    internal fun say(text: String, x: Float, y: Float, col: Int) {
        msg = text; msgX = x; msgY = y; msgT = 0f; msgCol = col; msgUi = false
    }

    /** A message in a strip just above the bottom sheet. */
    internal fun sayUi(text: String) {
        msg = text; msgT = 0f; msgCol = Palette.INK; msgUi = true
    }

    // ---------------------------------------------------------------- update

    internal fun update(dt: Float) {
        time += dt
        modeT += dt
        sheetT += dt
        if (selT < 1f) selT = min(1f, selT + dt / 0.22f)
        updateGlide(dt)
        // The title's backdrop drifts from hole to hole.
        if (mode == Mode.TITLE && sheet == Sheet.NONE && fadeT < 0f && glideT < 0f && !reduceMotion && modeT > titlePanAt) {
            titlePanAt = modeT + TITLE_PAN_S
            val k = (holeIndex + 1) % n
            if (terrains[k].isDone) glideTo(false) { loadHole(k, backdrop = true) }
        }
        if (fadeT >= 0f) {
            fadeT += dt
            if (!fadeDone && fadeT >= FADE) { fadeDone = true; fadeAction?.invoke(); fadeAction = null }
            if (fadeT >= 2 * FADE) fadeT = -1f
        }
        val live = mode == Mode.PLAY && sheet == Sheet.NONE
        if (live && fadeT < 0f && glideT < 0f) holeT += dt
        if (flyover && holeT > 1.6f) flyover = false

        // Hold still to peek at the green.
        if (aiming && isLong() && !aimMoved && !peeking && power <= 0f && time - aimStartT > PEEK_HOLD) {
            peeking = true
            haptics.tap(this)
        }
        // Practice: long-press the pause chip to restart the hole at once.
        if (pressedId == B_PAUSE && !courseRun && mode == Mode.PLAY && time - pressStartT > RESTART_HOLD) {
            pressedId = NONE
            haptics.cup(this, false)
            val i = holeIndex
            fadeTo { loadHole(i); setMode(Mode.PLAY) }
        }
        // Long-press the logo to show frame times.
        if (logoDownT >= 0f && time - logoDownT > 1f) {
            logoDownT = -1f
            progress.debugOverlay = !progress.debugOverlay
            haptics.tap(this)
        }

        if (!physics.moving) { fastForward = false; slowMo = false }
        // A shot about to make an ace or eagle plays its last stretch slowly. The physics runs
        // exactly the same steps; only the clock slows.
        if (live && physics.moving && !slowChecked && hypot(physics.x - hole.cupX, physics.y - hole.cupY) < SLOW_R) {
            slowChecked = true
            if (!reduceMotion && (strokes == 1 || strokes <= hole.par - 2)) {
                scratch.copyFrom(physics) // copies the hole state too, so the lookahead smashes nothing real
                scratch.settle()
                slowMo = scratch.inCup
            }
        }
        if (live && physics.moving) {
            val speedUp = if (fastForward) FAST_FORWARD else if (slowMo) SLOW_MO else 1f
            acc += dt * speedUp
            var steps = 0
            while (acc >= Physics.STEP && steps < MAX_STEPS_PER_FRAME * speedUp) {
                physics.step(this)
                rollDist += physics.speed * Physics.STEP
                acc -= Physics.STEP
                steps++
                if (!physics.moving) { acc = 0f; shotOver(); break }
            }
            if (physics.moving && physics.speed > 90f && time - tLast > 1f / 50f) {
                tX[tNext] = physics.x; tY[tNext] = physics.y - physics.z; tAt[tNext] = time
                tNext = (tNext + 1) % tn; tLast = time
            }
        } else acc = 0f

        if (sinkT >= 0f) {
            sinkT += dt
            if (sinkT > SINK_S && mode == Mode.PLAY) setMode(Mode.CARD)
        }
        if (mode == Mode.CARD) {
            cardT += dt
            // The result word lands like a rubber stamp.
            if (!stamped && cardT >= STAMP_S) {
                stamped = true
                stampT = 0f
                sfx.stamp()
                haptics.wall(this, 180f)
            }
        }
        if (stampT >= 0f) { stampT += dt; if (stampT > 1.2f) stampT = -1f }
        if (celebrateT >= 0f) { celebrateT += dt; if (celebrateT > 2.6f) celebrateT = -1f }
        if (punchT >= 0f) { punchT += dt; if (punchT > PUNCH_S) punchT = -1f }
        if (tideT >= 0f) { tideT += dt; if (tideT > TIDE_S) tideT = -1f }
        if (bumpT >= 0f) { bumpT += dt; if (bumpT > 0.3f) bumpT = -1f }
        spriteFx.update(dt)
        if (popT >= 0f) { popT += dt; if (popT > 0.5f) popT = -1f }
        if (teeterT >= 0f) { teeterT += dt; if (teeterT > 0.6f) teeterT = -1f }
        if (shakeT >= 0f) { shakeT += dt; if (shakeT > 0.12f) shakeT = -1f }
        if (msg != null) { msgT += dt; if (msgT > 1.4f) msg = null }

        val damp = exp(-3.5f * dt)
        for (i in 0 until pn) if (pLife[i] > 0f) {
            pLife[i] -= dt
            pX[i] += pVX[i] * dt; pY[i] += pVY[i] * dt
            pVX[i] *= damp; pVY[i] *= damp
        }
        for (i in 0 until rn) if (rT[i] >= 0f) { rT[i] += dt; if (rT[i] > rDur[i]) rT[i] = -1f }

        // Ambience: a gull now and then, and distant surf.
        gullNext -= dt
        if (gullT < 0f && gullNext <= 0f) {
            gullT = 0f; gullDir = if (rnd.nextBoolean()) 1f else -1f
            gullY = camY + lh * (0.2f + rnd.nextFloat() * 0.6f)
        }
        if (gullT >= 0f) {
            gullT += dt
            if (gullT * GULL_SPEED > lw + 40) { gullT = -1f; gullNext = 9f + rnd.nextFloat() * 10f }
        }
        surfNext -= dt
        if (surfNext <= 0f) {
            sfx.surf(rnd.nextFloat() * 1.6f - 0.8f)
            surfNext = 5f + rnd.nextFloat() * 4f
        }

        updateCamera(dt)
        updateGestureExclusion()
    }

    internal val playTop get() = insetTop + HUD_H
    internal val playBottom get() = lh - insetBottom - 2

    internal fun updateCamera(dt: Float) {
        val playH = playBottom - playTop
        var focusX = physics.x
        var focusY = physics.y
        if (aiming && power > 0f) {
            val len = max(1f, hypot(downX - curX, downY - curY))
            focusX += (downX - curX) / len * power * 40f
            focusY += (downY - curY) / len * power * 40f
        }
        if (peeking || (flyover && mode == Mode.PLAY)) { focusX = hole.cupX; focusY = hole.cupY }
        val tx = if (hole.pxW <= lw) -((lw - hole.pxW) / 2).toFloat()
        else (focusX - lw / 2f).coerceIn(0f, (hole.pxW - lw).toFloat())
        val ty = if (hole.pxH <= playH) -(playTop + (playH - hole.pxH) / 2).toFloat()
        else (focusY - playTop - playH / 2f).coerceIn(-playTop.toFloat(), (hole.pxH - playBottom).toFloat())
        if (camSnap) { camX = tx; camY = ty; camSnap = false }
        cameraMoving = abs(tx - camX) > 0.05f || abs(ty - camY) > 0.05f
        val k = 1f - exp(-(if (peeking) 6f else 3.6f) * dt)
        camX += (tx - camX) * k
        camY += (ty - camY) * k
        if (abs(tx - camX) < 0.02f) camX = tx
        if (abs(ty - camY) < 0.02f) camY = ty
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        val wb = world ?: return
        val ub = ui ?: return
        if (!ready) {
            // The first map is still being built off the main thread: a calm splash meanwhile.
            if (terrains[0].isDone) {
                loadHole(0)
                ready = true
                startupMs = SystemClock.uptimeMillis() - createdAt
                android.util.Log.i("GolfAlone", "first frame after $startupMs ms")
            } else {
                canvas.drawColor(Palette.DEEP)
                postInvalidateOnAnimation()
                return
            }
        }
        val t0 = System.nanoTime()
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastMs == 0L) 0L else now - lastMs
        val dt = (dtMs / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        // Only frames drawn back to back count towards smoothness; idle gaps are on purpose.
        if (dtMs > 0 && lastWasActive) {
            frameMs[frameNext] = dtMs.toFloat(); frameNext = (frameNext + 1) % frameMs.size
            if (dtMs > worstFrameMs) worstFrameMs = dtMs.toFloat()
        }
        update(dt)

        val vx = camX + glideOffsetX
        val vy = camY + glideOffset
        val ix = floor(vx).toInt()
        val iy = floor(vy).toInt()
        drawWorld(ix, iy)
        if (uiNeedsRedraw()) drawUi()

        val shake = if (shakeT >= 0f) (if ((shakeT * 60f).toInt() % 2 == 0) scale else -scale) else 0
        val punch = if (punchT >= 0f) ((1f - punchT / PUNCH_S) * 3f).roundToInt() * scale else 0
        val sx = (offX - (vx - ix) * scale).roundToInt().toFloat() + shake
        val sy = (offY - (vy - iy) * scale).roundToInt().toFloat() + punch
        src.set(0, 0, wb.width, wb.height)
        dst.set(sx, sy, sx + wb.width * scale, sy + wb.height * scale)
        canvas.drawColor(Palette.DEEP)
        canvas.drawBitmap(wb, src, dst, blit)
        src.set(0, 0, ub.width, ub.height)
        dst.set(offX, offY, offX + ub.width * scale, offY + ub.height * scale)
        canvas.drawBitmap(ub, src, dst, blit)
        val drawn = (System.nanoTime() - t0) / 1e6f
        drawMs[drawNext] = drawn
        drawNext = (drawNext + 1) % drawMs.size
        if (drawn > worstDrawMs) worstDrawMs = drawn

        if (isAttachedToWindow && windowVisibility == VISIBLE) {
            val wait = RedrawPolicy.intervalMs(redrawState())
            lastWasActive = wait == 0L
            if (wait == 0L) postInvalidateOnAnimation() else postInvalidateDelayed(wait)
        }
    }

    internal var lastWasActive = true

    /** What's moving right now, for [RedrawPolicy]. */
    internal fun redrawState(): RedrawPolicy.State {
        var effects = msg != null || popT >= 0f || shakeT >= 0f || teeterT >= 0f || peeking || celebrateT >= 0f ||
            punchT >= 0f || spriteFx.active || ambient.busy(time) || tideT >= 0f || bumpT >= 0f
        if (!effects) for (i in 0 until pn) if (pLife[i] > 0f) { effects = true; break }
        if (!effects) for (i in 0 until rn) if (rT[i] >= 0f) { effects = true; break }
        return RedrawPolicy.State(
            touching = aiming || pressedId != NONE || !swipeX.isNaN() || logoDownT >= 0f || tapAnywhere || tapOutside,
            ballMoving = physics.moving,
            transitioning = fadeT >= 0f || glideT >= 0f || sheetT < 0.35f || modeT < 0.7f || selT < 1f || flyover || sinkT >= 0f ||
                (mode == Mode.CARD && cardT < 0.7f) || (mode == Mode.PLAY && strokes == 0 && holeT < 2.7f) || stampT >= 0f,
            effects = effects,
            cameraMoving = cameraMoving,
        )
    }

    /** A redraw the moment a finger lands, even mid idle wait. */
    internal fun wake() {
        if (!lastWasActive) { lastWasActive = true; postInvalidateOnAnimation() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastMs = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- UI layer

    /**
     * The UI layer is redrawn (and re-uploaded) only when something it shows could have
     * changed: input, a transition, an animated hint or message, or a change of state.
     */
    internal fun uiNeedsRedraw(): Boolean {
        val key = (mode.ordinal.toLong() shl 56) xor (sheet.ordinal.toLong() shl 52) xor (strokes.toLong() shl 40) xor
            (holeIndex.toLong() shl 32) xor (scores.sum().toLong() shl 16) xor (pressedId.toLong() and 0xffff) xor (bookTab.toLong() shl 48) xor
            (if (physics.moving) 1L shl 60 else 0L) xor (if (sinkT >= 0f) 1L shl 61 else 0L)
        val animating = redrawState().let { it.touching || it.transitioning || it.effects } || progress.debugOverlay ||
            (mode == Mode.PLAY && playHintShowing()) || (mode == Mode.TITLE)
        if (!animating && key == uiDrawnKey && uiVersion == uiDrawnVersion) return false
        uiDrawnKey = key
        uiDrawnVersion = uiVersion
        return true
    }

    internal fun drawUi() {
        val c = uc
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        btnCount = 0
        sheetRect.setEmpty()
        when (mode) {
            Mode.TITLE -> drawTitle(c)
            Mode.SELECT -> drawSelect(c)
            Mode.PLAY -> { drawHud(c); drawPlayUi(c) }
            Mode.CARD -> { drawHud(c); if (glideT < 0f) drawCard(c) }
            Mode.FINAL -> drawFinal(c)
        }
        when (sheet) {
            Sheet.PAUSE -> drawPause(c)
            Sheet.SETTINGS -> drawSettings(c)
            Sheet.BOOK -> drawBook(c)
            Sheet.NONE -> {}
        }
        val m = msg
        if (m != null && msgUi) {
            val a = if (msgT < 1f) 255 else (255 * (1f - (msgT - 1f) / 0.4f)).toInt()
            val y = (if (sheetRect.isEmpty) safeBottom - 40 else sheetRect.top - 16)
            rect(c, 0, y - 4, lw, 15, Palette.alpha(Palette.INK_SHADOW, 200 * a / 255))
            bigText(c, m, lw / 2, y, Palette.alpha(msgCol, a))
        }
        if (progress.debugOverlay) drawFrameTimes(c)
        if (fadeT >= 0f) {
            val l = if (fadeT < FADE) fadeT / FADE else 1f - (fadeT - FADE) / FADE
            Pix.dissolve(c, lw, lh, l)
        }
    }

    // Text helpers: big face for words, small face for dense grids.
    internal fun bigText(c: Canvas, s: String, cx: Int, y: Int, col: Int, scale: Int = 1, shadow: Int = 0) =
        PixelFont.drawCentered(c, s, cx, y, col, scale, shadow, Face.BIG)

    internal fun smallText(c: Canvas, s: String, cx: Int, y: Int, col: Int) =
        PixelFont.drawCentered(c, s, cx, y, col, 1, 0, Face.SMALL)

    internal fun addButton(id: Int, x: Int, y: Int, w: Int, h: Int, pad: Int = 2) {
        if (btnCount >= maxButtons) return
        btnIds[btnCount] = id
        btnHit[btnCount].set(x - pad, y - pad, x + w + pad, y + h + pad)
        btnCount++
    }

    internal fun isPressed(id: Int): Boolean {
        if (pressedId != id) return false
        for (i in 0 until btnCount) if (btnIds[i] == id) return btnHit[i].contains(pressX, pressY)
        return true
    }

    /** A driftwood plank (primary) or paper tab (secondary) with a label that must fit. */
    internal fun button(c: Canvas, id: Int, x: Int, y: Int, w: Int, h: Int, text: String, primary: Boolean, on: Boolean = true) {
        addButton(id, x, y, w, h)
        val down = isPressed(id)
        val face = if (PixelFont.width(text, 1, Face.BIG) <= w - 8) Face.BIG else Face.SMALL
        if (PixelFont.width(text, 1, face) > w - 6) textOverflows += text
        val th = face.h
        val oy = if (down) 2 else 0
        val ty = y + oy + (h - 2 - th) / 2
        if (primary) {
            Pix.plank(c, x, y, w, h, down, id)
            PixelFont.drawCentered(c, text, x + w / 2, ty, Palette.PAPER[3], 1, Palette.WOOD_DARKER, face)
        } else {
            Pix.tab(c, x, y, w, h, down, on)
            PixelFont.drawCentered(c, text, x + w / 2, ty, if (on) Palette.PAPER_INK else Palette.PAPER_INK_DIM, 1, 0, face)
        }
    }

    /** An icon-only button: a paper tab with a one-colour sprite on it. */
    internal fun iconButton(c: Canvas, id: Int, x: Int, y: Int, w: Int, h: Int, icon: com.theprobyte.golfalone.render.Sprite, enabled: Boolean = true) {
        addButton(id, x, y, w, h)
        val down = isPressed(id)
        Pix.tab(c, x, y, w, h, down, enabled)
        val oy = if (down) 2 else 0
        Sprites.icon(c, icon, x + (w - icon.w) / 2, y + oy + (h - 2 - icon.h) / 2, if (enabled) Palette.PAPER_INK else Palette.PAPER_INK_DIM)
    }

    /** A bottom sheet of paper sliding up to height [h]; returns its top edge. */
    internal fun bottomSheet(c: Canvas, h: Int, t: Float): Int {
        val k = if (reduceMotion) 1f else easeOut(min(1f, t / 0.25f))
        val top = lh - h + ((1f - k) * h).toInt()
        Pix.paper(c, 0, top, lw, lh - top + 4)
        sheetRect.set(0, top, lw, lh)
        return top
    }

    internal fun easeOut(k: Float) = 1f - (1f - k) * (1f - k) * (1f - k)

    internal fun easeOutBack(k: Float): Float =
        if (reduceMotion) 1f else 1f + 2.2f * (k - 1f) * (k - 1f) * (k - 1f) + 1.2f * (k - 1f) * (k - 1f)

    /** Rows of buttons laid from the safe bottom upwards. */
    internal fun rowY(fromBottom: Int): Int {
        var y = safeBottom
        for (i in 0..fromBottom) y -= if (i == 0) PRIMARY_H else SECONDARY_H + GAP
        return y
    }

    // HUD strings, rebuilt only when the hole or the stroke count changes.
    internal var hudHole = -1
    internal var hudStrokes = -1
    internal var hudHoleText = ""
    internal var hudParText = ""
    internal var hudShotsText = ""


    /** In hole select, the preview takes horizontal swipes from the edges too (Android allows 200 dp). */
    internal fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29 || width == 0) return
        val want = mode == Mode.SELECT && sheet == Sheet.NONE
        if (want == excluding) return
        excluding = want
        systemGestureExclusionRects = if (want) {
            val cy = height / 2
            val half = (100 * density).toInt()
            listOf(Rect(0, cy - half, width, cy + half))
        } else emptyList()
    }

    internal var excluding = false

    internal var postcardThumbs = arrayOfNulls<Bitmap>(n)
    internal var postcardBox = 0L

    internal val sliderX0 = IntArray(2)
    internal val sliderX1 = IntArray(2)

    // Overlay text, refreshed twice a second so the overlay doesn't measure itself.
    internal var overlayAt = -1f
    internal var overlay1 = ""
    internal var overlay2 = ""
    internal var overlay3 = ""

    /** Android's own frame timings (from MainActivity's FrameMetrics listener), in ms. */
    @Volatile internal var metricTotal = 0f
    @Volatile internal var metricSync = 0f
    @Volatile internal var metricGpu = -1f

    fun onFrameMetrics(totalMs: Float, syncMs: Float, gpuMs: Float) {
        metricTotal = totalMs; metricSync = syncMs; metricGpu = gpuMs
    }

    internal val sortTmp = FloatArray(120)

    // ---------------------------------------------------------------- tests

    internal val ballX get() = physics.x
    internal val ballY get() = physics.y
    internal val ballMoving get() = physics.moving
    internal val ballAirborne get() = physics.airborne
    internal val fastForwarding get() = fastForward
    internal val ballInCup get() = physics.inCup
    internal val strokesForTest get() = strokes
    internal val scoresForTest get() = scores.copyOf()
    internal val busy get() = fadeT >= 0f || flyover || glideT >= 0f
    internal val gliding get() = glideT >= 0f
    /** Each glide's swap: the last old view's top, the old map's height, the first new view's top, the screen height. */
    internal val glideSwaps = ArrayList<FloatArray>()
    internal val sheetForTest get() = sheet
    internal val bookTabForTest get() = bookTab
    internal val slowMotion get() = slowMo
    internal val holeStateForTest get() = holeState
    internal val courseForTest get() = course
    internal val fading get() = fadeT >= 0f
    internal val isPaused get() = sheet == Sheet.PAUSE
    internal val isPeeking get() = peeking
    internal val excludingGestures get() = excluding
    internal val debugOverlayOn get() = progress.debugOverlay
    internal val selectedHole get() = sel
    internal val messageForTest get() = msg
    internal val dragRangePx get() = cancelPx to maxDragPx
    internal val logicalSize get() = Triple(lw, lh, scale)

    /** Screen centre of the button [id] as last drawn, or null if it isn't showing. */
    internal fun buttonCenter(id: Int): Pair<Float, Float>? {
        for (i in 0 until btnCount) if (btnIds[i] == id) {
            val r = btnHit[i]
            return (offX + r.exactCenterX() * scale) to (offY + r.exactCenterY() * scale)
        }
        return null
    }

    /** Height in dp and top edge (fraction of the screen) of button [id], for the reach checks. */
    internal fun buttonMetrics(id: Int): Pair<Float, Float>? {
        for (i in 0 until btnCount) if (btnIds[i] == id) {
            val r = btnHit[i]
            return (r.height() * scale / density) to (r.top / lh.toFloat())
        }
        return null
    }

    companion object {
        const val LOGICAL_W = 180
        const val HUD_H = 14
        const val GULL_SPEED = 26f
        const val FADE = 0.2f
        const val WATER_FPS = 6f
        internal val TEE_MARKS = intArrayOf(-7, 6)
        /** Full-power pull as a share of the screen width: short, normal, long. */
        internal val DRAG_LENGTHS = floatArrayOf(0.30f, 0.38f, 0.48f)
        internal val PULL_NAMES = arrayOf("SHORT", "NORMAL", "LONG")
        const val FAST_FORWARD = 3f
        /** How soon a hole card takes input. */
        const val CARD_READY = 0.25f
        const val LONG_SCROLL = 64
        const val SINK_S = 0.6f
        const val PEEK_HOLD = 0.35f
        const val MAX_STEPS_PER_FRAME = Physics.HZ / 8

        /** Button heights in logical px: 26 ≈ 60 dp, 22 ≈ 50 dp on a 1080-wide Pixel. */
        const val PRIMARY_H = 26
        const val SECONDARY_H = 22
        const val GAP = 4
        const val CHIP = 22

        const val NONE = -1
        const val B_PLAY = 1
        const val B_HOLES = 2
        const val B_BACK = 3
        const val B_SOUND = 4
        const val B_BUZZ = 5
        const val B_PAUSE = 6
        const val B_RESUME = 7
        const val B_RESTART = 8
        const val B_QUIT = 9
        const val B_NEXT = 10
        const val B_RETRY = 11
        const val B_SELECT = 12
        const val B_MENU = 13
        const val B_AGAIN = 14
        const val B_SETTINGS = 16
        const val B_DONE = 17
        const val B_LEFT = 18
        const val B_MOTION = 19
        const val B_NEW = 20
        const val B_PREV_HOLE = 21
        const val B_NEXT_HOLE = 22
        const val B_PLAY_HOLE = 23
        const val B_VOL_FX = 24
        const val B_VOL_SURF = 25
        const val B_STATUS = 26
        const val B_PULL = 27
        const val B_BOOK = 28
        const val B_TAB_STATS = 29
        const val B_TAB_CARDS = 30
        const val B_PREV_COURSE = 31
        const val B_NEXT_COURSE = 32
        /** Each half of the glide between holes (s). */
        const val GLIDE_S = 0.4f
        /** The title's backdrop moves to the next hole this often (s). */
        const val TITLE_PAN_S = 8f
        /** A great shot slows down this close to the cup (px), to this share of real time. */
        const val SLOW_R = 36f
        const val SLOW_MO = 0.35f
        const val PUNCH_S = 0.3f
        /** The result word lands this long after the card opens (s). */
        const val STAMP_S = 0.18f
        /** A ball stopping this close to the cup (px) is a near miss. */
        const val SO_CLOSE = 7f
        /** How long the map takes to crossfade when the tide turns (s). */
        const val TIDE_S = 0.8f
        /** Practice: holding the pause chip this long restarts the hole. */
        const val RESTART_HOLD = 0.6f
    }
}

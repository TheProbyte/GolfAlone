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
import com.theprobyte.golfalone.game.Holes
import com.theprobyte.golfalone.game.Physics
import com.theprobyte.golfalone.game.PhysicsEvents
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.game.Tile
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
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
    private val progress: Progress,
    private val sfx: Sfx,
    private val haptics: Haptics,
) : View(context), PhysicsEvents {

    enum class Mode { TITLE, SELECT, PLAY, CARD, FINAL }
    enum class Sheet { NONE, PAUSE, SETTINGS }

    var mode = Mode.TITLE
        private set
    var sheet = Sheet.NONE
        private set
    private var sheetReturn = Sheet.NONE
    private var sheetT = 0f

    // ---------------------------------------------------------------- course

    val course: Course = Holes.COVE
    private val n = course.holes.size
    private val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "terrain").apply { isDaemon = true } }
    private val terrains: List<Future<Terrain>> = course.holes.map { h -> builder.submit<Terrain> { Terrain(h) } }
    private val thumbs = arrayOfNulls<Bitmap>(n)
    private var thumbBox = 0L

    var holeIndex = 0
        private set
    var hole: Hole = course.holes[0]
        private set
    private var physics = Physics(hole)
    private var terrain: Terrain = terrains[0].get()
    private var slopeTiles = IntArray(0)

    /** A full round (scores count) rather than practising one hole. */
    var courseRun = false
        private set
    private val scores = IntArray(n)
    private var courseNewBest = false
    private val flownOver = BooleanArray(n)

    /** The hole shown in the select carousel, and its slide animation. */
    private var sel = 0
    private var selT = 1f
    private var selDir = 0

    // ---------------------------------------------------------------- buffers

    private var scale = 1
    private var lw = 0
    private var lh = 0
    private var offX = 0f
    private var offY = 0f
    private var world: Bitmap? = null
    private var ui: Bitmap? = null
    private val wc = Canvas()
    private val uc = Canvas()
    private val blit = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val dim = Paint().apply { isFilterBitmap = false; colorFilter = android.graphics.PorterDuffColorFilter(0xB0101828.toInt(), PorterDuff.Mode.SRC_ATOP) }
    private val src = Rect()
    private val dst = RectF()
    private val density = resources.displayMetrics.density
    private val clouds = Array(3) { Sprites.cloudShadow(it + 3) }

    // Insets (screen px)
    private var insetTopPx = 0
    private var insetBottomPx = 0
    private var gestLeft = 0
    private var gestRight = 0
    private var gestBottom = 0
    private val insetTop get() = ceil(insetTopPx / scale.toFloat()).toInt()
    private val insetBottom get() = ceil(insetBottomPx / scale.toFloat()).toInt()
    /** Lowest y a button may reach: above the nav bar and the Home-swipe zone. */
    private val safeBottom get() = lh - max(insetBottom, ceil(gestBottom / scale.toFloat()).toInt()) - 6

    // ---------------------------------------------------------------- state

    private var camX = 0f
    private var camY = 0f
    private var camSnap = true
    private var lastMs = 0L
    private var time = 0f
    private var acc = 0f

    private var strokes = 0
    private var sinkT = -1f
    private var cardT = 0f
    private var modeT = 0f
    private var holeT = 0f
    private var newBest = false
    private var lastWallHaptic = 0f
    private var popT = -1f
    private var shakeT = -1f
    private var rollDist = 0f
    private var teeterT = -1f
    /** The player tapped while the ball rolled: run the roll at [FAST_FORWARD]×. */
    private var fastForward = false
    private var flyover = false

    private var fadeT = -1f
    private var fadeAction: (() -> Unit)? = null
    private var fadeDone = false

    private val reduceMotion get() = progress.reduceMotion

    // Aim and peek
    private var aiming = false
    private var aimPointer = -1
    private var aimStartT = 0f
    private var aimMoved = false
    private var peeking = false
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var power = 0f
    private var tickStep = 0
    private val cancelPx get() = 14f * density
    private val slopPx get() = 8f * density
    private val maxDragPx get() = 0.38f * width

    // Other gestures
    private var swipeX = Float.NaN
    private var swipeY = 0f
    private var tapAnywhere = false
    private var tapOutside = false
    private var logoDownT = -1f

    // Effects
    private val rnd = Random(7)
    private val pn = 160
    private val pX = FloatArray(pn); private val pY = FloatArray(pn)
    private val pVX = FloatArray(pn); private val pVY = FloatArray(pn)
    private val pLife = FloatArray(pn); private val pMax = FloatArray(pn)
    private val pCol = IntArray(pn)
    private var pNext = 0

    private val rn = 8
    private val rX = FloatArray(rn); private val rY = FloatArray(rn)
    private val rT = FloatArray(rn) { -1f }; private val rDur = FloatArray(rn)
    private val rRad = FloatArray(rn); private val rCol = IntArray(rn)
    private var rNext = 0

    private val tn = 10
    private val tX = FloatArray(tn); private val tY = FloatArray(tn); private val tAt = FloatArray(tn) { -9f }
    private var tNext = 0
    private var tLast = 0f

    private var msg: String? = null
    private var msgX = 0f
    private var msgY = 0f
    private var msgT = 0f
    private var msgCol = Palette.INK
    private var msgUi = false

    private var gullT = -1f
    private var gullY = 0f
    private var gullDir = 1f
    private var gullNext = 4f
    private var surfNext = 1.5f

    // Frame times for the debug readout.
    private val frameMs = FloatArray(120)
    private var frameNext = 0
    /** How long each frame took to update and draw (ms), separate from the time between frames. */
    private val drawMs = FloatArray(120)
    private var drawNext = 0

    // ---------------------------------------------------------------- buttons

    private val maxButtons = 24
    private val btnIds = IntArray(maxButtons)
    private val btnHit = Array(maxButtons) { Rect() }
    private var btnCount = 0
    private var pressedId = NONE
    private var pressX = 0
    private var pressY = 0
    private val sheetRect = Rect()

    /** Labels that didn't fit their button (checked by the UI test). */
    internal val textOverflows = LinkedHashSet<String>()

    init {
        isFocusable = true
        setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
        loadHole(0)
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets) {
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

    private fun loadHole(i: Int) {
        holeIndex = i
        hole = course.holes[i]
        physics = Physics(hole)
        terrain = terrains[i].get()
        for (k in 0 until n) if (k != i && k != i + 1 && terrains[k].isDone) terrains[k].get().releaseWater()
        if (i + 1 < n) builder.submit { terrains[i + 1].get().prepareWater() }
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
        for (k in 0 until pn) pLife[k] = 0f
        for (k in 0 until rn) rT[k] = -1f
        for (k in 0 until tn) tAt[k] = -9f
        msg = null
        // A long hole opens on the flag the first time you see it, then glides back to the tee.
        flyover = lh > 0 && wantsFlyover()
        if (flyover) flownOver[i] = true
        camSnap = true
    }

    private fun wantsFlyover() = isLong() && !flownOver[holeIndex] && !reduceMotion

    private fun fitsVertically() = hole.pxH <= playBottom - playTop

    /** The green is out of sight from the tee: worth a flyover, and a peek hint. */
    private fun isLong() = hole.pxH - (playBottom - playTop) > LONG_SCROLL

    private fun setMode(m: Mode) {
        mode = m
        modeT = 0f
        pressedId = NONE
        sheet = Sheet.NONE
        tapAnywhere = false
        if (m == Mode.CARD) cardT = 0f
        if (m != Mode.PLAY) flyover = false
        keepScreenOn = m == Mode.PLAY
    }

    /** Dissolves out, runs [action], dissolves back in. Input waits until it's done. */
    private fun fadeTo(action: () -> Unit) {
        if (fadeT >= 0f) return
        fadeT = 0f
        fadeAction = action
        fadeDone = false
        sfx.whoosh()
    }

    private fun openSheet(s: Sheet) {
        sheetReturn = if (s == Sheet.SETTINGS) sheet else Sheet.NONE
        sheet = s
        sheetT = 0f
        aiming = false
        peeking = false
    }

    private fun closeSheet() {
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

    private fun continueRound() = fadeTo {
        courseRun = true
        val saved = progress.runScores(n)
        for (i in 0 until n) scores[i] = saved[i]
        courseNewBest = false
        loadHole(progress.runHole.coerceIn(0, n - 1))
        // Pick the hole up where it was left: strokes and the ball's lie.
        val held = progress.runStrokes
        if (held > 0) {
            strokes = held
            physics.placeAt(progress.runBallX, progress.runBallY)
            flyover = false
            camSnap = true
        }
        setMode(Mode.PLAY)
    }

    private fun practise(i: Int) = fadeTo {
        courseRun = false
        loadHole(i)
        setMode(Mode.PLAY)
    }

    private fun toTitle() = fadeTo {
        physics.reset()
        aiming = false
        setMode(Mode.TITLE)
    }

    private fun toSelect() = fadeTo {
        aiming = false
        sel = holeIndex
        selT = 1f
        setMode(Mode.SELECT)
    }

    private val hasSavedRound get() = progress.runHole in 0 until n && (progress.runHole > 0 || progress.runStrokes > 0)

    /** System back: close a sheet, pause a hole, or step back towards the title. */
    fun onBack(): Boolean {
        if (fadeT >= 0f) return true
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
    private fun saveHoleInProgress() {
        if (!courseRun || mode != Mode.PLAY || sinkT >= 0f) return
        if (physics.moving) {
            // Play the shot out first, so the ball is saved where it really stops. It runs
            // through the normal events: a splash costs its stroke, and a holed ball goes down
            // the usual cup path (score, saved round, and the card when the game comes back).
            physics.settle(this)
            if (physics.inCup) return
        }
        progress.saveRun(holeIndex, scores, strokes, physics.restX, physics.restY)
    }

    /** The app went to the background: pause any hole in progress and save the round. */
    fun onHostPause() {
        saveHoleInProgress()
        if (mode == Mode.PLAY && sinkT < 0f && sheet == Sheet.NONE) openSheet(Sheet.PAUSE)
        aiming = false
        peeking = false
        pressedId = NONE
    }

    private fun toPar(): Int {
        var s = 0
        for (i in 0 until n) if (scores[i] > 0) s += scores[i] - course.holes[i].par
        return s
    }

    private fun parText(d: Int) = when { d == 0 -> "E"; d > 0 -> "+$d"; else -> "$d" }

    // ---------------------------------------------------------------- input

    private fun inGestureZone(x: Float, y: Float) =
        x < gestLeft || x > width - gestRight || y > height - gestBottom

    private fun toLX(sx: Float) = (sx - offX) / scale
    private fun toLY(sy: Float) = (sy - offY) / scale

    private fun hitButton(lx: Int, ly: Int): Int {
        for (i in btnCount - 1 downTo 0) if (btnHit[i].contains(lx, ly)) return btnIds[i]
        return NONE
    }

    private val canAim get() = mode == Mode.PLAY && sheet == Sheet.NONE && !physics.moving && !physics.inCup && !flyover

    private fun startAim(x: Float, y: Float, pointer: Int) {
        aiming = true
        aimPointer = pointer
        aimStartT = time
        aimMoved = false
        peeking = false
        downX = x; downY = y; curX = x; curY = y
        power = 0f; tickStep = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fadeT >= 0f) return true
        val lx = floor(toLX(e.x)).toInt()
        val ly = floor(toLY(e.y)).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapAnywhere = false; tapOutside = false; swipeX = Float.NaN; logoDownT = -1f
                downX = e.x; downY = e.y
                val id = hitButton(lx, ly)
                if (id != NONE) {
                    pressedId = id; pressX = lx; pressY = ly
                    haptics.tap(this)
                } else if (sheet != Sheet.NONE) {
                    tapOutside = !sheetRect.contains(lx, ly)
                } else when (mode) {
                    Mode.PLAY -> if (flyover) flyover = false // a tap skips the flyover
                    else if (physics.moving && sheet == Sheet.NONE) fastForward = true // a tap hurries a long roll
                    else if (canAim && !inGestureZone(e.x, e.y)) startAim(e.x, e.y, e.getPointerId(0))
                    Mode.SELECT -> { swipeX = e.x; swipeY = e.y }
                    Mode.CARD -> tapAnywhere = courseRun && cardT > CARD_READY
                    Mode.TITLE -> if (ly < lh / 2) logoDownT = time
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
                if (id != NONE && hitButton(lx, ly) == id) onButton(id)
                if (!swipeX.isNaN()) {
                    val dx = e.x - swipeX
                    if (abs(dx) > 36f * density && abs(dx) > abs(e.y - swipeY) * 1.5f) moveSelection(if (dx < 0) 1 else -1)
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

    private fun finishAim(x: Float, y: Float) {
        aiming = false
        if (peeking) { peeking = false; return } // a peek never shoots
        curX = x; curY = y
        updatePower()
        if (power > 0f) shoot()
    }

    private fun moveSelection(d: Int) {
        val next = (sel + d).coerceIn(0, n - 1)
        if (next == sel) return
        sel = next
        selDir = d
        selT = 0f
        sfx.tap()
        haptics.tap(this)
    }

    private fun onButton(id: Int) {
        sfx.tap()
        when (id) {
            B_PLAY -> if (hasSavedRound) continueRound() else startCourse()
            B_NEW -> startCourse()
            B_HOLES -> toSelect()
            B_BACK -> toTitle()
            B_SETTINGS -> openSheet(Sheet.SETTINGS)
            B_DONE -> closeSheet()
            B_SOUND -> { progress.sound = !progress.sound; sfx.enabled = progress.sound; sfx.tap() }
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
                    progress.recordCourse(course.name, scores.sum())
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

    private fun nextHole() {
        val next = holeIndex + 1
        if (courseRun && next >= n) {
            fadeTo {
                courseNewBest = progress.recordCourse(course.name, scores.sum())
                progress.clearRun()
                setMode(Mode.FINAL)
            }
        } else if (next < n) {
            fadeTo { loadHole(next); setMode(Mode.PLAY) }
        } else toSelect()
    }

    private fun updatePower() {
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

    private fun shoot() {
        val dx = downX - curX
        val dy = downY - curY
        physics.shoot(dx, dy, power)
        strokes++
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
    }

    // ---------------------------------------------------------------- physics events

    private fun pan(wx: Float) = if (lw == 0) 0f else (((wx - camX) / lw) * 2f - 1f).coerceIn(-1f, 1f) * 0.8f

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
        progress.seenRampTip = true
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
    }

    override fun onTeeter(x: Float, y: Float) {
        teeterT = 0f
        haptics.lipOut(this)
        sfx.lipOut(pan(x))
        say("ON THE EDGE!", x, y - 8f, Palette.GOLD)
    }

    override fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) {
        strokes++ // penalty
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

    override fun onLipOut(x: Float, y: Float, speed: Float) {
        sfx.lipOut(pan(x))
        haptics.lipOut(this)
        say("LIP OUT!", hole.cupX, hole.cupY - 20f, Palette.GOLD)
    }

    override fun onStop(x: Float, y: Float) {
        if (hole.at(x, y) == Tile.ROUGH) repeat(4) {
            spawn(x, y + 1f, rnd.nextFloat() * 16f - 8f, -rnd.nextFloat() * 10f, 0.35f, Palette.ROUGH_LIGHT)
        }
    }

    override fun onCup(x: Float, y: Float, speed: Float) {
        sinkT = 0f
        val toParHole = strokes - hole.par
        newBest = progress.record(holeIndex, strokes)
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

    private fun spawn(x: Float, y: Float, vx: Float, vy: Float, life: Float, col: Int) {
        val i = pNext; pNext = (pNext + 1) % pn
        pX[i] = x; pY[i] = y; pVX[i] = vx; pVY[i] = vy; pLife[i] = life; pMax[i] = life; pCol[i] = col
    }

    private fun ring(x: Float, y: Float, dur: Float, radius: Float, col: Int) {
        val i = rNext; rNext = (rNext + 1) % rn
        rX[i] = x; rY[i] = y; rT[i] = 0f; rDur[i] = dur; rRad[i] = radius; rCol[i] = col
    }

    /** A message floating up from a point in the world. */
    private fun say(text: String, x: Float, y: Float, col: Int) {
        msg = text; msgX = x; msgY = y; msgT = 0f; msgCol = col; msgUi = false
    }

    /** A message in a strip just above the bottom sheet. */
    private fun sayUi(text: String) {
        msg = text; msgT = 0f; msgCol = Palette.INK; msgUi = true
    }

    // ---------------------------------------------------------------- update

    private fun update(dt: Float) {
        time += dt
        modeT += dt
        sheetT += dt
        if (selT < 1f) selT = min(1f, selT + dt / 0.22f)
        if (fadeT >= 0f) {
            fadeT += dt
            if (!fadeDone && fadeT >= FADE) { fadeDone = true; fadeAction?.invoke(); fadeAction = null }
            if (fadeT >= 2 * FADE) fadeT = -1f
        }
        val live = mode == Mode.PLAY && sheet == Sheet.NONE
        if (live && fadeT < 0f) holeT += dt
        if (flyover && holeT > 1.6f) flyover = false

        // Hold still to peek at the green.
        if (aiming && isLong() && !aimMoved && !peeking && power <= 0f && time - aimStartT > PEEK_HOLD) {
            peeking = true
            haptics.tap(this)
        }
        // Long-press the logo to show frame times.
        if (logoDownT >= 0f && time - logoDownT > 1f) {
            logoDownT = -1f
            progress.debugOverlay = !progress.debugOverlay
            haptics.tap(this)
        }

        if (!physics.moving) fastForward = false
        if (live && physics.moving) {
            val speedUp = if (fastForward) FAST_FORWARD else 1f
            acc += dt * speedUp
            var steps = 0
            while (acc >= Physics.STEP && steps < MAX_STEPS_PER_FRAME * speedUp) {
                physics.step(this)
                rollDist += physics.speed * Physics.STEP
                acc -= Physics.STEP
                steps++
                if (!physics.moving) { acc = 0f; break }
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
        if (mode == Mode.CARD) cardT += dt
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

    private val playTop get() = insetTop + HUD_H
    private val playBottom get() = lh - insetBottom - 2

    private fun updateCamera(dt: Float) {
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
        val t0 = System.nanoTime()
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastMs == 0L) 0L else now - lastMs
        val dt = (dtMs / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
        if (dtMs > 0) { frameMs[frameNext] = dtMs.toFloat(); frameNext = (frameNext + 1) % frameMs.size }
        update(dt)

        val ix = floor(camX).toInt()
        val iy = floor(camY).toInt()
        drawWorld(ix, iy)
        drawUi()

        val shake = if (shakeT >= 0f) (if ((shakeT * 60f).toInt() % 2 == 0) scale else -scale) else 0
        val sx = (offX - (camX - ix) * scale).roundToInt().toFloat() + shake
        val sy = (offY - (camY - iy) * scale).roundToInt().toFloat()
        src.set(0, 0, wb.width, wb.height)
        dst.set(sx, sy, sx + wb.width * scale, sy + wb.height * scale)
        canvas.drawColor(Palette.DEEP)
        canvas.drawBitmap(wb, src, dst, blit)
        src.set(0, 0, ub.width, ub.height)
        dst.set(offX, offY, offX + ub.width * scale, offY + ub.height * scale)
        canvas.drawBitmap(ub, src, dst, blit)
        drawMs[drawNext] = (System.nanoTime() - t0) / 1e6f
        drawNext = (drawNext + 1) % drawMs.size

        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastMs = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawWorld(ix: Int, iy: Int) {
        val c = wc
        val wf = ((time * WATER_FPS).toInt()) % Terrain.FRAMES
        // The open sea beyond the map: the same water, tiled in world coordinates so it meets
        // the map's own open water without a seam.
        val sea = Terrain.sea[wf]
        var sy = Math.floorDiv(iy, Terrain.SEA_H) * Terrain.SEA_H
        while (sy < iy + lh + 1) {
            var sx = Math.floorDiv(ix, Terrain.SEA_W) * Terrain.SEA_W
            while (sx < ix + lw + 1) {
                c.drawBitmap(sea, (sx - ix).toFloat(), (sy - iy).toFloat(), blit)
                sx += Terrain.SEA_W
            }
            sy += Terrain.SEA_H
        }
        val gx = -ix.toFloat()
        val gy = -iy.toFloat()
        c.drawBitmap(terrain.ground, gx, gy, blit)
        c.drawBitmap(terrain.water(wf), gx, gy, blit)

        // Glints drifting downhill on slopes.
        for (i in slopeTiles) {
            val t = hole.tiles[i]
            val tx = i % hole.w
            val ty = i / hole.w
            val h1 = Terrain.hash(tx, ty, 41)
            if (h1 > 0.6f) continue
            val ph = ((time * 0.9f + h1 * 7f) % 1f)
            val along = (ph * TILE).toInt()
            val across = (Terrain.hash(tx, ty, 42) * 6f).toInt() + 1
            val lx = if (t.slopeX > 0) along else if (t.slopeX < 0) TILE - 1 - along else across
            val ly = if (t.slopeY > 0) along else if (t.slopeY < 0) TILE - 1 - along else across
            val a = (sin(ph * PI.toFloat()) * 200f).toInt()
            px(c, tx * TILE + lx - ix, ty * TILE + ly - iy, Palette.alpha(Palette.SLOPE_GLINT, a))
        }

        // Tee markers.
        val tx0 = hole.teeX.toInt() - ix
        val ty0 = hole.teeY.toInt() - iy
        for (s in intArrayOf(-7, 6)) {
            rect(c, tx0 + s, ty0 - 1, 2, 2, Palette.INK)
            rect(c, tx0 + s, ty0 + 1, 2, 1, Palette.shade(Palette.FAIR_B, 0.7f))
        }

        val cx = hole.cupX.roundToInt() - ix
        val cy = hole.cupY.roundToInt() - iy
        Sprites.cup(c, cx, cy)

        // Palm shadows and trunks sit on the ground, under the ball.
        val posts = hole.posts
        var k = 0
        while (k < posts.size) {
            val bx = posts[k].roundToInt() - ix
            val by = posts[k + 1].roundToInt() - iy
            Sprites.palmShadow(c, bx, by, Sprites.palmSway(time, k))
            Sprites.palmTrunk(c, bx, by)
            k += 2
        }

        // Trail.
        for (i in 0 until tn) {
            val age = time - tAt[i]
            if (age < 0f || age > 0.2f) continue
            px(c, tX[i].roundToInt() - ix, tY[i].roundToInt() - iy, Palette.alpha(Palette.INK, (150 * (1f - age / 0.2f)).toInt()))
        }

        drawBall(c, ix, iy)
        if (mode == Mode.PLAY && aiming && power > 0f) drawAim(c, ix, iy)

        // Particles.
        for (i in 0 until pn) if (pLife[i] > 0f) {
            val f = pLife[i] / pMax[i]
            px(c, pX[i].roundToInt() - ix, pY[i].roundToInt() - iy, Palette.alpha(pCol[i], if (f > 0.4f) 255 else (255 * f / 0.4f).toInt()))
        }
        for (i in 0 until rn) if (rT[i] >= 0f) {
            val f = rT[i] / rDur[i]
            Pix.ring(c, rX[i].roundToInt() - ix + 0.5f, rY[i].roundToInt() - iy + 0.5f, 1f + f * rRad[i], Palette.alpha(rCol[i], (220 * (1f - f)).toInt()))
        }

        // Canopies over the ball; the ball shows through as a ghost.
        k = 0
        var under = false
        while (k < posts.size) {
            val bx = posts[k].roundToInt() - ix
            val by = posts[k + 1].roundToInt() - iy
            Sprites.palmCanopy(c, bx, by, Sprites.palmSway(time, k))
            if (sinkT < 0f && Sprites.underCanopy(physics.x, physics.y, posts[k], posts[k + 1])) under = true
            k += 2
        }
        if (under) Sprites.ball(c, physics.x.roundToInt() - ix, physics.y.roundToInt() - iy, 0, ghost = true, lift = physics.z.roundToInt())

        val near = hypot(physics.x - hole.cupX, physics.y - hole.cupY) < 24f && physics.moving
        val lift = if (sinkT >= 0f) min(3, (sinkT * 20f).toInt()) else 0
        Sprites.flag(c, cx, cy, ((time * (if (near) 12f else 4f)).toInt()) % 3, lift)

        // Gull and its shadow.
        if (gullT >= 0f) {
            val sx = if (gullDir > 0) -20f + gullT * GULL_SPEED else lw + 20f - gullT * GULL_SPEED
            val wx = (camX + sx).roundToInt() - ix
            val wy = (gullY + sin(gullT * 1.3f) * 4f).roundToInt() - iy
            val flap = ((gullT * 4f).toInt()) % 2 == 0
            Sprites.gull(c, wx + 7, wy + 12, flap, Palette.alpha(Palette.INK_SHADOW, 60))
            Sprites.gull(c, wx, wy, flap, Palette.INK)
        }

        // Cloud shadows drift over everything on the ground.
        for (i in clouds.indices) {
            val cw = clouds[i].width
            val span = hole.pxW + cw * 2
            val x = ((time * (3f + i) + i * 97f) % span) - cw
            val y = (hole.pxH * (0.15f + 0.33f * i)) + sin(time * 0.05f + i) * 10f
            c.drawBitmap(clouds[i], (x.roundToInt() - ix).toFloat(), (y.roundToInt() - iy).toFloat(), blit)
        }

        val m = msg
        if (m != null && !msgUi) {
            val rise = (msgT * 8f).toInt()
            val a = if (msgT < 1f) 255 else (255 * (1f - (msgT - 1f) / 0.4f)).toInt()
            PixelFont.drawCentered(c, m, msgX.roundToInt() - ix, msgY.roundToInt() - iy - rise,
                Palette.alpha(msgCol, a), 1, Palette.alpha(Palette.INK_SHADOW, a), PixelFont.Face.BIG)
        }
    }

    private fun drawBall(c: Canvas, ix: Int, iy: Int) {
        var bx = physics.x
        var by = physics.y
        if (sinkT >= 0f) {
            // Rolls to the centre, shrinks, gone.
            if (sinkT > 0.28f) return
            val k = min(1f, sinkT / 0.12f)
            bx += (hole.cupX - bx) * k; by += (hole.cupY - by) * k
            val x = bx.roundToInt() - ix
            val y = by.roundToInt() - iy
            if (sinkT < 0.16f) rect(c, x - 1, y - 1, 3, 3, Palette.BALL_SHADE) else px(c, x, y, Palette.BALL_SHADE)
            return
        }
        if (popT in 0f..0.12f) return // blink on respawn
        // A ball that just stopped on the water's edge wobbles before it settles.
        val wobble = if (teeterT >= 0f && !reduceMotion) (if ((teeterT * 14f).toInt() % 2 == 0) 1 else -1) * (if (teeterT < 0.4f) 1 else 0) else 0
        val x = bx.roundToInt() - ix + wobble
        val y = by.roundToInt() - iy
        val dir = if (physics.vx + physics.vy >= 0f) 1 else -1
        Sprites.ball(c, x, y, ((rollDist / 2.5f).toInt() * dir) and 3, lift = physics.z.roundToInt())
        // An idle ball breathes a faint ring so it's easy to find.
        if (mode == Mode.PLAY && !physics.moving && !aiming && sheet == Sheet.NONE && !flyover) {
            val ph = (time * 0.8f) % 1f
            Pix.ring(c, x + 0.5f, y + 0.5f, 3f + ph * 6f, Palette.alpha(Palette.INK, (120 * (1f - ph)).toInt()))
        }
    }

    private fun drawAim(c: Canvas, ix: Int, iy: Int) {
        val len = max(1f, hypot(downX - curX, downY - curY))
        val ux = (downX - curX) / len
        val uy = (downY - curY) / len
        val dots = 2 + (power * 11f).toInt()
        val col = if (power < 0.6f) Palette.lerp(Palette.INK, Palette.GOLD, power / 0.6f)
        else Palette.lerp(Palette.GOLD, Palette.HOT, (power - 0.6f) / 0.4f)
        val full = power >= 1f
        for (i in 0 until dots) {
            val d = 5f + i * 4f
            val wx = physics.x + ux * d
            val wy = physics.y + uy * d
            if (hole.at(wx, wy).solid) break
            if (full && ((time * 8f).toInt() + i) % 2 == 0) continue
            val x = wx.roundToInt() - ix
            val y = wy.roundToInt() - iy
            px(c, x, y + 1, Palette.alpha(Palette.INK_SHADOW, 110))
            px(c, x, y, col)
            if (i == dots - 1) { px(c, x + 1, y, col); px(c, x - 1, y, col); px(c, x, y - 1, col) }
        }
    }

    // ---------------------------------------------------------------- UI layer

    private fun drawUi() {
        val c = uc
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        btnCount = 0
        sheetRect.setEmpty()
        when (mode) {
            Mode.TITLE -> drawTitle(c)
            Mode.SELECT -> drawSelect(c)
            Mode.PLAY -> { drawHud(c); drawPlayUi(c) }
            Mode.CARD -> { drawHud(c); drawCard(c) }
            Mode.FINAL -> drawFinal(c)
        }
        when (sheet) {
            Sheet.PAUSE -> drawPause(c)
            Sheet.SETTINGS -> drawSettings(c)
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
    private fun bigText(c: Canvas, s: String, cx: Int, y: Int, col: Int, scale: Int = 1, shadow: Int = 0) =
        PixelFont.drawCentered(c, s, cx, y, col, scale, shadow, Face.BIG)

    private fun smallText(c: Canvas, s: String, cx: Int, y: Int, col: Int) =
        PixelFont.drawCentered(c, s, cx, y, col, 1, 0, Face.SMALL)

    private fun addButton(id: Int, x: Int, y: Int, w: Int, h: Int, pad: Int = 2) {
        if (btnCount >= maxButtons) return
        btnIds[btnCount] = id
        btnHit[btnCount].set(x - pad, y - pad, x + w + pad, y + h + pad)
        btnCount++
    }

    private fun isPressed(id: Int): Boolean {
        if (pressedId != id) return false
        for (i in 0 until btnCount) if (btnIds[i] == id) return btnHit[i].contains(pressX, pressY)
        return true
    }

    /** A driftwood plank (primary) or paper tab (secondary) with a label that must fit. */
    private fun button(c: Canvas, id: Int, x: Int, y: Int, w: Int, h: Int, text: String, primary: Boolean, on: Boolean = true) {
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
    private fun iconButton(c: Canvas, id: Int, x: Int, y: Int, w: Int, h: Int, icon: com.theprobyte.golfalone.render.Sprite, enabled: Boolean = true) {
        addButton(id, x, y, w, h)
        val down = isPressed(id)
        Pix.tab(c, x, y, w, h, down, enabled)
        val oy = if (down) 2 else 0
        Sprites.icon(c, icon, x + (w - icon.w) / 2, y + oy + (h - 2 - icon.h) / 2, if (enabled) Palette.PAPER_INK else Palette.PAPER_INK_DIM)
    }

    /** A bottom sheet of paper sliding up to height [h]; returns its top edge. */
    private fun bottomSheet(c: Canvas, h: Int, t: Float): Int {
        val k = if (reduceMotion) 1f else easeOut(min(1f, t / 0.25f))
        val top = lh - h + ((1f - k) * h).toInt()
        Pix.paper(c, 0, top, lw, lh - top + 4)
        sheetRect.set(0, top, lw, lh)
        return top
    }

    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k) * (1f - k)

    private fun easeOutBack(k: Float): Float =
        if (reduceMotion) 1f else 1f + 2.2f * (k - 1f) * (k - 1f) * (k - 1f) + 1.2f * (k - 1f) * (k - 1f)

    /** Rows of buttons laid from the safe bottom upwards. */
    private fun rowY(fromBottom: Int): Int {
        var y = safeBottom
        for (i in 0..fromBottom) y -= if (i == 0) PRIMARY_H else SECONDARY_H + GAP
        return y
    }

    private fun drawHud(c: Canvas) {
        val y = insetTop + 3
        rect(c, 0, 0, lw, y + 10, Palette.alpha(Palette.INK_SHADOW, 170))
        rect(c, 0, y + 10, lw, 1, Palette.alpha(Palette.INK_SHADOW, 80))
        val holeText = "HOLE ${holeIndex + 1}/$n"
        PixelFont.draw(c, holeText, 4, y, Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)
        PixelFont.draw(c, "PAR ${hole.par}", 4 + PixelFont.width(holeText, 1, Face.BIG) + 6, y, Palette.INK_DIM, 1, Palette.INK_SHADOW, Face.BIG)
        val s = "SHOTS $strokes"
        val sw = PixelFont.width(s, 1, Face.BIG)
        PixelFont.draw(c, s, lw - 4 - sw, y, if (strokes > hole.par) Palette.HOT else Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)
        if (courseRun) {
            val d = toPar()
            val t = parText(d)
            val tw = PixelFont.width(t, 1, Face.BIG) + 4
            val tx = lw - 4 - sw - 5 - tw
            rect(c, tx, y - 1, tw, 9, Palette.alpha(Palette.PANEL_EDGE, 220))
            PixelFont.draw(c, t, tx + 2, y, if (d <= 0) Palette.GOLD else Palette.INK, 1, 0, Face.BIG)
        }
    }

    private fun drawPlayUi(c: Canvas) {
        // Hole banner while the round begins.
        if (holeT < 2.6f && strokes == 0) {
            val a = when {
                holeT < 0.3f -> holeT / 0.3f
                holeT > 2.1f -> 1f - (holeT - 2.1f) / 0.5f
                else -> 1f
            }.coerceIn(0f, 1f)
            // Mid-screen, over fairway: greens sit up top and the tee at the bottom.
            val y = (playTop + playBottom) / 2 - 18
            val ai = (255 * a).toInt()
            val sh = Palette.alpha(Palette.INK_SHADOW, ai)
            rect(c, 0, y - 10, lw, 46, Palette.alpha(Palette.INK_SHADOW, (160 * a).toInt()))
            PixelFont.drawCentered(c, "${course.name} * HOLE ${holeIndex + 1}", lw / 2, y - 5, Palette.alpha(Palette.INK_DIM, ai), 1, sh, Face.BIG)
            PixelFont.drawCentered(c, hole.name, lw / 2, y + 8, Palette.alpha(Palette.INK, ai), 2, sh, Face.BIG)
            PixelFont.drawCentered(c, "PAR ${hole.par}", lw / 2, y + 25, Palette.alpha(Palette.GOLD, ai), 1, sh, Face.BIG)
        }

        // Rubber band from where you touched to your finger.
        if (aiming && !peeking) {
            val ax = toLX(downX).roundToInt()
            val ay = toLY(downY).roundToInt()
            val fx = toLX(curX)
            val fy = toLY(curY)
            val d = hypot(fx - ax, fy - ay)
            val cancel = power <= 0f
            val col = if (cancel) Palette.INK_DIM else Palette.INK
            var t = 3f
            while (t < d - 2f) {
                px(c, (ax + (fx - ax) * t / d).roundToInt(), (ay + (fy - ay) * t / d).roundToInt(), Palette.alpha(col, 120))
                t += 3f
            }
            Pix.ring(c, ax + 0.5f, ay + 0.5f, 4f, Palette.alpha(col, 170))
            rect(c, fx.roundToInt() - 1, fy.roundToInt() - 1, 3, 3, Palette.alpha(col, 210))
            if (!cancel) {
                val pct = "${(power * 100).roundToInt()}%"
                PixelFont.draw(c, pct, ax + 7, ay - 3, if (power >= 1f) Palette.HOT else Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)
            }
        }

        // The pause chip: bottom corner on the thumb's side, above the Home-swipe zone.
        if (sinkT < 0f) {
            val cw = CHIP
            val cx = if (progress.leftHanded) 6 else lw - 6 - cw
            val cy = safeBottom - cw
            addButton(B_PAUSE, cx, cy, cw, cw, pad = 3)
            val down = isPressed(B_PAUSE)
            Pix.chip(c, cx, cy, cw, cw, down)
            Sprites.icon(c, Sprites.iconPause, cx + (cw - Sprites.iconPause.w) / 2, cy + (cw - Sprites.iconPause.h) / 2, Palette.INK)
        }

        // Hints along the bottom.
        val hy = safeBottom - CHIP - 14
        val pulse = (0.6f + 0.4f * sin(time * 3f)).coerceIn(0f, 1f)
        val a = (255 * pulse).toInt()
        val sh = Palette.alpha(Palette.INK_SHADOW, a)
        when {
            fastForward -> PixelFont.drawCentered(c, ">> FAST", lw / 2, hy, Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)
            peeking -> PixelFont.drawCentered(c, "PEEKING AT THE GREEN", lw / 2, hy, Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)
            flyover && holeT > 0.4f -> PixelFont.drawCentered(c, "TAP TO SKIP", lw / 2, hy, Palette.INK_DIM, 1, Palette.INK_SHADOW, Face.BIG)
            progress.best(0) == 0 && holeIndex == 0 && strokes == 0 && !physics.moving && holeT > 1f -> {
                val text = if (aiming) (if (power > 0f) "LET GO TO SHOOT" else "PULL BACK TO AIM") else "PULL BACK ANYWHERE TO AIM"
                PixelFont.drawCentered(c, text, lw / 2, hy, Palette.alpha(Palette.INK, a), 1, sh, Face.BIG)
            }
            hole.hasRamps && !progress.seenRampTip && strokes == 0 && !aiming && holeT > 2.6f ->
                PixelFont.drawCentered(c, "ROLL UP A RAMP FAST TO JUMP", lw / 2, hy, Palette.alpha(Palette.INK, a), 1, sh, Face.BIG)
            isLong() && strokes == 0 && !aiming && !physics.moving && holeT > 2.6f ->
                PixelFont.drawCentered(c, "HOLD STILL TO PEEK", lw / 2, hy, Palette.alpha(Palette.INK, a), 1, sh, Face.BIG)
        }
    }

    private fun toggleLabel(on: Boolean) = if (on) "ON" else "OFF"

    private fun drawTitle(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 110))
        val a = (255 * min(1f, modeT / 0.5f)).toInt()
        val top = insetTop + 34
        val bob = if (reduceMotion) 0 else (sin(time * 1.2f) * 1.5f).roundToInt()
        val sh = Palette.alpha(Palette.INK_SHADOW, a)
        PixelFont.drawCentered(c, "GOLF", lw / 2, top + bob, Palette.alpha(Palette.INK, a), 5, sh, Face.BIG)
        PixelFont.drawCentered(c, "ALONE", lw / 2, top + 42 + bob, Palette.alpha(Palette.GOLD, a), 4, sh, Face.BIG)
        Sprites.flagIcon(c, lw / 2 + 56, top + 20 + bob, Palette.FLAG, 14)
        PixelFont.drawCentered(c, "A QUIET ISLAND COURSE", lw / 2, top + 76, Palette.alpha(Palette.INK, a), 1, sh, Face.BIG)

        // The sheet: course line, the main button, and a row of secondary ones.
        val h = safeBottom.let { lh - it } + PRIMARY_H + GAP + SECONDARY_H + 30
        val st = bottomSheet(c, h, modeT)
        val best = progress.courseBest(course.name)
        val line = if (best == 0) "${course.name} * PAR ${course.par}" else "BEST $best (${parText(best - course.par)}) * PAR ${course.par}"
        bigText(c, line, lw / 2, st + 9, Palette.PAPER_INK)
        val pad = 8
        val by = st + 22
        val saved = hasSavedRound
        button(c, B_PLAY, pad, by, lw - pad * 2, PRIMARY_H, if (saved) "CONTINUE HOLE ${progress.runHole + 1}" else "PLAY COURSE", true)
        val ry = by + PRIMARY_H + GAP
        if (saved) {
            val gw = 30
            val bw = (lw - pad * 2 - gw - GAP * 2) / 2
            button(c, B_NEW, pad, ry, bw, SECONDARY_H, "NEW ROUND", false)
            button(c, B_HOLES, pad + bw + GAP, ry, bw, SECONDARY_H, "PRACTISE", false)
            iconButton(c, B_SETTINGS, lw - pad - gw, ry, gw, SECONDARY_H, Sprites.iconGear)
        } else {
            val bw = (lw - pad * 2 - GAP) / 2
            button(c, B_HOLES, pad, ry, bw, SECONDARY_H, "PRACTISE", false)
            button(c, B_SETTINGS, pad + bw + GAP, ry, bw, SECONDARY_H, "SETTINGS", false)
        }
    }

    /** A hole's thumbnail sized to fit [maxW]×[maxH], cached per box size. */
    private fun thumb(i: Int, maxW: Int, maxH: Int): Bitmap? {
        val box = (maxW.toLong() shl 32) or maxH.toLong()
        if (box != thumbBox) { thumbs.fill(null); thumbBox = box }
        thumbs[i]?.let { return it }
        val f = terrains[i]
        if (!f.isDone) return null
        val t = f.get()
        val step = max(ceil(t.w / maxW.toFloat()).toInt(), ceil(t.h / maxH.toFloat()).toInt()).coerceAtLeast(1)
        return t.thumbnail(step).also { thumbs[i] = it }
    }

    /** In hole select, the preview takes horizontal swipes from the edges too (Android allows 200 dp). */
    private fun updateGestureExclusion() {
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

    private var excluding = false

    private fun drawSelect(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 190))
        PixelFont.drawCentered(c, course.name, lw / 2, insetTop + 6, Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)

        val sheetH = lh - safeBottom + PRIMARY_H + GAP + SECONDARY_H + 34
        val previewTop = insetTop + 20
        val previewBottom = lh - sheetH - 14
        val boxH = previewBottom - previewTop
        // The selected hole slides in from the side it came from.
        val k = if (reduceMotion) 1f else easeOut(selT)
        val slide = ((1f - k) * lw * 0.6f * selDir).toInt()
        val open = progress.unlocked(sel)
        // The hole's map, at full size where it fits, in a paper frame sized to it.
        val tb = thumb(sel, lw - 16, boxH - 8)
        if (tb != null) {
            val fw = tb.width + 8
            val fh = tb.height + 8
            val fx = (lw - fw) / 2 + slide
            val fy = previewTop + (boxH - fh) / 2
            Pix.paper(c, fx, fy, fw, fh, perforated = false)
            c.drawBitmap(tb, (fx + 4).toFloat(), (fy + 4).toFloat(), if (open) blit else dim)
            if (!open) {
                val lk = Sprites.iconLock
                Pix.chip(c, fx + fw / 2 - 9, fy + fh / 2 - 9, 18, 18, false)
                Sprites.icon(c, lk, fx + fw / 2 - lk.w / 2, fy + fh / 2 - lk.h / 2, Palette.INK)
            }
        }
        // Dots.
        val dy = previewBottom + 5
        for (i in 0 until n) {
            val x = lw / 2 - n * 4 + i * 8 + 2
            rect(c, x, dy, 4, 4, if (i == sel) Palette.GOLD else Palette.alpha(Palette.INK, 90))
        }

        val st = bottomSheet(c, sheetH, 1f)
        val h = course.holes[sel]
        PixelFont.drawCentered(c, "${sel + 1}. ${h.name}", lw / 2, st + 8, Palette.PAPER_INK, 1, 0, Face.BIG)
        val best = progress.best(sel)
        val info = if (!open) "PAR ${h.par} * LOCKED" else if (best == 0) "PAR ${h.par} * NOT PLAYED" else "PAR ${h.par} * BEST $best"
        PixelFont.drawCentered(c, info, lw / 2, st + 19, Palette.PAPER_INK_DIM, 1, 0, Face.BIG)
        if (best > 0) Sprites.flagIcon(c, lw - 20, st + 8, flagColour(best, h.par), 10)
        val pad = 8
        val aw = 30
        val by = st + 32
        iconButton(c, B_PREV_HOLE, pad, by, aw, PRIMARY_H, Sprites.iconLeft, sel > 0)
        button(c, B_PLAY_HOLE, pad + aw + GAP, by, lw - pad * 2 - aw * 2 - GAP * 2, PRIMARY_H, if (open) "PLAY HOLE" else "LOCKED", open)
        iconButton(c, B_NEXT_HOLE, lw - pad - aw, by, aw, PRIMARY_H, Sprites.iconRight, sel < n - 1)
        button(c, B_BACK, pad, by + PRIMARY_H + GAP, lw - pad * 2, SECONDARY_H, "BACK", false)
    }

    private fun flagColour(strokes: Int, par: Int) = when {
        strokes == 1 -> Palette.FLAG
        strokes <= par -> Palette.GOLD
        else -> Palette.PAPER[1]
    }

    private fun drawPause(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 120))
        val restartAllowed = !courseRun // a restart in a round would be a free mulligan
        val rows = if (restartAllowed) 2 else 1
        val h = lh - safeBottom + PRIMARY_H + rows * (SECONDARY_H + GAP) + 30
        val st = bottomSheet(c, h, sheetT)
        PixelFont.drawCentered(c, "PAUSED", lw / 2, st + 8, Palette.PAPER_INK, 2, 0, Face.BIG)
        val pad = 8
        var y = st + 26
        val bw = (lw - pad * 2 - GAP) / 2
        button(c, B_SETTINGS, pad, y, bw, SECONDARY_H, "SETTINGS", false)
        button(c, B_QUIT, pad + bw + GAP, y, bw, SECONDARY_H, if (courseRun) "SAVE + QUIT" else "QUIT", false)
        y += SECONDARY_H + GAP
        if (restartAllowed) {
            button(c, B_RESTART, pad, y, lw - pad * 2, SECONDARY_H, "RESTART HOLE", false)
            y += SECONDARY_H + GAP
        }
        button(c, B_RESUME, pad, y, lw - pad * 2, PRIMARY_H, "RESUME", true)
    }

    private fun drawSettings(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 140))
        val h = lh - safeBottom + PRIMARY_H + 4 * (SECONDARY_H + GAP) + 30
        val st = bottomSheet(c, h, sheetT)
        PixelFont.drawCentered(c, "SETTINGS", lw / 2, st + 8, Palette.PAPER_INK, 2, 0, Face.BIG)
        val pad = 8
        var y = st + 26
        fun toggle(id: Int, label: String, on: Boolean) {
            button(c, id, pad, y, lw - pad * 2, SECONDARY_H, "$label: ${toggleLabel(on)}", false, on)
            y += SECONDARY_H + GAP
        }
        toggle(B_SOUND, "SOUND", progress.sound)
        toggle(B_BUZZ, "VIBRATION", progress.haptics)
        toggle(B_LEFT, "LEFT-HANDED", progress.leftHanded)
        toggle(B_MOTION, "REDUCE MOTION", progress.reduceMotion)
        button(c, B_DONE, pad, y, lw - pad * 2, PRIMARY_H, "DONE", true)
    }

    private fun resultWord(strokes: Int, par: Int): String {
        val d = strokes - par
        return when {
            strokes == 1 -> "HOLE IN ONE!"
            d <= -3 -> "ALBATROSS!"
            d == -2 -> "EAGLE!"
            d == -1 -> "BIRDIE!"
            d == 0 -> "PAR"
            d == 1 -> "BOGEY"
            d == 2 -> "DOUBLE BOGEY"
            else -> "+$d"
        }
    }

    private fun resultInk(strokes: Int, par: Int) = when {
        strokes < par || strokes == 1 -> Palette.STAMP_RED
        strokes == par -> Palette.PAPER_INK
        else -> Palette.PAPER_INK_DIM
    }

    private fun drawCard(c: Canvas) {
        val k = min(1f, cardT / 0.3f)
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, (90 * k).toInt()))
        val stripH = if (courseRun) 32 + 14 else 0 // the round strip, plus room for the tap hint
        val buttonsH = if (courseRun) PRIMARY_H else PRIMARY_H + GAP + SECONDARY_H
        val h = lh - safeBottom + buttonsH + stripH + 76
        val st = bottomSheet(c, h, cardT)

        val word = resultWord(strokes, hole.par)
        val ws = if (PixelFont.width(word, 2, Face.BIG) <= lw - 16) 2 else 1
        val wy = st + 10 + ((1f - easeOutBack(min(1f, cardT / 0.35f))) * 6).toInt()
        PixelFont.drawCentered(c, word, lw / 2, wy, resultInk(strokes, hole.par), ws, 0, Face.BIG)
        Sprites.flagIcon(c, lw / 2 - 3, st + 28, flagColour(strokes, hole.par), 11)
        bigText(c, "$strokes ${if (strokes == 1) "SHOT" else "SHOTS"} * PAR ${hole.par}", lw / 2, st + 44, Palette.PAPER_INK)
        if (newBest) bigText(c, "NEW BEST!", lw / 2, st + 55, Palette.STAMP_RED)
        else bigText(c, "BEST ${progress.best(holeIndex)}", lw / 2, st + 55, Palette.PAPER_INK_DIM)

        var y = st + 68
        if (courseRun) {
            // The round so far.
            val bw = 22
            val sx = lw / 2 - (n * (bw + 2) - 2) / 2
            for (i in 0 until n) {
                val bx = sx + i * (bw + 2)
                rect(c, bx, y, bw, 17, if (i == holeIndex) Palette.STAMP_RED else Palette.PAPER[1])
                rect(c, bx + 1, y + 1, bw - 2, 15, Palette.PAPER[3])
                smallText(c, "${i + 1}", bx + bw / 2, y + 2, Palette.PAPER_INK_DIM)
                val s = scores[i]
                val p = course.holes[i].par
                smallText(c, if (s > 0) "$s" else "-", bx + bw / 2, y + 9, if (s in 1..p) Palette.STAMP_RED else Palette.PAPER_INK)
            }
            y += 22
            bigText(c, "ROUND ${parText(toPar())}", lw / 2, y, if (toPar() <= 0) Palette.STAMP_RED else Palette.PAPER_INK)
            y += 12
        }
        if (cardT < CARD_READY) return
        val pad = 8
        if (courseRun) {
            val last = holeIndex == n - 1
            button(c, B_NEXT, pad, safeBottom - PRIMARY_H, lw - pad * 2, PRIMARY_H, if (last) "SEE SCORECARD" else "NEXT HOLE", true)
            bigText(c, "OR TAP ANYWHERE", lw / 2, safeBottom - PRIMARY_H - 11, Palette.PAPER_INK_DIM)
        } else {
            val bw = (lw - pad * 2 - GAP) / 2
            val ry = safeBottom - PRIMARY_H - GAP - SECONDARY_H
            button(c, B_RETRY, pad, ry, bw, SECONDARY_H, "RETRY", false)
            button(c, B_SELECT, pad + bw + GAP, ry, bw, SECONDARY_H, "HOLES", false)
            val last = holeIndex == n - 1
            button(c, if (last) B_SELECT else B_NEXT, pad, safeBottom - PRIMARY_H, lw - pad * 2, PRIMARY_H, if (last) "BACK TO HOLES" else "NEXT HOLE", true)
        }
    }

    private fun drawFinal(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 150))
        val h = lh - safeBottom + PRIMARY_H + 160
        val st = bottomSheet(c, h, modeT)
        smallText(c, course.name, lw / 2, st + 7, Palette.PAPER_INK_DIM)
        PixelFont.drawCentered(c, "SCORECARD", lw / 2, st + 15, Palette.PAPER_INK, 2, 0, Face.BIG)
        val total = scores.sum()
        val d = total - course.par

        // Table: label column, six holes, total, on ruled paper.
        val lx = 8
        val cw = 20
        val cx0 = 32
        var ry = st + 38
        Pix.ruled(c, 4, ry + 7, lw - 8, 30, 10)
        fun row(label: String, cell: (Int) -> Pair<String, Int>, tot: Pair<String, Int>) {
            PixelFont.draw(c, label, lx, ry, Palette.PAPER_INK_DIM, 1, 0, Face.SMALL)
            for (i in 0 until n) {
                val (t, cl) = cell(i)
                smallText(c, t, cx0 + i * cw + cw / 2, ry, cl)
            }
            smallText(c, tot.first, cx0 + n * cw + 12, ry, tot.second)
            ry += 10
        }
        row("HOLE", { i -> "${i + 1}" to Palette.PAPER_INK_DIM }, "TOT" to Palette.PAPER_INK_DIM)
        row("PAR", { i -> "${course.holes[i].par}" to Palette.PAPER_INK }, "${course.par}" to Palette.PAPER_INK)
        row("YOU", { i -> "${scores[i]}" to resultInk(scores[i], course.holes[i].par) }, "$total" to (if (d <= 0) Palette.STAMP_RED else Palette.PAPER_INK))
        for (i in 0 until n) Sprites.flagIcon(c, cx0 + i * cw + cw / 2 - 2, ry, flagColour(scores[i], course.holes[i].par), 8)
        ry += 16
        PixelFont.drawCentered(c, "${parText(d)} * $total SHOTS", lw / 2, ry, if (d <= 0) Palette.STAMP_RED else Palette.PAPER_INK, 2, 0, Face.BIG)
        ry += 20
        val best = progress.courseBest(course.name)
        if (courseNewBest) bigText(c, "NEW COURSE BEST!", lw / 2, ry, Palette.STAMP_RED)
        else bigText(c, "COURSE BEST $best (${parText(best - course.par)})", lw / 2, ry, Palette.PAPER_INK_DIM)
        if (modeT < 0.4f) return
        val pad = 8
        val bw = (lw - pad * 2 - GAP) / 3
        button(c, B_MENU, pad, safeBottom - PRIMARY_H, bw, PRIMARY_H, "MENU", false)
        button(c, B_AGAIN, pad + bw + GAP, safeBottom - PRIMARY_H, lw - pad * 2 - bw - GAP, PRIMARY_H, "PLAY AGAIN", true)
    }

    private fun drawFrameTimes(c: Canvas) {
        fun stats(a: FloatArray): Pair<Float, Float>? {
            val s = a.filter { it > 0f }.sorted()
            if (s.isEmpty()) return null
            return s[s.size / 2] to s[(s.size * 95 / 100).coerceAtMost(s.size - 1)]
        }
        val f = stats(frameMs) ?: return
        val d = stats(drawMs) ?: return
        val l1 = "FRAME ${"%.1f".format(f.first)}MS P95 ${"%.1f".format(f.second)}"
        val l2 = "DRAW ${"%.1f".format(d.first)}MS P95 ${"%.1f".format(d.second)}"
        rect(c, 0, insetTop + 14, max(PixelFont.width(l1), PixelFont.width(l2)) + 6, 16, Palette.alpha(Palette.INK_SHADOW, 200))
        PixelFont.draw(c, l1, 3, insetTop + 16, Palette.GOLD)
        PixelFont.draw(c, l2, 3, insetTop + 23, Palette.GOLD)
    }

    // ---------------------------------------------------------------- tests

    internal val ballX get() = physics.x
    internal val ballY get() = physics.y
    internal val ballMoving get() = physics.moving
    internal val ballAirborne get() = physics.airborne
    internal val ballInCup get() = physics.inCup
    internal val strokesForTest get() = strokes
    internal val scoresForTest get() = scores.copyOf()
    internal val busy get() = fadeT >= 0f || flyover
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
    }
}

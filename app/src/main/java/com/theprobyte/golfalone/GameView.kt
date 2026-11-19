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
 * Screens: title → course round (holes in order) or hole select → practice. A round ends
 * on the course scorecard. Buttons are registered as they're drawn each frame and fire on
 * release.
 */
class GameView(
    context: Context,
    private val progress: Progress,
    private val sfx: Sfx,
    private val haptics: Haptics,
) : View(context), PhysicsEvents {

    enum class Mode { TITLE, SELECT, PLAY, CARD, FINAL }

    var mode = Mode.TITLE
        private set

    // ---------------------------------------------------------------- course

    val course: Course = Holes.COVE
    private val n = course.holes.size
    private val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "terrain").apply { isDaemon = true } }
    private val terrains: List<Future<Terrain>> = course.holes.map { h -> builder.submit<Terrain> { Terrain(h) } }
    private val thumbs = arrayOfNulls<Bitmap>(n)

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
    private var paused = false

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
    private var flyover = false
    private var looking = false

    private var fadeT = -1f
    private var fadeAction: (() -> Unit)? = null
    private var fadeDone = false

    // Aim
    private var aiming = false
    private var aimPointer = -1
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var power = 0f
    private var tickStep = 0
    private val cancelPx get() = 14f * density
    private val maxDragPx get() = 0.38f * width

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

    // ---------------------------------------------------------------- buttons

    private val maxButtons = 24
    private val btnIds = IntArray(maxButtons)
    private val btnHit = Array(maxButtons) { Rect() }
    private var btnCount = 0
    private var pressedId = NONE
    private var pressX = 0
    private var pressY = 0

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
        world = Bitmap.createBitmap(lw + 1, lh + 1, Bitmap.Config.ARGB_8888).also { wc.setBitmap(it) }
        ui = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888).also { uc.setBitmap(it) }
        flyover = mode == Mode.PLAY && strokes == 0 && holeT < 1.6f && isLong()
        camSnap = true
    }

    // ---------------------------------------------------------------- flow

    private fun loadHole(i: Int) {
        holeIndex = i
        hole = course.holes[i]
        physics = Physics(hole)
        terrain = terrains[i].get()
        slopeTiles = (0 until hole.w * hole.h)
            .filter { val t = hole.tiles[it]; t.slopeX != 0f || t.slopeY != 0f }.toIntArray()
        strokes = 0
        sinkT = -1f
        newBest = false
        aiming = false
        looking = false
        paused = false
        popT = -1f
        rollDist = 0f
        holeT = 0f
        for (k in 0 until pn) pLife[k] = 0f
        for (k in 0 until rn) rT[k] = -1f
        for (k in 0 until tn) tAt[k] = -9f
        msg = null
        // A long hole opens on the flag, then the camera glides back to the tee.
        flyover = lh > 0 && isLong()
        camSnap = true
    }

    private fun fitsVertically() = hole.pxH <= playBottom - playTop

    /** The green is out of sight from the tee: worth a flyover and the look button. */
    private fun isLong() = hole.pxH - (playBottom - playTop) > LONG_SCROLL

    private fun setMode(m: Mode) {
        mode = m
        modeT = 0f
        pressedId = NONE
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

    fun startCourse() = fadeTo {
        courseRun = true
        scores.fill(0)
        courseNewBest = false
        loadHole(0)
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
        paused = false
        setMode(Mode.TITLE)
    }

    private fun toSelect() = fadeTo {
        aiming = false
        paused = false
        setMode(Mode.SELECT)
    }

    /** System back: pause a hole, close a menu, or step back towards the title. */
    fun onBack(): Boolean {
        if (fadeT >= 0f) return true
        when (mode) {
            Mode.TITLE -> return false
            Mode.PLAY -> { paused = !paused; aiming = false; sfx.tap() }
            Mode.SELECT -> toTitle()
            Mode.CARD -> if (courseRun) toTitle() else toSelect()
            Mode.FINAL -> toTitle()
        }
        return true
    }

    /** The app went to the background: pause any hole in progress. */
    fun onHostPause() {
        if (mode == Mode.PLAY && sinkT < 0f) paused = true
        aiming = false
        pressedId = NONE
        looking = false
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

    private val canAim get() = mode == Mode.PLAY && !paused && !physics.moving && !physics.inCup && !flyover

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fadeT >= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val lx = floor(toLX(e.x)).toInt()
                val ly = floor(toLY(e.y)).toInt()
                val id = hitButton(lx, ly)
                if (id != NONE) {
                    pressedId = id; pressX = lx; pressY = ly
                    haptics.tap(this)
                    if (id == B_LOOK) looking = true
                } else if (mode == Mode.PLAY && flyover && !paused) {
                    flyover = false // a tap skips the flyover
                } else if (canAim && !inGestureZone(e.x, e.y)) {
                    aiming = true
                    aimPointer = e.getPointerId(0)
                    downX = e.x; downY = e.y; curX = e.x; curY = e.y
                    power = 0f; tickStep = 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (aiming) {
                    val i = e.findPointerIndex(aimPointer)
                    if (i >= 0) { curX = e.getX(i); curY = e.getY(i); updatePower() }
                }
                if (pressedId != NONE) { pressX = floor(toLX(e.x)).toInt(); pressY = floor(toLY(e.y)).toInt() }
            }
            MotionEvent.ACTION_POINTER_UP -> if (aiming && e.getPointerId(e.actionIndex) == aimPointer) {
                aiming = false
                curX = e.getX(e.actionIndex); curY = e.getY(e.actionIndex)
                updatePower()
                if (power > 0f) shoot()
            }
            MotionEvent.ACTION_UP -> {
                if (aiming) {
                    aiming = false
                    val i = e.findPointerIndex(aimPointer).coerceAtLeast(0)
                    curX = e.getX(i); curY = e.getY(i)
                    updatePower()
                    if (power > 0f) shoot()
                }
                val id = pressedId
                pressedId = NONE
                looking = false
                if (id != NONE && id != B_LOOK && hitButton(floor(toLX(e.x)).toInt(), floor(toLY(e.y)).toInt()) == id) onButton(id)
            }
            MotionEvent.ACTION_CANCEL -> { aiming = false; pressedId = NONE; looking = false }
        }
        return true
    }

    private fun onButton(id: Int) {
        sfx.tap()
        when (id) {
            B_PLAY -> startCourse()
            B_HOLES -> toSelect()
            B_BACK -> toTitle()
            B_SOUND -> { progress.sound = !progress.sound; sfx.enabled = progress.sound; sfx.tap() }
            B_BUZZ -> { progress.haptics = !progress.haptics; haptics.enabled = progress.haptics; haptics.tap(this) }
            B_PAUSE -> { paused = true; aiming = false }
            B_RESUME -> paused = false
            B_RESTART -> { val i = holeIndex; fadeTo { loadHole(i); setMode(Mode.PLAY) } }
            B_QUIT -> toTitle()
            B_NEXT -> nextHole()
            B_RETRY -> practise(holeIndex)
            B_SELECT -> toSelect()
            B_MENU -> toTitle()
            B_AGAIN -> startCourse()
            else -> if (id >= B_HOLE0 && id < B_HOLE0 + n) {
                val i = id - B_HOLE0
                if (progress.unlocked(i)) practise(i) else sayUi("FINISH HOLE $i TO UNLOCK")
            }
        }
    }

    private fun nextHole() {
        val next = holeIndex + 1
        if (courseRun && next >= n) {
            fadeTo {
                courseNewBest = progress.recordCourse(course.name, scores.sum())
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
        if (impact > 170f) shakeT = 0f
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

    override fun onSplash(x: Float, y: Float) {
        strokes++ // penalty
        sfx.splash(pan(x))
        haptics.splash(this)
        shakeT = 0f
        repeat(16) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 20f + rnd.nextFloat() * 45f
            spawn(x, y, cos(a) * s, sin(a) * s, 0.45f + rnd.nextFloat() * 0.35f, if (it % 3 == 0) Palette.SPARKLE else Palette.FOAM)
        }
        ring(x, y, 0.7f, 10f, Palette.FOAM)
        ring(x, y, 1.0f, 16f, Palette.SPARKLE)
        say("+1 SPLASH", x, y - 6f, Palette.FOAM)
        popT = 0f
        sfx.pop(pan(physics.x))
        for (i in 0 until tn) tAt[i] = -9f
    }

    override fun onLipOut(x: Float, y: Float, speed: Float) {
        sfx.lipOut(pan(x))
        haptics.lipOut(this)
        say("LIP OUT!", hole.cupX, hole.cupY - 18f, Palette.GOLD)
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
        if (courseRun) scores[holeIndex] = strokes
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

    /** A message in a strip near the bottom of the screen. */
    private fun sayUi(text: String) {
        msg = text; msgT = 0f; msgCol = Palette.INK; msgUi = true
    }

    // ---------------------------------------------------------------- update

    private fun update(dt: Float) {
        time += dt
        modeT += dt
        if (fadeT >= 0f) {
            fadeT += dt
            if (!fadeDone && fadeT >= FADE) { fadeDone = true; fadeAction?.invoke(); fadeAction = null }
            if (fadeT >= 2 * FADE) fadeT = -1f
        }
        val live = mode == Mode.PLAY && !paused
        if (live && fadeT < 0f) holeT += dt
        if (flyover && holeT > 1.6f) flyover = false

        if (live && physics.moving) {
            acc += dt
            var steps = 0
            while (acc >= Physics.STEP && steps < MAX_STEPS_PER_FRAME) {
                physics.step(this)
                rollDist += physics.speed * Physics.STEP
                acc -= Physics.STEP
                steps++
                if (!physics.moving) { acc = 0f; break }
            }
            if (physics.moving && physics.speed > 90f && time - tLast > 1f / 50f) {
                tX[tNext] = physics.x; tY[tNext] = physics.y; tAt[tNext] = time
                tNext = (tNext + 1) % tn; tLast = time
            }
        } else acc = 0f

        if (sinkT >= 0f) {
            sinkT += dt
            if (sinkT > 1.1f && mode == Mode.PLAY) setMode(Mode.CARD)
        }
        if (mode == Mode.CARD) cardT += dt
        if (popT >= 0f) { popT += dt; if (popT > 0.5f) popT = -1f }
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
        if (looking || (flyover && mode == Mode.PLAY)) { focusX = hole.cupX; focusY = hole.cupY }
        val tx = if (hole.pxW <= lw) -((lw - hole.pxW) / 2).toFloat()
        else (focusX - lw / 2f).coerceIn(0f, (hole.pxW - lw).toFloat())
        val ty = if (hole.pxH <= playH) -(playTop + (playH - hole.pxH) / 2).toFloat()
        else (focusY - playTop - playH / 2f).coerceIn(-playTop.toFloat(), (hole.pxH - playBottom).toFloat())
        if (camSnap) { camX = tx; camY = ty; camSnap = false }
        val k = 1f - exp(-(if (looking) 6f else 3.6f) * dt)
        camX += (tx - camX) * k
        camY += (ty - camY) * k
        if (abs(tx - camX) < 0.02f) camX = tx
        if (abs(ty - camY) < 0.02f) camY = ty
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        val wb = world ?: return
        val ub = ui ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = now
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

        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastMs = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawWorld(ix: Int, iy: Int) {
        val c = wc
        c.drawColor(Palette.DEEP)
        val gx = -ix.toFloat()
        val gy = -iy.toFloat()
        c.drawBitmap(terrain.ground, gx, gy, blit)
        c.drawBitmap(terrain.water[((time * 5f).toInt()) % Terrain.FRAMES], gx, gy, blit)

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
        if (under) Sprites.ball(c, physics.x.roundToInt() - ix, physics.y.roundToInt() - iy, 0, ghost = true)

        val near = hypot(physics.x - hole.cupX, physics.y - hole.cupY) < 24f && physics.moving
        val lift = if (sinkT >= 0f) min(3, (sinkT * 20f).toInt()) else 0
        Sprites.flag(c, cx, cy, ((time * (if (near) 12f else 3f)).toInt()) % 2, lift)

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
                Palette.alpha(msgCol, a), 1, Palette.alpha(Palette.INK_SHADOW, a))
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
        val x = bx.roundToInt() - ix
        val y = by.roundToInt() - iy
        val dir = if (physics.vx + physics.vy >= 0f) 1 else -1
        Sprites.ball(c, x, y, ((rollDist / 2.5f).toInt() * dir) and 3)
        // An idle ball breathes a faint ring so it's easy to find.
        if (mode == Mode.PLAY && !physics.moving && !aiming && !paused && !flyover) {
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
        when (mode) {
            Mode.TITLE -> drawTitle(c)
            Mode.SELECT -> drawSelect(c)
            Mode.PLAY -> { drawHud(c); if (paused) drawPause(c) else drawPlayUi(c) }
            Mode.CARD -> { drawHud(c); drawCard(c) }
            Mode.FINAL -> drawFinal(c)
        }
        val m = msg
        if (m != null && msgUi) {
            val a = if (msgT < 1f) 255 else (255 * (1f - (msgT - 1f) / 0.4f)).toInt()
            val y = lh - insetBottom - 44
            rect(c, 0, y - 4, lw, 13, Palette.alpha(Palette.INK_SHADOW, 180 * a / 255))
            PixelFont.drawCentered(c, m, lw / 2, y, Palette.alpha(msgCol, a), 1)
        }
        if (fadeT >= 0f) {
            val l = if (fadeT < FADE) fadeT / FADE else 1f - (fadeT - FADE) / FADE
            Pix.dissolve(c, lw, lh, l)
        }
    }

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

    /** A pixel button that drops a pixel while held. */
    private fun button(c: Canvas, id: Int, x: Int, y: Int, w: Int, h: Int, text: String, style: Int = STYLE_NORMAL, textScale: Int = 1) {
        addButton(id, x, y, w, h)
        val down = isPressed(id)
        val face = when (style) {
            STYLE_PRIMARY -> Palette.FAIR_B
            STYLE_OFF -> Palette.STONE_DARK
            else -> Palette.PANEL_EDGE
        }
        val oy = if (down) 1 else 0
        if (!down) rect(c, x + 1, y + h - 1, w - 2, 2, Palette.INK_SHADOW)
        rect(c, x + 1, y + oy, w - 2, h - 1, face)
        rect(c, x, y + 1 + oy, w, h - 3, face)
        rect(c, x + 1, y + oy, w - 2, 1, Palette.alpha(Palette.INK, if (down) 30 else 80))
        val ty = y + oy + (h - 1 - PixelFont.H * textScale) / 2
        PixelFont.drawCentered(c, text, x + w / 2, ty, Palette.INK, textScale, Palette.INK_SHADOW)
    }

    private fun iconButton(c: Canvas, id: Int, x: Int, y: Int, w: Int, h: Int, draw: (Int) -> Unit) {
        addButton(id, x, y, w, h, pad = 5)
        val down = isPressed(id)
        val o = if (down) 1 else 0
        rect(c, x, y + o, w, h, Palette.alpha(Palette.PANEL_EDGE, if (down) 255 else 170))
        draw(o)
    }

    private fun drawHud(c: Canvas) {
        val y = insetTop + 3
        rect(c, 0, 0, lw, y + 8, Palette.alpha(Palette.INK_SHADOW, 160))
        rect(c, 0, y + 8, lw, 1, Palette.alpha(Palette.INK_SHADOW, 70))
        if (mode == Mode.PLAY) {
            iconButton(c, B_PAUSE, 2, y - 2, 11, 9) { o -> Sprites.pauseIcon(c, 5, y - 1 + o, Palette.INK) }
        }
        val holeText = "HOLE ${holeIndex + 1}/$n"
        PixelFont.draw(c, holeText, 17, y, Palette.INK, 1, Palette.INK_SHADOW)
        PixelFont.draw(c, "PAR ${hole.par}", 17 + PixelFont.width(holeText) + 6, y, Palette.INK_DIM, 1, Palette.INK_SHADOW)

        var right = lw - 3
        if (mode == Mode.PLAY && isLong()) {
            val r = right
            iconButton(c, B_LOOK, r - 11, y - 2, 11, 9) { o ->
                Sprites.flagIcon(c, r - 8, y - 1 + o, if (looking) Palette.GOLD else Palette.FLAG, 7)
            }
            right -= 15
        }
        val s = "SHOTS $strokes"
        PixelFont.draw(c, s, right - PixelFont.width(s), y, if (strokes > hole.par) Palette.HOT else Palette.INK, 1, Palette.INK_SHADOW)
        if (courseRun) {
            val d = toPar()
            val t = parText(d)
            val tw = PixelFont.width(t) + 4
            val tx = right - PixelFont.width(s) - 5 - tw
            rect(c, tx, y - 1, tw, 7, Palette.alpha(Palette.PANEL_EDGE, 220))
            PixelFont.draw(c, t, tx + 2, y, if (d <= 0) Palette.GOLD else Palette.INK, 1)
        }
    }

    private fun drawPlayUi(c: Canvas) {
        // Hole banner while the round begins (and during the flyover).
        if (holeT < 2.6f && strokes == 0) {
            val a = when {
                holeT < 0.3f -> holeT / 0.3f
                holeT > 2.1f -> 1f - (holeT - 2.1f) / 0.5f
                else -> 1f
            }.coerceIn(0f, 1f)
            // On long holes the flyover shows the green up top, so the banner sits low.
            val y = if (isLong()) playBottom - 62 else playTop + 26
            val ai = (255 * a).toInt()
            val sh = Palette.alpha(Palette.INK_SHADOW, ai)
            rect(c, 0, y - 6, lw, 36, Palette.alpha(Palette.INK_SHADOW, (150 * a).toInt()))
            PixelFont.drawCentered(c, "${course.name} * HOLE ${holeIndex + 1}", lw / 2, y, Palette.alpha(Palette.INK_DIM, ai), 1, sh)
            PixelFont.drawCentered(c, hole.name, lw / 2, y + 8, Palette.alpha(Palette.INK, ai), 2, sh)
            PixelFont.drawCentered(c, "PAR ${hole.par}", lw / 2, y + 21, Palette.alpha(Palette.GOLD, ai), 1, sh)
        }

        // Rubber band from where you touched to your finger.
        if (aiming) {
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
                PixelFont.draw(c, pct, ax + 7, ay - 2, if (power >= 1f) Palette.HOT else Palette.INK, 1, Palette.INK_SHADOW)
            }
        }

        // Coaching until the first hole has ever been finished.
        if (progress.best(0) == 0 && holeIndex == 0 && strokes == 0 && !physics.moving && holeT > 1f) {
            val y = playBottom - 12
            val pulse = (0.6f + 0.4f * sin(time * 3f)).coerceIn(0f, 1f)
            val a = (255 * pulse).toInt()
            val text = if (aiming) (if (power > 0f) "LET GO TO SHOOT" else "PULL BACK TO AIM") else "PULL BACK ANYWHERE TO AIM"
            PixelFont.drawCentered(c, text, lw / 2, y, Palette.alpha(Palette.INK, a), 1, Palette.alpha(Palette.INK_SHADOW, a))
            if (aiming && power > 0f) PixelFont.drawCentered(c, "DRAG BACK IN TO CANCEL", lw / 2, y + 8, Palette.alpha(Palette.INK_DIM, a), 1, Palette.alpha(Palette.INK_SHADOW, a))
        }
        if (looking) PixelFont.drawCentered(c, "LOOKING AT THE GREEN", lw / 2, playBottom - 12, Palette.INK, 1, Palette.INK_SHADOW)
        else if (flyover && holeT > 0.4f) PixelFont.drawCentered(c, "TAP TO SKIP", lw / 2, playBottom - 12, Palette.INK_DIM, 1, Palette.INK_SHADOW)
    }

    private fun toggleText(on: Boolean, name: String) = if (on) "$name ON" else "$name OFF"

    private fun drawTitle(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 140))
        val a = (255 * min(1f, modeT / 0.5f)).toInt()
        val top = (lh * 0.17f).toInt()
        val bob = (sin(time * 1.2f) * 1.5f).roundToInt()
        PixelFont.drawCentered(c, "GOLF", lw / 2, top + bob, Palette.alpha(Palette.INK, a), 6, Palette.alpha(Palette.INK_SHADOW, a))
        PixelFont.drawCentered(c, "ALONE", lw / 2, top + 36 + bob, Palette.alpha(Palette.GOLD, a), 5, Palette.alpha(Palette.INK_SHADOW, a))
        PixelFont.drawCentered(c, "A QUIET ISLAND COURSE", lw / 2, top + 68, Palette.alpha(Palette.INK_DIM, a), 1, Palette.INK_SHADOW)

        val bw = 124
        val bx = (lw - bw) / 2
        var y = (lh * 0.52f).toInt()
        button(c, B_PLAY, bx, y, bw, 22, "PLAY COURSE", STYLE_PRIMARY, 2)
        val best = progress.courseBest(course.name)
        val bestText = if (best == 0) "${course.name} * PAR ${course.par}" else "BEST $best (${parText(best - course.par)}) * PAR ${course.par}"
        PixelFont.drawCentered(c, bestText, lw / 2, y + 27, Palette.INK_DIM, 1, Palette.INK_SHADOW)
        y += 42
        button(c, B_HOLES, bx, y, bw, 16, "PRACTISE A HOLE")

        val by = lh - insetBottom - 26
        val tw = 64
        button(c, B_SOUND, lw / 2 - tw - 4, by, tw, 14, toggleText(progress.sound, "SOUND"), if (progress.sound) STYLE_NORMAL else STYLE_OFF)
        button(c, B_BUZZ, lw / 2 + 4, by, tw, 14, toggleText(progress.haptics, "BUZZ"), if (progress.haptics) STYLE_NORMAL else STYLE_OFF)
    }

    private fun thumb(i: Int): Bitmap? {
        thumbs[i]?.let { return it }
        val f = terrains[i]
        if (!f.isDone) return null
        val t = f.get()
        val step = max(ceil(t.w / 64f).toInt(), ceil(t.h / 62f).toInt()).coerceAtLeast(1)
        return t.thumbnail(step).also { thumbs[i] = it }
    }

    private fun drawSelect(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 200))
        val top = insetTop + 4
        button(c, B_BACK, 4, top, 22, 12, "<")
        PixelFont.drawCentered(c, course.name, lw / 2 + 8, top + 3, Palette.INK, 1, Palette.INK_SHADOW)
        val cols = 2
        val gap = 6
        val cw = (lw - gap * 3) / cols
        val avail = playBottom - (top + 20)
        val ch = min(118, (avail - gap * 2) / 3)
        for (i in 0 until n) {
            val col = i % cols
            val row = i / cols
            val x = gap + col * (cw + gap)
            val y = top + 20 + row * (ch + gap)
            val open = progress.unlocked(i)
            addButton(B_HOLE0 + i, x, y, cw, ch, pad = 1)
            val down = isPressed(B_HOLE0 + i)
            val oy = if (down) 1 else 0
            Pix.panel(c, x, y + oy, cw, ch, Palette.PANEL, if (down) Palette.GOLD else Palette.PANEL_EDGE)
            // Thumbnail of the hole.
            val tb = thumb(i)
            val th = ch - 30
            if (tb != null) {
                val sx = x + (cw - tb.width) / 2
                val sy = y + 4 + oy + max(0, (th - tb.height) / 2)
                c.save()
                c.clipRect(x + 2, y + 3 + oy, x + cw - 2, y + 4 + th + oy)
                c.drawBitmap(tb, sx.toFloat(), sy.toFloat(), blit)
                c.restore()
            }
            if (!open) {
                rect(c, x + 2, y + 3 + oy, cw - 4, th + 1, Palette.alpha(Palette.INK_SHADOW, 170))
                Sprites.lockIcon(c, x + cw / 2 - 2, y + th / 2 + oy, Palette.INK_DIM)
            }
            val ty = y + th + 8 + oy
            PixelFont.drawCentered(c, "${i + 1}. ${course.holes[i].name}", x + cw / 2, ty, if (open) Palette.INK else Palette.INK_DIM, 1)
            val best = progress.best(i)
            val info = if (best == 0) "PAR ${course.holes[i].par}" else "PAR ${course.holes[i].par} * BEST $best"
            PixelFont.drawCentered(c, info, x + cw / 2, ty + 8, Palette.INK_DIM, 1)
            if (best > 0) Sprites.flagIcon(c, x + cw - 10, y + 5 + oy, flagColour(best, course.holes[i].par))
        }
    }

    private fun flagColour(strokes: Int, par: Int) = when {
        strokes == 1 -> Palette.FLAG
        strokes <= par -> Palette.GOLD
        else -> Palette.INK
    }

    private fun drawPause(c: Canvas) {
        rect(c, 0, playTop - 3, lw, lh, Palette.alpha(Palette.INK_SHADOW, 150))
        val w = 132
        val h = 132
        val x = (lw - w) / 2
        val y = (lh - h) / 2
        Pix.panel(c, x, y, w, h)
        PixelFont.drawCentered(c, "PAUSED", lw / 2, y + 8, Palette.INK, 2, Palette.INK_SHADOW)
        val bw = w - 24
        var by = y + 26
        button(c, B_RESUME, x + 12, by, bw, 16, "RESUME", STYLE_PRIMARY); by += 21
        button(c, B_RESTART, x + 12, by, bw, 16, "RESTART HOLE"); by += 21
        val hw = (bw - 4) / 2
        button(c, B_SOUND, x + 12, by, hw, 16, toggleText(progress.sound, "SOUND"), if (progress.sound) STYLE_NORMAL else STYLE_OFF)
        button(c, B_BUZZ, x + 12 + hw + 4, by, hw, 16, toggleText(progress.haptics, "BUZZ"), if (progress.haptics) STYLE_NORMAL else STYLE_OFF)
        by += 21
        button(c, B_QUIT, x + 12, by, bw, 16, if (courseRun) "QUIT ROUND" else "QUIT TO MENU")
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

    private fun easeOutBack(k: Float): Float = 1f + 2.2f * (k - 1f) * (k - 1f) * (k - 1f) + 1.2f * (k - 1f) * (k - 1f)

    private fun drawCard(c: Canvas) {
        val k = min(1f, cardT / 0.45f)
        val w = 156
        val h = if (courseRun) 128 else 104
        val x = (lw - w) / 2
        val y = ((lh - h) / 2 + (1f - easeOutBack(k)) * lh * 0.7f).toInt()
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, (110 * k).toInt()))
        Pix.panel(c, x, y, w, h)

        val good = strokes <= hole.par
        val word = resultWord(strokes, hole.par)
        val ws = if (PixelFont.width(word, 2) <= w - 12) 2 else 1
        PixelFont.drawCentered(c, word, lw / 2, y + 9, if (good) Palette.GOLD else Palette.INK, ws, Palette.INK_SHADOW)
        Sprites.flagIcon(c, lw / 2 - 3, y + 25, flagColour(strokes, hole.par), 11)
        PixelFont.drawCentered(c, "$strokes ${if (strokes == 1) "SHOT" else "SHOTS"} * PAR ${hole.par}", lw / 2, y + 41, Palette.INK, 1, Palette.INK_SHADOW)
        if (newBest) {
            if ((cardT * 3f % 1f) < 0.75f) PixelFont.drawCentered(c, "NEW BEST!", lw / 2, y + 50, Palette.GOLD, 1, Palette.INK_SHADOW)
        } else PixelFont.drawCentered(c, "BEST ${progress.best(holeIndex)}", lw / 2, y + 50, Palette.INK_DIM, 1, Palette.INK_SHADOW)

        var by = y + 62
        if (courseRun) {
            // The round so far.
            val bw = 20
            val sx = lw / 2 - (n * (bw + 2) - 2) / 2
            for (i in 0 until n) {
                val bx = sx + i * (bw + 2)
                rect(c, bx, by, bw, 15, if (i == holeIndex) Palette.GOLD else Palette.PANEL_EDGE)
                rect(c, bx + 1, by + 1, bw - 2, 13, Palette.INK_SHADOW)
                PixelFont.drawCentered(c, "${i + 1}", bx + bw / 2, by + 2, Palette.INK_DIM, 1)
                val s = scores[i]
                if (s > 0) PixelFont.drawCentered(c, "$s", bx + bw / 2, by + 8, if (s <= course.holes[i].par) Palette.GOLD else Palette.INK, 1)
                else PixelFont.drawCentered(c, "-", bx + bw / 2, by + 8, Palette.INK_DIM, 1)
            }
            by += 20
            PixelFont.drawCentered(c, "ROUND ${parText(toPar())}", lw / 2, by, if (toPar() <= 0) Palette.GOLD else Palette.INK, 1, Palette.INK_SHADOW)
            by += 12
        }
        if (cardT < 0.5f) return
        if (courseRun) {
            val last = holeIndex == n - 1
            button(c, B_NEXT, x + 12, by, w - 24, 18, if (last) "SEE SCORECARD" else "NEXT HOLE", STYLE_PRIMARY)
        } else {
            val bw = (w - 24 - 8) / 3
            button(c, B_RETRY, x + 12, by + 4, bw, 18, "RETRY")
            if (holeIndex < n - 1) button(c, B_NEXT, x + 12 + bw + 4, by + 4, bw, 18, "NEXT", STYLE_PRIMARY)
            button(c, B_SELECT, x + 12 + (bw + 4) * 2, by + 4, bw, 18, "HOLES")
        }
    }

    private fun drawFinal(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 170))
        val k = min(1f, modeT / 0.45f)
        val w = 170
        val h = 176
        val x = (lw - w) / 2
        val y = ((lh - h) / 2 + (1f - easeOutBack(k)) * lh * 0.6f).toInt()
        Pix.panel(c, x, y, w, h)
        PixelFont.drawCentered(c, course.name, lw / 2, y + 7, Palette.INK_DIM, 1, Palette.INK_SHADOW)
        val total = scores.sum()
        val d = total - course.par
        PixelFont.drawCentered(c, "SCORECARD", lw / 2, y + 16, Palette.INK, 2, Palette.INK_SHADOW)

        // Table: label column, six holes, total.
        val lx = x + 6
        val cw = 18
        val cx0 = x + 26
        var ry = y + 36
        fun row(label: String, cell: (Int) -> Pair<String, Int>, tot: Pair<String, Int>) {
            PixelFont.draw(c, label, lx, ry, Palette.INK_DIM, 1)
            for (i in 0 until n) {
                val (t, cl) = cell(i)
                PixelFont.drawCentered(c, t, cx0 + i * cw + cw / 2, ry, cl, 1)
            }
            PixelFont.drawCentered(c, tot.first, cx0 + n * cw + 14, ry, tot.second, 1)
            ry += 10
        }
        row("HOLE", { i -> "${i + 1}" to Palette.INK_DIM }, "TOT" to Palette.INK_DIM)
        rect(c, x + 4, ry - 3, w - 8, 1, Palette.PANEL_EDGE)
        row("PAR", { i -> "${course.holes[i].par}" to Palette.INK }, "${course.par}" to Palette.INK)
        row("YOU", { i ->
            val s = scores[i]
            val p = course.holes[i].par
            "$s" to (if (s == 1) Palette.FLAG else if (s < p) Palette.GOLD else if (s == p) Palette.INK else Palette.HOT)
        }, "$total" to (if (d <= 0) Palette.GOLD else Palette.INK))
        // Flags earned.
        for (i in 0 until n) Sprites.flagIcon(c, cx0 + i * cw + cw / 2 - 2, ry, flagColour(scores[i], course.holes[i].par), 8)
        ry += 16

        PixelFont.drawCentered(c, "${parText(d)} * $total SHOTS", lw / 2, ry, if (d <= 0) Palette.GOLD else Palette.INK, 2, Palette.INK_SHADOW)
        ry += 16
        val best = progress.courseBest(course.name)
        if (courseNewBest) {
            if ((modeT * 3f % 1f) < 0.75f) PixelFont.drawCentered(c, "NEW COURSE BEST!", lw / 2, ry, Palette.GOLD, 1, Palette.INK_SHADOW)
        } else PixelFont.drawCentered(c, "COURSE BEST $best (${parText(best - course.par)})", lw / 2, ry, Palette.INK_DIM, 1, Palette.INK_SHADOW)
        ry += 14
        if (modeT < 0.5f) return
        val bw = (w - 28) / 2
        button(c, B_AGAIN, x + 10, ry, bw, 18, "PLAY AGAIN", STYLE_PRIMARY)
        button(c, B_MENU, x + 18 + bw, ry, bw, 18, "MENU")
    }

    // ---------------------------------------------------------------- tests

    internal val ballX get() = physics.x
    internal val ballY get() = physics.y
    internal val ballMoving get() = physics.moving
    internal val ballInCup get() = physics.inCup
    internal val strokesForTest get() = strokes
    internal val scoresForTest get() = scores.copyOf()
    internal val busy get() = fadeT >= 0f || flyover
    internal val fading get() = fadeT >= 0f
    internal val isPaused get() = paused
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

    companion object {
        const val LOGICAL_W = 180
        const val HUD_H = 12
        const val GULL_SPEED = 26f
        const val FADE = 0.28f
        const val LONG_SCROLL = 64
        const val MAX_STEPS_PER_FRAME = Physics.HZ / 8

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
        const val B_LOOK = 15
        const val B_HOLE0 = 100

        private const val STYLE_NORMAL = 0
        private const val STYLE_PRIMARY = 1
        private const val STYLE_OFF = 2
    }
}

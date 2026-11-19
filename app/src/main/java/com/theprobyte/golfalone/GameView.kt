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
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.game.Holes
import com.theprobyte.golfalone.game.Physics
import com.theprobyte.golfalone.game.PhysicsEvents
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.Terrain
import kotlin.math.PI
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
 * one pixel larger than the view and shifted by the camera's sub-pixel remainder at
 * screen resolution, so the camera glides smoothly while the art stays on its grid.
 */
class GameView(
    context: Context,
    private val progress: Progress,
    private val sfx: Sfx,
    private val haptics: Haptics,
) : View(context), PhysicsEvents {

    enum class Mode { TITLE, PLAY, CARD }

    var mode = Mode.TITLE
        private set

    private val holeIndex = 0
    val hole: Hole = Holes.all[holeIndex]
    private val physics = Physics(hole)
    private val terrain = Terrain(hole)
    private val slopeTiles: IntArray = (0 until hole.w * hole.h)
        .filter { val t = hole.tiles[it]; t.slopeX != 0f || t.slopeY != 0f }.toIntArray()

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
    private val p = Paint().apply { isAntiAlias = false }
    private val stroke = Paint().apply { isAntiAlias = false; style = Paint.Style.STROKE; strokeWidth = 0f }
    private val src = Rect()
    private val dst = RectF()
    private val density = resources.displayMetrics.density

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
    private var newBest = false
    private var lastWallHaptic = 0f
    private var popT = -1f

    // Aim
    private var aiming = false
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

    private var gullT = -1f
    private var gullY = 0f
    private var gullDir = 1f
    private var gullNext = 4f
    private var surfNext = 1.5f

    // Hit areas (logical px) for the title and the card.
    private val btnA = Rect()
    private val btnB = Rect()

    init {
        isFocusable = true
        setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
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
        camSnap = true
    }

    // ---------------------------------------------------------------- flow

    fun startRound() {
        physics.reset()
        strokes = 0
        sinkT = -1f
        newBest = false
        aiming = false
        popT = -1f
        for (i in 0 until pn) pLife[i] = 0f
        for (i in 0 until rn) rT[i] = -1f
        for (i in 0 until tn) tAt[i] = -9f
        msg = null
        setMode(Mode.PLAY)
        camSnap = true
        keepScreenOn = true
    }

    private fun setMode(m: Mode) {
        mode = m
        modeT = 0f
        if (m == Mode.CARD) cardT = 0f
        if (m == Mode.TITLE) keepScreenOn = false
    }

    /** Back: from a round to the title. Returns false when already on the title. */
    fun onBack(): Boolean {
        if (mode == Mode.TITLE) return false
        aiming = false
        if (!physics.inCup) physics.reset()
        setMode(Mode.TITLE)
        sfx.tap()
        return true
    }

    // ---------------------------------------------------------------- input

    private fun inGestureZone(x: Float, y: Float) =
        x < gestLeft || x > width - gestRight || y > height - gestBottom

    private fun toLX(sx: Float) = (sx - offX) / scale
    private fun toLY(sy: Float) = (sy - offY) / scale

    /** The finger that started the aim; other fingers are ignored. */
    private var aimPointer = -1

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.PLAY && !physics.moving && !physics.inCup && !inGestureZone(e.x, e.y)) {
                    aiming = true
                    aimPointer = e.getPointerId(0)
                    downX = e.x; downY = e.y; curX = e.x; curY = e.y
                    power = 0f; tickStep = 0
                }
            }
            MotionEvent.ACTION_MOVE -> if (aiming) {
                val i = e.findPointerIndex(aimPointer)
                if (i >= 0) {
                    curX = e.getX(i); curY = e.getY(i)
                    updatePower()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (aiming && e.getPointerId(e.actionIndex) == aimPointer) {
                // The aiming finger lifted while another stays down: shoot from where it let go.
                aiming = false
                curX = e.getX(e.actionIndex); curY = e.getY(e.actionIndex)
                updatePower()
                if (power > 0f) shoot()
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.PLAY -> if (aiming) {
                        aiming = false
                        val i = e.findPointerIndex(aimPointer).coerceAtLeast(0)
                        curX = e.getX(i); curY = e.getY(i)
                        updatePower()
                        if (power > 0f) shoot()
                    }
                    Mode.TITLE -> titleTap(toLX(e.x).toInt(), toLY(e.y).toInt())
                    Mode.CARD -> cardTap(toLX(e.x).toInt(), toLY(e.y).toInt())
                }
            }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
        return true
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

    private fun titleTap(x: Int, y: Int) {
        when {
            btnA.contains(x, y) -> {
                progress.sound = !progress.sound
                sfx.enabled = progress.sound
                sfx.tap()
            }
            btnB.contains(x, y) -> {
                progress.haptics = !progress.haptics
                haptics.enabled = progress.haptics
                haptics.tap(this)
                sfx.tap()
            }
            modeT > 0.25f -> { sfx.tap(); haptics.tap(this); startRound() }
        }
    }

    private fun cardTap(x: Int, y: Int) {
        if (cardT < 0.5f) return
        when {
            btnA.contains(x, y) -> { sfx.tap(); haptics.tap(this); startRound() }
            btnB.contains(x, y) -> { sfx.tap(); physics.reset(); setMode(Mode.TITLE) }
        }
    }

    // ---------------------------------------------------------------- physics events

    private fun pan(wx: Float) = if (lw == 0) 0f else (((wx - camX) / lw) * 2f - 1f).coerceIn(-1f, 1f) * 0.8f

    override fun onWall(x: Float, y: Float, impact: Float) {
        sfx.wall(impact, pan(x))
        if (time - lastWallHaptic > 0.04f) { haptics.wall(this, impact); lastWallHaptic = time }
        repeat((impact / 70f).toInt().coerceIn(1, 4)) {
            spawn(x, y, rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 40f - 20f, 0.3f, Palette.STONE_LIGHT)
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
        repeat(16) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 20f + rnd.nextFloat() * 45f
            spawn(x, y, cos(a) * s, sin(a) * s, 0.45f + rnd.nextFloat() * 0.35f, if (it % 3 == 0) Palette.SPARKLE else Palette.FOAM)
        }
        ring(x, y, 0.7f, 10f, Palette.FOAM)
        ring(x, y, 1.0f, 16f, Palette.SPARKLE)
        say("+1 SPLASH", x, y - 6f, Palette.FOAM)
        popT = 0f
        for (i in 0 until tn) tAt[i] = -9f
    }

    override fun onLipOut(x: Float, y: Float, speed: Float) {
        sfx.lipOut(pan(x))
        haptics.lipOut(this)
        say("LIP OUT!", hole.cupX, hole.cupY - 18f, Palette.GOLD)
    }

    override fun onCup(x: Float, y: Float, speed: Float) {
        sinkT = 0f
        val toPar = strokes - hole.par
        newBest = progress.record(holeIndex, strokes)
        sfx.cup(pan(x), toPar, strokes == 1)
        haptics.cup(this, toPar < 0 || strokes == 1)
        ring(hole.cupX, hole.cupY, 0.6f, 8f, Palette.INK)
        val gold = toPar <= 0
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

    private fun say(text: String, x: Float, y: Float, col: Int) {
        msg = text; msgX = x; msgY = y; msgT = 0f; msgCol = col
    }

    // ---------------------------------------------------------------- update

    private fun update(dt: Float) {
        time += dt
        modeT += dt
        if (mode == Mode.PLAY && physics.moving) {
            acc += dt
            var n = 0
            while (acc >= Physics.STEP && n < MAX_STEPS_PER_FRAME) {
                physics.step(this)
                acc -= Physics.STEP
                n++
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
        var lead = 0f
        var leadX = 0f
        if (aiming && power > 0f) {
            val len = max(1f, hypot(downX - curX, downY - curY))
            leadX = (downX - curX) / len * power * 40f
            lead = (downY - curY) / len * power * 40f
        }
        val tx = if (hole.pxW <= lw) -((lw - hole.pxW) / 2).toFloat()
        else (physics.x + leadX - lw / 2f).coerceIn(0f, (hole.pxW - lw).toFloat())
        val ty = if (hole.pxH <= playH) -(playTop + (playH - hole.pxH) / 2).toFloat()
        else (physics.y + lead - playTop - playH / 2f).coerceIn(-playTop.toFloat(), (hole.pxH - playBottom).toFloat())
        if (camSnap) { camX = tx; camY = ty; camSnap = false }
        val k = 1f - exp(-5f * dt)
        camX += (tx - camX) * k
        camY += (ty - camY) * k
        if (kotlin.math.abs(tx - camX) < 0.02f) camX = tx
        if (kotlin.math.abs(ty - camY) < 0.02f) camY = ty
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
        val fx = camX - ix
        val fy = camY - iy
        drawWorld(ix, iy)
        drawUi()

        // Shift by the camera's sub-pixel remainder in whole screen pixels.
        val sx = (offX - fx * scale).roundToInt().toFloat()
        val sy = (offY - fy * scale).roundToInt().toFloat()
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

    private fun px(c: Canvas, x: Int, y: Int, col: Int) {
        p.color = col
        c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, p)
    }

    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, col: Int) {
        p.color = col
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p)
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

        // The cup.
        val cx = hole.cupX.roundToInt() - ix
        val cy = hole.cupY.roundToInt() - iy
        rect(c, cx - 2, cy - 2, 4, 4, Palette.CUP)
        rect(c, cx - 3, cy - 1, 6, 2, Palette.CUP)
        rect(c, cx - 2, cy + 2, 4, 1, Palette.CUP_LIP)

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
            stroke.color = Palette.alpha(rCol[i], (220 * (1f - f)).toInt())
            c.drawCircle(rX[i].roundToInt() - ix + 0.5f, rY[i].roundToInt() - iy + 0.5f, 1f + f * rRad[i], stroke)
        }

        drawFlag(c, cx, cy)
        drawGull(c, ix, iy)

        msg?.let {
            val rise = (msgT * 8f).toInt()
            val a = if (msgT < 1f) 255 else (255 * (1f - (msgT - 1f) / 0.4f)).toInt()
            PixelFont.drawCentered(c, it, msgX.roundToInt() - ix, msgY.roundToInt() - iy - rise,
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
        val x = bx.roundToInt() - 2 - ix
        val y = by.roundToInt() - 2 - iy
        if (popT in 0f..0.12f) return // blink on respawn
        // Shadow, then the ball with a shaded lower right.
        rect(c, x + 2, y + 1, 2, 4, Palette.SHADOW)
        rect(c, x + 1, y + 2, 4, 2, Palette.SHADOW)
        rect(c, x + 1, y, 2, 4, Palette.BALL)
        rect(c, x, y + 1, 4, 2, Palette.BALL)
        px(c, x + 3, y + 2, Palette.BALL_SHADE)
        px(c, x + 2, y + 3, Palette.BALL_SHADE)
        // An idle ball breathes a faint ring so it's easy to find.
        if (mode == Mode.PLAY && !physics.moving && !aiming) {
            val ph = (time * 0.8f) % 1f
            stroke.color = Palette.alpha(Palette.INK, (120 * (1f - ph)).toInt())
            c.drawCircle(bx.roundToInt() - ix + 0.5f, by.roundToInt() - iy + 0.5f, 3f + ph * 6f, stroke)
        }
    }

    private fun drawAim(c: Canvas, ix: Int, iy: Int) {
        val len = max(1f, hypot(downX - curX, downY - curY))
        val ux = (downX - curX) / len
        val uy = (downY - curY) / len
        val n = 2 + (power * 11f).toInt()
        val col = if (power < 0.6f) Palette.lerp(Palette.INK, Palette.GOLD, power / 0.6f)
        else Palette.lerp(Palette.GOLD, Palette.HOT, (power - 0.6f) / 0.4f)
        val full = power >= 1f
        for (i in 0 until n) {
            val d = 5f + i * 4f
            val wx = physics.x + ux * d
            val wy = physics.y + uy * d
            if (hole.at(wx, wy).solid) break
            if (full && ((time * 8f).toInt() + i) % 2 == 0) continue
            val x = wx.roundToInt() - ix
            val y = wy.roundToInt() - iy
            px(c, x, y + 1, Palette.alpha(Palette.INK_SHADOW, 110))
            px(c, x, y, col)
            if (i == n - 1) { px(c, x + 1, y, col); px(c, x - 1, y, col); px(c, x, y - 1, col) }
        }
    }

    private fun drawFlag(c: Canvas, cx: Int, cy: Int) {
        val near = hypot(physics.x - hole.cupX, physics.y - hole.cupY) < 24f && physics.moving
        val up = if (sinkT >= 0f) min(3, (sinkT * 20f).toInt()) else 0
        val top = cy - 15 - up
        // Pole shadow across the green.
        for (i in 1..7) px(c, cx + i, cy - 1 + i / 3, Palette.alpha(Palette.CUP, 70))
        rect(c, cx, top, 1, cy - top, Palette.POLE)
        val wave = ((time * (if (near) 12f else 3f)).toInt()) % 2
        rect(c, cx + 1, top, 5, 1, Palette.FLAG)
        rect(c, cx + 1, top + 1, 6 - wave, 1, Palette.FLAG)
        rect(c, cx + 1, top + 2, 4 + wave, 1, Palette.FLAG_DARK)
        rect(c, cx + 1, top + 3, 2, 1, Palette.FLAG_DARK)
    }

    private fun drawGull(c: Canvas, ix: Int, iy: Int) {
        if (gullT < 0f) return
        val sx = if (gullDir > 0) -20f + gullT * GULL_SPEED else lw + 20f - gullT * GULL_SPEED
        val wx = camX + sx
        val wy = gullY + sin(gullT * 1.3f) * 4f
        val flap = ((gullT * 4f).toInt()) % 2 == 0
        for (pass in 0..1) {
            val x = wx.roundToInt() - ix + if (pass == 0) 7 else 0
            val y = wy.roundToInt() - iy + if (pass == 0) 12 else 0
            val col = if (pass == 0) Palette.alpha(Palette.INK_SHADOW, 60) else Palette.INK
            if (flap) {
                px(c, x - 2, y - 1, col); px(c, x + 2, y - 1, col)
                px(c, x - 1, y, col); px(c, x + 1, y, col); px(c, x, y + 1, col)
            } else {
                rect(c, x - 2, y, 2, 1, col); rect(c, x + 1, y, 2, 1, col); px(c, x, y + 1, col)
            }
        }
    }

    // ---------------------------------------------------------------- UI layer

    private fun drawUi() {
        val c = uc
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        when (mode) {
            Mode.TITLE -> drawTitle(c)
            Mode.PLAY -> { drawHud(c); drawPlayUi(c) }
            Mode.CARD -> { drawHud(c); drawCard(c) }
        }
    }

    private fun drawHud(c: Canvas) {
        val y = insetTop + 3
        rect(c, 0, 0, lw, y + 8, Palette.alpha(Palette.INK_SHADOW, 150))
        rect(c, 0, y + 8, lw, 1, Palette.alpha(Palette.INK_SHADOW, 70))
        PixelFont.draw(c, "HOLE ${holeIndex + 1}", 4, y, Palette.INK, 1, Palette.INK_SHADOW)
        PixelFont.draw(c, "PAR ${hole.par}", 4 + PixelFont.width("HOLE 1") + 8, y, Palette.INK_DIM, 1, Palette.INK_SHADOW)
        val s = "SHOTS $strokes"
        PixelFont.draw(c, s, lw - 4 - PixelFont.width(s), y, if (strokes > hole.par) Palette.HOT else Palette.INK, 1, Palette.INK_SHADOW)
    }

    private fun drawPlayUi(c: Canvas) {
        // Hole banner as the round begins.
        if (modeT < 2.4f && strokes == 0) {
            val a = when {
                modeT < 0.3f -> modeT / 0.3f
                modeT > 1.9f -> 1f - (modeT - 1.9f) / 0.5f
                else -> 1f
            }.coerceIn(0f, 1f)
            val y = playTop + 30
            val ai = (255 * a).toInt()
            rect(c, 0, y - 6, lw, 28, Palette.alpha(Palette.INK_SHADOW, (150 * a).toInt()))
            PixelFont.drawCentered(c, hole.name, lw / 2, y, Palette.alpha(Palette.INK, ai), 1, Palette.alpha(Palette.INK_SHADOW, ai))
            PixelFont.drawCentered(c, "PAR ${hole.par}", lw / 2, y + 9, Palette.alpha(Palette.GOLD, ai), 2, Palette.alpha(Palette.INK_SHADOW, ai))
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
            stroke.color = Palette.alpha(col, 170)
            c.drawCircle(ax + 0.5f, ay + 0.5f, 4f, stroke)
            rect(c, fx.roundToInt() - 1, fy.roundToInt() - 1, 3, 3, Palette.alpha(col, 210))
            // Power readout beside the anchor.
            if (!cancel) {
                val pct = "${(power * 100).roundToInt()}%"
                PixelFont.draw(c, pct, ax + 7, ay - 2, if (power >= 1f) Palette.HOT else Palette.INK, 1, Palette.INK_SHADOW)
            }
        }

        // First-shot coaching.
        if (strokes == 0 && !physics.moving) {
            val y = playBottom - 12
            val pulse = (0.6f + 0.4f * sin(time * 3f)).coerceIn(0f, 1f)
            val a = (255 * pulse * min(1f, modeT / 0.6f)).toInt()
            val text = if (aiming) (if (power > 0f) "LET GO TO SHOOT" else "PULL BACK TO AIM") else "PULL BACK ANYWHERE TO AIM"
            PixelFont.drawCentered(c, text, lw / 2, y, Palette.alpha(Palette.INK, a), 1, Palette.alpha(Palette.INK_SHADOW, a))
            if (aiming && power > 0f) PixelFont.drawCentered(c, "DRAG BACK IN TO CANCEL", lw / 2, y + 8, Palette.alpha(Palette.INK_DIM, a), 1, Palette.alpha(Palette.INK_SHADOW, a))
        }
    }

    private fun button(c: Canvas, r: Rect, text: String, primary: Boolean) {
        rect(c, r.left, r.top + 1, r.width(), r.height(), Palette.INK_SHADOW)
        rect(c, r.left, r.top, r.width(), r.height() - 1, if (primary) Palette.FAIR_B else Palette.PANEL_EDGE)
        rect(c, r.left + 1, r.top, r.width() - 2, 1, Palette.alpha(Palette.INK, 70))
        PixelFont.drawCentered(c, text, r.centerX(), r.top + (r.height() - 1 - PixelFont.H) / 2, Palette.INK, 1, Palette.INK_SHADOW)
    }

    private fun panel(c: Canvas, x: Int, y: Int, w: Int, h: Int) {
        rect(c, x + 1, y, w - 2, h, Palette.PANEL)
        rect(c, x, y + 1, w, h - 2, Palette.PANEL)
        rect(c, x + 1, y, w - 2, 1, Palette.PANEL_EDGE)
        rect(c, x + 1, y + h - 1, w - 2, 1, Palette.PANEL_EDGE)
        rect(c, x, y + 1, 1, h - 2, Palette.PANEL_EDGE)
        rect(c, x + w - 1, y + 1, 1, h - 2, Palette.PANEL_EDGE)
    }

    private fun drawTitle(c: Canvas) {
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 140))
        val a = (255 * min(1f, modeT / 0.5f)).toInt()
        val top = (lh * 0.2f).toInt()
        val bob = (sin(time * 1.2f) * 1.5f).roundToInt()
        PixelFont.drawCentered(c, "GOLF", lw / 2, top + bob, Palette.alpha(Palette.INK, a), 6, Palette.alpha(Palette.INK_SHADOW, a))
        PixelFont.drawCentered(c, "ALONE", lw / 2, top + 36 + bob, Palette.alpha(Palette.GOLD, a), 5, Palette.alpha(Palette.INK_SHADOW, a))
        PixelFont.drawCentered(c, "A QUIET ISLAND COURSE", lw / 2, top + 68, Palette.alpha(Palette.INK_DIM, a), 1, Palette.INK_SHADOW)

        val blink = (time * 1.6f % 1f) < 0.7f
        if (blink) PixelFont.drawCentered(c, "TAP TO PLAY", lw / 2, (lh * 0.6f).toInt(), Palette.INK, 2, Palette.INK_SHADOW)
        val best = progress.best(holeIndex)
        val bestText = if (best == 0) "HOLE 1 * PAR ${hole.par} * NOT PLAYED" else "HOLE 1 * PAR ${hole.par} * BEST $best"
        PixelFont.drawCentered(c, bestText, lw / 2, (lh * 0.6f).toInt() + 20, Palette.INK_DIM, 1, Palette.INK_SHADOW)

        val by = lh - insetBottom - 26
        val bw = 64
        btnA.set(lw / 2 - bw - 4, by, lw / 2 - 4, by + 14)
        btnB.set(lw / 2 + 4, by, lw / 2 + 4 + bw, by + 14)
        button(c, btnA, if (progress.sound) "SOUND ON" else "SOUND OFF", progress.sound)
        button(c, btnB, if (progress.haptics) "BUZZ ON" else "BUZZ OFF", progress.haptics)
    }

    private fun drawCard(c: Canvas) {
        val k = min(1f, cardT / 0.45f)
        val ease = 1f + 2.2f * (k - 1f) * (k - 1f) * (k - 1f) + 1.2f * (k - 1f) * (k - 1f) // ease-out-back
        val w = 148
        val h = 98
        val x = (lw - w) / 2
        val y = ((lh - h) / 2 + (1f - ease) * lh * 0.7f).toInt()
        rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, (110 * k).toInt()))
        panel(c, x, y, w, h)

        val toPar = strokes - hole.par
        val word = when {
            strokes == 1 -> "HOLE IN ONE!"
            toPar <= -3 -> "ALBATROSS!"
            toPar == -2 -> "EAGLE!"
            toPar == -1 -> "BIRDIE!"
            toPar == 0 -> "PAR"
            toPar == 1 -> "BOGEY"
            toPar == 2 -> "DOUBLE BOGEY"
            else -> "+$toPar"
        }
        val good = toPar <= 0
        val ws = if (PixelFont.width(word, 2) <= w - 12) 2 else 1
        PixelFont.drawCentered(c, word, lw / 2, y + 10, if (good) Palette.GOLD else Palette.INK, ws, Palette.INK_SHADOW)

        // Flag earned: white for finishing, gold at or under par, red pennant for a hole-in-one.
        val fx = lw / 2 - 3
        val fy = y + 26
        rect(c, fx, fy, 1, 11, Palette.POLE)
        val fc = when { strokes == 1 -> Palette.FLAG; good -> Palette.GOLD; else -> Palette.INK }
        rect(c, fx + 1, fy, 6, 2, fc); rect(c, fx + 1, fy + 2, 4, 2, fc); rect(c, fx + 1, fy + 4, 2, 1, fc)

        PixelFont.drawCentered(c, "$strokes ${if (strokes == 1) "SHOT" else "SHOTS"} * PAR ${hole.par}", lw / 2, y + 44, Palette.INK, 1, Palette.INK_SHADOW)
        val best = progress.best(holeIndex)
        if (newBest) {
            if ((cardT * 3f % 1f) < 0.75f) PixelFont.drawCentered(c, "NEW BEST!", lw / 2, y + 54, Palette.GOLD, 1, Palette.INK_SHADOW)
        } else PixelFont.drawCentered(c, "BEST $best", lw / 2, y + 54, Palette.INK_DIM, 1, Palette.INK_SHADOW)

        val bw = 60
        val by = y + h - 24
        btnA.set(lw / 2 - bw - 4, by, lw / 2 - 4, by + 15)
        btnB.set(lw / 2 + 4, by, lw / 2 + 4 + bw, by + 15)
        button(c, btnA, "AGAIN", true)
        button(c, btnB, "MENU", false)
    }

    // ---------------------------------------------------------------- tests

    internal val ballX get() = physics.x
    internal val ballY get() = physics.y
    internal val ballMoving get() = physics.moving
    internal val ballInCup get() = physics.inCup
    internal val strokesForTest get() = strokes
    internal val dragRangePx get() = cancelPx to maxDragPx
    internal val logicalSize get() = Triple(lw, lh, scale)
    internal fun buttonCenter(primary: Boolean): Pair<Float, Float> {
        val r = if (primary) btnA else btnB
        return (offX + (r.centerX() + 0.5f) * scale) to (offY + (r.centerY() + 0.5f) * scale)
    }

    companion object {
        const val LOGICAL_W = 180
        const val HUD_H = 12
        const val GULL_SPEED = 26f
        const val MAX_STEPS_PER_FRAME = Physics.HZ / 8
    }
}

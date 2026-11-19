package com.theprobyte.golfalone.game

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Things the simulation reports so the game can play sounds, haptics and particles. */
interface PhysicsEvents {
    fun onWall(x: Float, y: Float, impact: Float) {}
    fun onSand(x: Float, y: Float, speed: Float) {}
    fun onSplash(x: Float, y: Float) {}
    fun onLipOut(x: Float, y: Float, speed: Float) {}
    fun onCup(x: Float, y: Float, speed: Float) {}
    fun onStop(x: Float, y: Float) {}

    object None : PhysicsEvents
}

/**
 * The ball on a [Hole], advanced in fixed [STEP]s so a shot plays out identically at any
 * frame rate (and in unit tests). No randomness and no Android types.
 */
class Physics(val hole: Hole) {

    var x = hole.teeX; private set
    var y = hole.teeY; private set
    var vx = 0f; private set
    var vy = 0f; private set
    /** Where the ball last came to rest: water sends it back here. */
    var restX = x; private set
    var restY = y; private set
    var moving = false; private set
    var inCup = false; private set
    /** Simulated seconds since the shot. */
    var shotTime = 0f; private set

    private var lipped = false
    private var lastTile = hole.at(x, y)
    private var touchedWall = false

    val speed get() = hypot(vx, vy)

    fun reset() {
        x = hole.teeX; y = hole.teeY; restX = x; restY = y
        vx = 0f; vy = 0f; moving = false; inCup = false; lipped = false
    }

    /** Hits the ball towards ([dx], [dy]) (any length) with [power] 0..1. */
    fun shoot(dx: Float, dy: Float, power: Float) {
        val len = hypot(dx, dy)
        if (len < 1e-6f || inCup) return
        val s = launchSpeed(power)
        vx = dx / len * s
        vy = dy / len * s
        restX = x; restY = y
        moving = true
        lipped = false
        shotTime = 0f
        lastTile = hole.at(x, y)
    }

    fun shootAngle(angle: Float, power: Float) = shoot(cos(angle), sin(angle), power)

    /** Advances one fixed step. */
    fun step(ev: PhysicsEvents = PhysicsEvents.None) {
        if (!moving) return
        val dt = STEP
        shotTime += dt
        val tile = hole.at(x, y)

        // Rolling resistance (never reverses the ball), then slope.
        var sp = speed
        if (sp > 0f) {
            val ns = max(0f, sp - (tile.c + tile.k * sp) * dt)
            val f = ns / sp
            vx *= f; vy *= f
        }
        vx += tile.slopeX * SLOPE_G * dt
        vy += tile.slopeY * SLOPE_G * dt

        // Gentle funnel into the cup for slow balls.
        val cdx = hole.cupX - x
        val cdy = hole.cupY - y
        val cd = hypot(cdx, cdy)
        sp = speed
        if (cd < CUP_R + 2.5f && cd > 0.01f && sp < CAPTURE_SPEED) {
            vx += cdx / cd * FUNNEL_G * dt
            vy += cdy / cd * FUNNEL_G * dt
        }

        x += vx * dt
        y += vy * dt

        collideWalls(ev)

        val now = hole.at(x, y)
        if (now.water) {
            ev.onSplash(x, y)
            x = restX; y = restY; vx = 0f; vy = 0f
            moving = false
            lastTile = hole.at(x, y)
            return
        }
        if (now == Tile.SAND && lastTile != Tile.SAND && speed > 15f) ev.onSand(x, y, speed)
        lastTile = now

        // The cup: slow balls drop, fast ones lip out and are flung round the rim.
        val d = hypot(hole.cupX - x, hole.cupY - y)
        if (d < CUP_R) {
            sp = speed
            if (sp < CAPTURE_SPEED) {
                inCup = true; moving = false
                ev.onCup(x, y, sp)
                x = hole.cupX; y = hole.cupY; vx = 0f; vy = 0f
                return
            } else if (!lipped) {
                lipped = true
                val nx = (x - hole.cupX) / max(d, 0.01f)
                val ny = (y - hole.cupY) / max(d, 0.01f)
                val side = if (vx * ny - vy * nx >= 0f) 1f else -1f
                val a = side * (0.35f + 0.5f * (1f - d / CUP_R))
                val ca = cos(a); val sa = sin(a)
                val rx = vx * ca - vy * sa
                val ry = vx * sa + vy * ca
                vx = rx * 0.72f; vy = ry * 0.72f
                ev.onLipOut(x, y, sp)
            }
        } else if (d > CUP_R + 1f) lipped = false

        // Stop when slow and nothing (slope) keeps it going, or after a long roll.
        sp = speed
        val slopePush = SLOPE_G * hypot(tile.slopeX, tile.slopeY)
        if ((sp < STOP_SPEED && (slopePush < tile.c || touchedWall)) || shotTime > MAX_ROLL_S) {
            vx = 0f; vy = 0f; moving = false
            restX = x; restY = y
            ev.onStop(x, y)
        }
    }

    private fun collideWalls(ev: PhysicsEvents) {
        touchedWall = false
        val r = BALL_R
        val tx0 = floor((x - r) / TILE).toInt()
        val tx1 = floor((x + r) / TILE).toInt()
        val ty0 = floor((y - r) / TILE).toInt()
        val ty1 = floor((y + r) / TILE).toInt()
        var maxImpact = 0f
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            if (!hole.tile(tx, ty).solid) continue
            val left = (tx * TILE).toFloat()
            val top = (ty * TILE).toFloat()
            val px = min(max(x, left), left + TILE)
            val py = min(max(y, top), top + TILE)
            var nx = x - px
            var ny = y - py
            val d2 = nx * nx + ny * ny
            if (d2 >= r * r) continue
            val d = sqrt(d2)
            if (d < 1e-4f) { // centre inside the block: push out along the travel direction
                val sp = max(speed, 1e-4f)
                nx = -vx / sp; ny = -vy / sp
                x += nx * r; y += ny * r
            } else {
                nx /= d; ny /= d
                x = px + nx * r
                y = py + ny * r
            }
            touchedWall = true
            val vn = vx * nx + vy * ny
            if (vn < 0f) {
                // Reflect the normal part with restitution, damp the tangential part a touch.
                val tx = vx - vn * nx
                val tyv = vy - vn * ny
                vx = tx * WALL_GRIP - vn * WALL_E * nx
                vy = tyv * WALL_GRIP - vn * WALL_E * ny
                maxImpact = max(maxImpact, -vn)
            }
        }
        if (maxImpact > 6f) ev.onWall(x, y, maxImpact)
    }

    /** Runs the current shot to rest (or the cup). Returns the number of steps taken. */
    fun settle(ev: PhysicsEvents = PhysicsEvents.None, maxSteps: Int = (MAX_ROLL_S / STEP).toInt() + 10): Int {
        var n = 0
        while (moving && n < maxSteps) { step(ev); n++ }
        return n
    }

    /** Copies position and rest state from [o] (for the planner and aim preview). */
    fun copyFrom(o: Physics) {
        x = o.x; y = o.y; vx = o.vx; vy = o.vy; restX = o.restX; restY = o.restY
        moving = o.moving; inCup = o.inCup; lipped = false; shotTime = 0f; lastTile = hole.at(x, y)
    }

    fun placeAt(px: Float, py: Float) {
        x = px; y = py; restX = px; restY = py; vx = 0f; vy = 0f; moving = false; inCup = false
    }

    companion object {
        const val HZ = 240
        const val STEP = 1f / HZ
        const val BALL_R = 2f
        const val CUP_R = 3f
        const val MAX_SPEED = 430f
        const val CAPTURE_SPEED = 150f
        const val SLOPE_G = 70f
        const val FUNNEL_G = 260f
        const val STOP_SPEED = 3f
        const val WALL_E = 0.62f
        const val WALL_GRIP = 0.94f
        const val MAX_ROLL_S = 18f

        /** The softest tap still rolls the ball a few pixels. */
        const val MIN_SPEED = 22f

        /** Power 0..1 to launch speed; eased so short putts get fine control. */
        fun launchSpeed(power: Float): Float =
            MIN_SPEED + (MAX_SPEED - MIN_SPEED) * power.coerceIn(0f, 1f).pow(1.45f)
    }
}

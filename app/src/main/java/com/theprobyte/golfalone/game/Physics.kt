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
    /** [wood] is driftwood or a palm trunk rather than stone. */
    fun onWall(x: Float, y: Float, impact: Float, wood: Boolean) {}
    fun onSand(x: Float, y: Float, speed: Float) {}
    /** In the water at ([x], [y]); the ball is dropped at ([dropX], [dropY]) near where it went in. */
    fun onSplash(x: Float, y: Float, dropX: Float, dropY: Float) {}
    /** A slow ball stopped right on the water's edge. */
    fun onTeeter(x: Float, y: Float) {}
    /** Thrown into the air off a ramp at [speed]. */
    fun onLaunch(x: Float, y: Float, speed: Float) {}
    /** Came down after a flight, hitting the ground at vertical speed [impact]. */
    fun onLand(x: Float, y: Float, impact: Float) {}
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
    /** Height above the ground (px) and vertical speed, while the ball is in the air. */
    var z = 0f; private set
    var vz = 0f; private set
    val airborne get() = z > 0f || vz > 0f
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
        z = 0f; vz = 0f
    }

    /** Hits the ball towards ([dx], [dy]) (any length) with [power] 0..1. */
    fun shoot(dx: Float, dy: Float, power: Float) {
        val len = hypot(dx, dy)
        if (len < 1e-6f || inCup) return
        val s = launchSpeed(power)
        vx = dx / len * s
        vy = dy / len * s
        restX = x; restY = y
        lastDryX = x; lastDryY = y; entryVx = vx; entryVy = vy
        z = 0f; vz = 0f
        moving = true
        lipped = false
        shotTime = 0f
        lastTile = hole.at(x, y)
    }

    fun shootAngle(angle: Float, power: Float) = shoot(cos(angle), sin(angle), power)

    /** Advances one fixed step. */
    fun step(ev: PhysicsEvents = PhysicsEvents.None) {
        if (!moving) return
        if (airborne) { stepAir(ev); return }
        val dt = STEP
        shotTime += dt
        val tile = hole.at(x, y)
        val coast = hole.coast
        val inWater = coast.waterDepth(x, y)
        // The beach's wet band and the very edge drag harder, so long rolls tend to die on the sand.
        val wet = !tile.solid && !tile.isGreen && !tile.isRamp && (inWater > 0f || coast.landDepth(x, y) < WET_BAND)
        val c = if (wet) max(tile.c, WET_C) else tile.c
        val k = if (wet) max(tile.k, WET_K) else tile.k

        // Rolling resistance (never reverses the ball), then slope.
        var sp = speed
        if (sp > 0f) {
            val ns = max(0f, sp - (c + k * sp) * dt)
            val f = ns / sp
            vx *= f; vy *= f
        }
        // Right on the edge, the bank tips the ball back towards land.
        if (inWater > 0f) {
            val gx = coast.waterDepth(x + 1f, y) - coast.waterDepth(x - 1f, y)
            val gy = coast.waterDepth(x, y + 1f) - coast.waterDepth(x, y - 1f)
            val gl = hypot(gx, gy)
            if (gl > 1e-3f) { vx -= gx / gl * LIP_PULL * dt; vy -= gy / gl * LIP_PULL * dt }
        }
        vx += tile.slopeX * SLOPE_G * dt
        vy += tile.slopeY * SLOPE_G * dt
        // A ramp is a steep climb: it slows the ball on the way up and rolls a slow one back.
        vx -= tile.rampX * RAMP_G * dt
        vy -= tile.rampY * RAMP_G * dt

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
        // Off the top of a ramp: into the air. Checked before the water, so a ramp on the shore
        // throws the ball out over the sea rather than dropping it in.
        if (tile.isRamp && now != tile) {
            val ptx = floor((x - vx * dt) / TILE).toInt()
            val pty = floor((y - vy * dt) / TILE).toInt()
            val ntx = floor(x / TILE).toInt()
            val nty = floor(y / TILE).toInt()
            val along = vx * tile.rampX + vy * tile.rampY
            if (ntx - ptx == tile.rampX.toInt() && nty - pty == tile.rampY.toInt() && along > RAMP_MIN) {
                vz = min(along * RAMP_LIFT, MAX_LIFT)
                z = LAUNCH_Z
                lastTile = now
                ev.onLaunch(x, y, along)
                return
            }
        }
        // Water is the drawn sea, and the whole ball has to be in before it counts.
        val depth = coast.waterDepth(x, y)
        if (depth >= BALL_R) {
            val (dx, dy) = dropPoint()
            ev.onSplash(x, y, dx, dy)
            x = dx; y = dy; vx = 0f; vy = 0f
            restX = x; restY = y
            moving = false
            lastTile = hole.at(x, y)
            return
        }
        if (depth > 0f && speed < TEETER_SPEED && headingDeeper()) {
            // Teetering on the edge: it stays out, settling back on the last dry spot (on top of
            // the bank, so it's never drawn on the cliff face, and a soft shot from here moves).
            vx = 0f; vy = 0f
            if (coast.isLand(lastDryX, lastDryY)) { x = lastDryX; y = lastDryY }
            // On a ramp's lip there's nowhere to rest: it simply rolls back down.
            if (hole.at(x, y).isRamp) return
            moving = false
            restX = x; restY = y
            ev.onTeeter(x, y)
            return
        }
        if (depth <= 0f) { lastDryX = x; lastDryY = y; entryVx = vx; entryVy = vy }
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
        val slopePush = SLOPE_G * hypot(tile.slopeX, tile.slopeY) + RAMP_G * hypot(tile.rampX, tile.rampY)
        if ((sp < STOP_SPEED && (slopePush < c || touchedWall)) || shotTime > MAX_ROLL_S) {
            vx = 0f; vy = 0f; moving = false
            restX = x; restY = y
            ev.onStop(x, y)
        }
    }

    /** Whether the ball is moving towards deeper water (down the coast's depth gradient). */
    private fun headingDeeper(): Boolean {
        val coast = hole.coast
        val gx = coast.waterDepth(x + 1f, y) - coast.waterDepth(x - 1f, y)
        val gy = coast.waterDepth(x, y + 1f) - coast.waterDepth(x, y - 1f)
        return vx * gx + vy * gy > 0f
    }

    private var lastDryX = x
    private var lastDryY = y
    private var entryVx = 0f
    private var entryVy = 0f

    /**
     * Where a ball that went in is dropped: on dry land, a little back along its path from
     * the last point it was dry (golf's penalty-area relief), never in a wall or on a trunk.
     */
    private fun dropPoint(): Pair<Float, Float> {
        val coast = hole.coast
        val sp = hypot(entryVx, entryVy)
        val ux = if (sp > 1e-3f) entryVx / sp else 0f
        val uy = if (sp > 1e-3f) entryVy / sp else 0f
        var k = 0f
        while (k <= 32f) {
            val px = lastDryX - ux * k
            val py = lastDryY - uy * k
            val t = hole.at(px, py)
            if (coast.landDepth(px, py) >= BALL_R + 1f && !t.solid && !t.isRamp && clearOfPosts(px, py)) return px to py
            k += 1f
        }
        return if (coast.isLand(lastDryX, lastDryY)) lastDryX to lastDryY else restX to restY
    }

    private fun clearOfPosts(px: Float, py: Float): Boolean {
        val p = hole.posts
        var i = 0
        while (i < p.size) {
            if (hypot(px - p[i], py - p[i + 1]) < BALL_R + Hole.POST_R + 0.5f) return false
            i += 2
        }
        return true
    }

    private var impactWood = false

    /** Reflects the normal part with restitution [e] and damps the tangential part a touch. */
    private fun bounce(nx: Float, ny: Float, vn: Float, e: Float) {
        val tx = vx - vn * nx
        val ty = vy - vn * ny
        vx = tx * WALL_GRIP - vn * e * nx
        vy = ty * WALL_GRIP - vn * e * ny
    }

    /** In the air: gravity only. Taller things still block; low walls and logs pass beneath. */
    private fun stepAir(ev: PhysicsEvents) {
        val dt = STEP
        shotTime += dt
        vz -= GRAVITY * dt
        x += vx * dt
        y += vy * dt
        z += vz * dt
        collideWalls(ev, max(z, 0f))
        if (z <= 0f && insideSolid(x, y)) {
            // Came down on top of a wall, log or trunk: its top is ground that bounces the ball
            // on, so it hops along and drops off the far side rather than ending up inside.
            z = 0.5f
            vz = max(-vz * LAND_BOUNCE, HOP_VZ)
            val sp = hypot(vx, vy)
            if (sp < HOP_MIN) {
                val ux = if (sp > 1e-3f) vx / sp else 0f
                val uy = if (sp > 1e-3f) vy / sp else -1f
                vx = ux * HOP_MIN; vy = uy * HOP_MIN
            }
            return
        }
        if (z <= 0f) {
            val impact = -vz
            z = 0f
            ev.onLand(x, y, impact)
            // Landing takes some pace off; sand plugs the ball.
            val keep = if (hole.at(x, y) == Tile.SAND) SAND_PLUG else LAND_KEEP
            vx *= keep; vy *= keep
            if (impact > BOUNCE_MIN) { vz = impact * LAND_BOUNCE; z = 0.001f } else vz = 0f
            lastTile = hole.at(x, y)
        }
        if (shotTime > MAX_ROLL_S) { vx = 0f; vy = 0f; vz = 0f; z = 0f; moving = false; restX = x; restY = y; ev.onStop(x, y) }
    }

    /** Whether ([px], [py]) is inside a wall block, a rail or a palm trunk. */
    private fun insideSolid(px: Float, py: Float): Boolean {
        val tx = floor(px / TILE).toInt()
        val ty = floor(py / TILE).toInt()
        val t = hole.tile(tx, ty)
        if (t == Tile.WALL && !hole.rails.isBoulder(tx, ty)) return true
        val rails = hole.rails
        for (i in rails.near(tx, ty)) if (rails.distance(i, px, py) < rails.r[i] + 0.5f) return true
        val p = hole.posts
        var i = 0
        while (i < p.size) { if (hypot(px - p[i], py - p[i + 1]) < Hole.POST_R + 0.5f) return true; i += 2 }
        return false
    }

    private fun collideWalls(ev: PhysicsEvents, height: Float = 0f) {
        touchedWall = false
        impactWood = false
        val r = BALL_R
        val tx0 = floor((x - r) / TILE).toInt()
        val tx1 = floor((x + r) / TILE).toInt()
        val ty0 = floor((y - r) / TILE).toInt()
        val ty1 = floor((y + r) / TILE).toInt()
        var maxImpact = 0f
        val rails = hole.rails
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            // Driftwood and boulders collide as rails (below), not as square tiles.
            val tt = hole.tile(tx, ty)
            if (!tt.solid || tt == Tile.WOOD || rails.isBoulder(tx, ty) || height >= WALL_H) continue
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
                bounce(nx, ny, vn, hole.tile(tx, ty).e)
                if (-vn > maxImpact) { maxImpact = -vn; impactWood = hole.tile(tx, ty) == Tile.WOOD }
            }
        }
        // Rails: logs, diagonal wall joins and boulders.
        val ctx = floor(x / TILE).toInt()
        val cty = floor(y / TILE).toInt()
        for (i in rails.near(ctx, cty)) {
            val railH = when (rails.kind[i]) { Rails.WOOD -> WOOD_H; Rails.BOULDER -> BOULDER_H; else -> WALL_H }
            if (height >= railH) continue
            val t = rails.along(i, x, y)
            val px = rails.x0[i] + (rails.x1[i] - rails.x0[i]) * t
            val py = rails.y0[i] + (rails.y1[i] - rails.y0[i]) * t
            val dx = x - px
            val dy = y - py
            val rr = r + rails.r[i]
            val d2 = dx * dx + dy * dy
            if (d2 >= rr * rr) continue
            val d = sqrt(d2)
            val nx: Float
            val ny: Float
            if (d < 1e-4f) { val sp = max(speed, 1e-4f); nx = -vx / sp; ny = -vy / sp } else { nx = dx / d; ny = dy / d }
            x = px + nx * rr
            y = py + ny * rr
            touchedWall = true
            val vn = vx * nx + vy * ny
            if (vn < 0f) {
                val wood = rails.kind[i] == Rails.WOOD
                bounce(nx, ny, vn, if (wood) Tile.WOOD.e else Tile.WALL.e)
                if (-vn > maxImpact) { maxImpact = -vn; impactWood = wood }
            }
        }
        // Palm trunks: round posts.
        val posts = hole.posts
        var i = if (height >= POST_H) posts.size else 0
        while (i < posts.size) {
            val dx = x - posts[i]
            val dy = y - posts[i + 1]
            i += 2
            val rr = r + Hole.POST_R
            val d2 = dx * dx + dy * dy
            if (d2 >= rr * rr || d2 < 1e-6f) continue
            val d = sqrt(d2)
            val nx = dx / d
            val ny = dy / d
            x = posts[i - 2] + nx * rr
            y = posts[i - 1] + ny * rr
            touchedWall = true
            val vn = vx * nx + vy * ny
            if (vn < 0f) {
                bounce(nx, ny, vn, POST_E)
                if (-vn > maxImpact) { maxImpact = -vn; impactWood = true }
            }
        }
        if (maxImpact > 6f) ev.onWall(x, y, maxImpact, impactWood)
    }

    /** Runs the current shot to rest (or the cup). Returns the number of steps taken. */
    fun settle(ev: PhysicsEvents = PhysicsEvents.None, maxSteps: Int = (MAX_ROLL_S / STEP).toInt() + 10): Int {
        var n = 0
        while (moving && n < maxSteps) { step(ev); n++ }
        return n
    }

    /** Copies position and rest state from [o] (for the planner and aim preview). */
    fun copyFrom(o: Physics) {
        x = o.x; y = o.y; vx = o.vx; vy = o.vy; restX = o.restX; restY = o.restY; z = o.z; vz = o.vz
        moving = o.moving; inCup = o.inCup; lipped = false; shotTime = 0f; lastTile = hole.at(x, y)
    }

    fun placeAt(px: Float, py: Float) {
        x = px; y = py; restX = px; restY = py; vx = 0f; vy = 0f; moving = false; inCup = false
        z = 0f; vz = 0f
    }

    companion object {
        const val HZ = 240
        const val STEP = 1f / HZ
        const val BALL_R = 2.5f
        const val CUP_R = 3f
        const val MAX_SPEED = 430f
        const val CAPTURE_SPEED = 150f
        const val SLOPE_G = 70f
        const val FUNNEL_G = 260f
        const val STOP_SPEED = 3f
        const val POST_E = 0.55f
        /** Below this speed a ball reaching the water's edge stops on it instead of going in. */
        const val TEETER_SPEED = 40f
        const val LIP_PULL = 160f
        /** The wet band along the shore (px from the water) and its drag. */
        const val WET_BAND = 3f
        const val WET_C = 220f
        const val WET_K = 3.5f

        // Ramps and flight.
        const val RAMP_G = 150f
        /** Speed up the ramp needed to leave the lip (anything slower rolls back). */
        const val RAMP_MIN = 25f
        const val RAMP_LIFT = 0.55f
        /** Caps a ramp's throw at about 19 px high and a few tiles long. */
        const val MAX_LIFT = 150f
        const val LAUNCH_Z = 3f
        const val GRAVITY = 600f
        const val LAND_KEEP = 0.82f
        const val SAND_PLUG = 0.25f
        const val LAND_BOUNCE = 0.3f
        const val BOUNCE_MIN = 80f
        /** Heights (px) of what the ball can fly over. */
        const val WALL_H = 7f
        const val WOOD_H = 5f
        const val BOULDER_H = 6f
        const val POST_H = 14f
        /** A ball landing on top of a solid hops on at least this fast. */
        const val HOP_VZ = 60f
        const val HOP_MIN = 40f
        const val WALL_GRIP = 0.94f
        const val MAX_ROLL_S = 18f

        /** The softest tap still rolls the ball a few pixels. */
        const val MIN_SPEED = 22f

        /** Power 0..1 to launch speed; eased so short putts get fine control. */
        fun launchSpeed(power: Float): Float =
            MIN_SPEED + (MAX_SPEED - MIN_SPEED) * power.coerceIn(0f, 1f).pow(1.45f)
    }
}

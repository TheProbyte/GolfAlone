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
    /** Kicked off a bumper buoy. */
    fun onBumper(x: Float, y: Float) {}
    /** Smashed a cracked plank open. */
    fun onBreak(x: Float, y: Float) {}
    /** Knocked a barrel at [impact], or a barrel sank. */
    fun onBarrel(x: Float, y: Float, impact: Float) {}
    fun onBarrelSink(x: Float, y: Float) {}
    /** Thrown up by a steam vent. */
    fun onVent(x: Float, y: Float) {}
    /** Rolled over a switch plate; [opened] if it opened its gates just now. */
    fun onPlate(x: Float, y: Float, opened: Boolean) {}
    /** Cracked thin ice (it opens after this shot). */
    fun onCrack(x: Float, y: Float) {}
    /** Into a tunnel's mouth at ([x0], [y0]) and out at ([x1], [y1]). */
    fun onTunnel(x0: Float, y0: Float, x1: Float, y1: Float) {}

    object None : PhysicsEvents
}

/**
 * The ball on a [Hole], advanced in fixed [STEP]s so a shot plays out identically at any
 * frame rate (and in unit tests). No randomness and no Android types.
 */
class Physics(val hole: Hole, val state: HoleState = HoleState(hole)) {

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
    /** The ball itself has stopped, but the shot goes on while barrels still roll. */
    private var ballStill = false
    private var kicks = 0
    /** The vent tile that last threw the ball (so it doesn't throw it again as it lands), or -1. */
    private var ventFrom = -1

    /** The ball's part of the shot is over; the shot ends once nothing else is moving. */
    private fun endBall() {
        ballStill = true
        if (!state.barrelsMoving) moving = false
    }

    val speed get() = hypot(vx, vy)

    fun reset() {
        x = hole.teeX; y = hole.teeY; restX = x; restY = y
        vx = 0f; vy = 0f; moving = false; inCup = false; lipped = false
        z = 0f; vz = 0f; ballStill = false
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
        ballStill = false
        kicks = 0
        ventFrom = -1
        lipped = false
        shotTime = 0f
        lastTile = hole.at(x, y)
    }

    fun shootAngle(angle: Float, power: Float) = shoot(cos(angle), sin(angle), power)

    /** Advances one fixed step. */
    fun step(ev: PhysicsEvents = PhysicsEvents.None) {
        if (!moving) return
        if (ballStill) {
            shotTime += STEP
            stepBarrels(ev)
            if (!state.barrelsMoving || shotTime > MAX_ROLL_S) { stopBarrels(); moving = false }
            return
        }
        if (airborne) { stepAir(ev); stepBarrels(ev); return }
        stepGround(ev)
        stepBarrels(ev)
    }

    private fun stepGround(ev: PhysicsEvents) {
        val dt = STEP
        shotTime += dt
        val tile = hole.at(x, y)
        val coast = state.coast
        val inWater = coast.waterDepth(x, y)
        // The beach's wet band and the very edge drag harder, so long rolls tend to die on the sand.
        // Shallows, decks and mud have their own feel right up to the edge.
        val wet = !tile.solid && !tile.isGreen && !tile.isRamp && !tile.shallow && tile != Tile.DECK && tile != Tile.MUD &&
            (inWater > 0f || coast.landDepth(x, y) < WET_BAND)
        val c = if (wet) max(tile.c, WET_C) else tile.c
        val k = if (wet) max(tile.k, WET_K) else tile.k

        // Rolling resistance (never reverses the ball), then slope.
        var sp = speed
        if (sp > 0f) {
            val ns = max(0f, sp - (c + k * sp) * dt)
            val f = ns / sp
            vx *= f; vy *= f
        }
        // Right on the edge, the bank tips the ball back towards land (a current carries it on).
        if (inWater > 0f && !tile.flows) {
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
        // A ledge's lip tips the ball over; the wind leans on it up on the ridges.
        if (tile.isLedge) { vx += tile.dropX * LEDGE_G * dt; vy += tile.dropY * LEDGE_G * dt }
        if (tile.windy) { vx += hole.windX * WIND_G * dt; vy += hole.windY * WIND_G * dt }
        // A current draws the ball towards its own drift.
        if (tile.flows) {
            vx += (tile.flowX * CURRENT_V - vx) * CURRENT_K * dt
            vy += (tile.flowY * CURRENT_V - vy) * CURRENT_K * dt
        }

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
            endBall()
            lastTile = hole.at(x, y)
            return
        }
        // Thin ice that has given way: in once the whole ball is over the hole.
        if (hole.thinCount > 0 && state.thinOpenAt(x, y) && overOpenIce()) {
            val (dx, dy) = dropPoint()
            ev.onSplash(x, y, dx, dy)
            x = dx; y = dy; vx = 0f; vy = 0f
            restX = x; restY = y
            endBall()
            lastTile = hole.at(x, y)
            return
        }
        if (depth > 0f && speed < TEETER_SPEED && !tile.flows && headingDeeper()) {
            // Teetering on the edge: it stays out, settling back on the last dry spot (on top of
            // the bank, so it's never drawn on the cliff face, and a soft shot from here moves).
            vx = 0f; vy = 0f
            if (coast.isLand(lastDryX, lastDryY)) { x = lastDryX; y = lastDryY }
            // On a ramp's lip there's nowhere to rest: it simply rolls back down.
            if (hole.at(x, y).isRamp) return
            endBall()
            restX = x; restY = y
            ev.onTeeter(x, y)
            return
        }
        if (depth <= 0f) { lastDryX = x; lastDryY = y; entryVx = vx; entryVy = vy }
        if (now == Tile.SAND && lastTile != Tile.SAND && speed > 15f) ev.onSand(x, y, speed)
        // Mud swallows a ball rolling into it.
        if (now == Tile.MUD && lastTile != Tile.MUD) {
            vx *= MUD_PLUG; vy *= MUD_PLUG
            if (speed > 15f) ev.onSand(x, y, speed)
        }
        if (now != lastTile && specialTile(now, ev)) return
        // Every tile of thin ice the ball rolls over cracks.
        if (now == Tile.THIN_ICE) crack(floor(x / TILE).toInt(), floor(y / TILE).toInt(), ev)
        lastTile = now

        // The cup: slow balls drop, fast ones lip out and are flung round the rim.
        val d = hypot(hole.cupX - x, hole.cupY - y)
        if (d < CUP_R) {
            sp = speed
            if (sp < CAPTURE_SPEED) {
                inCup = true; endBall()
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
        val slopePush = SLOPE_G * hypot(tile.slopeX, tile.slopeY) + RAMP_G * hypot(tile.rampX, tile.rampY) +
            (if (tile.isLedge) LEDGE_G else 0f) + (if (tile.windy) WIND_G else 0f)
        if ((sp < STOP_SPEED && (slopePush < c || touchedWall) && !tile.flows) || shotTime > MAX_ROLL_S) {
            vx = 0f; vy = 0f; endBall()
            restX = x; restY = y
            ev.onStop(x, y)
        }
    }

    /**
     * Rolling onto a vent, plate, tunnel mouth, thin ice or snow (the tile changed this step).
     * Returns true if the step is over (the ball was thrown up or sent through a tunnel).
     */
    private fun specialTile(now: Tile, ev: PhysicsEvents): Boolean {
        val tx = floor(x / TILE).toInt()
        val ty = floor(y / TILE).toInt()
        val idx = ty * hole.w + tx
        when (now) {
            Tile.VENT -> if (idx != ventFrom) { ventLaunch(idx, ev); lastTile = now; return true }
            Tile.PLATE -> {
                val g = hole.plateAt(tx, ty)
                val opens = g >= 0 && !state.gates[g]
                if (opens) state.gates[g] = true
                ev.onPlate((tx + 0.5f) * TILE, (ty + 0.5f) * TILE, opens)
            }
            Tile.TUNNEL_IN -> {
                val k = hole.tunnelOf[idx]
                val sp = max(speed, TUNNEL_MIN)
                val x0 = x
                val y0 = y
                x = hole.exitX[k] + hole.exitDX[k] * 2f
                y = hole.exitY[k] + hole.exitDY[k] * 2f
                vx = hole.exitDX[k] * sp; vy = hole.exitDY[k] * sp
                lastDryX = x; lastDryY = y; entryVx = vx; entryVy = vy
                lastTile = Tile.TUNNEL_OUT
                ev.onTunnel(x0, y0, x, y)
                return true
            }
            Tile.SNOW -> { vx *= SNOW_PLUG; vy *= SNOW_PLUG; if (speed > 5f) ev.onSand(x, y, speed) }
            else -> {}
        }
        if (!now.vent) ventFrom = -1
        return false
    }

    private fun crack(tx: Int, ty: Int, ev: PhysicsEvents) {
        val k = hole.thinAt(tx, ty)
        if (k >= 0 && !state.cracked[k] && !state.thinOpen[k]) { state.cracked[k] = true; ev.onCrack((tx + 0.5f) * TILE, (ty + 0.5f) * TILE) }
    }

    /** A vent throws the ball up and forward (at least [VENT_MIN_V], so even a soft ball clears a seam). */
    private fun ventLaunch(idx: Int, ev: PhysicsEvents) {
        val sp = speed
        if (sp < 1e-3f) return
        val hs = max(sp, VENT_MIN_V)
        vx = vx / sp * hs; vy = vy / sp * hs
        vz = VENT_LIFT; z = LAUNCH_Z
        ventFrom = idx
        ev.onVent(x, y)
    }

    /** Whether the whole ball is over open thin ice (or water). */
    private fun overOpenIce(): Boolean {
        val r = BALL_R
        fun open(px: Float, py: Float) = hole.at(px, py).water || state.thinOpenAt(px, py)
        return open(x + r, y) && open(x - r, y) && open(x, y + r) && open(x, y - r)
    }

    /** Whether the ball is moving towards deeper water (down the coast's depth gradient). */
    private fun headingDeeper(): Boolean {
        val coast = state.coast
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
        val coast = state.coast
        val sp = hypot(entryVx, entryVy)
        val ux = if (sp > 1e-3f) entryVx / sp else 0f
        val uy = if (sp > 1e-3f) entryVy / sp else 0f
        var k = 0f
        while (k <= 32f) {
            val px = lastDryX - ux * k
            val py = lastDryY - uy * k
            val t = hole.at(px, py)
            if (coast.landDepth(px, py) >= BALL_R + 1f && !t.solid && !t.isRamp && !t.flows && !t.vent && !t.isLedge &&
                !nearOpenIce(px, py) && clearOfPosts(px, py)) return px to py
            k += 1f
        }
        // A current carried it in (no dry ground along its path): back to where the shot was played.
        if (hole.at(lastDryX, lastDryY).flows) return restX to restY
        return if (coast.isLand(lastDryX, lastDryY)) lastDryX to lastDryY else restX to restY
    }

    private fun clearOfPosts(px: Float, py: Float): Boolean {
        val p = hole.posts
        var i = 0
        while (i < p.size) {
            if (hypot(px - p[i], py - p[i + 1]) < BALL_R + Hole.POST_R + 0.5f) return false
            i += 2
        }
        return clearOfBodies(px, py, 0.5f)
    }

    /** Whether open thin ice is under any part of a ball at ([px], [py]). */
    private fun nearOpenIce(px: Float, py: Float): Boolean {
        if (hole.thinCount == 0) return false
        val r = BALL_R
        return state.thinOpenAt(px, py) || state.thinOpenAt(px + r, py) || state.thinOpenAt(px - r, py) ||
            state.thinOpenAt(px, py + r) || state.thinOpenAt(px, py - r)
    }

    /** Clear of corals, buoys, crabs and barrels by [gap]. */
    private fun clearOfBodies(px: Float, py: Float, gap: Float): Boolean {
        fun clear(a: FloatArray, r: Float): Boolean {
            var i = 0
            while (i < a.size) { if (hypot(px - a[i], py - a[i + 1]) < BALL_R + r + gap) return false; i += 2 }
            return true
        }
        if (!clear(hole.corals, Hole.CORAL_R) || !clear(hole.buoys, Hole.BUOY_R)) return false
        for (c in hole.crabs.indices) if (hypot(px - state.crabX(c), py - state.crabY(c)) < BALL_R + Hole.CRAB_R + gap) return false
        for (b in state.bx.indices) if (!state.sunk[b] && hypot(px - state.bx[b], py - state.by[b]) < BALL_R + Hole.BARREL_R + gap) return false
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
        val prevZ = z
        vz -= GRAVITY * dt
        x += vx * dt
        y += vy * dt
        z += vz * dt
        // Coming down onto a wall, log, boulder or trunk: its top is a floor at its height,
        // so the ball hops along it and drops off the edge (no rattling inside the block).
        val floorH = solidHeightUnder(x, y)
        if (floorH > 0f && z < floorH && prevZ >= floorH - 0.5f) {
            z = floorH
            vz = max(-vz * LAND_BOUNCE, HOP_VZ)
            val sp = hypot(vx, vy)
            if (sp < HOP_MIN) {
                val ux = if (sp > 1e-3f) vx / sp else 0f
                val uy = if (sp > 1e-3f) vy / sp else -1f
                vx = ux * HOP_MIN; vy = uy * HOP_MIN
            }
            collideWalls(ev, floorH)
            return
        }
        collideWalls(ev, max(z, 0f))
        if (z <= 0f && state.coast.waterDepth(x, y) >= BALL_R) {
            // Came down in the water: no bounce, no thump. The ground step splashes it.
            z = 0f; vz = 0f
            return
        }
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
            // Coming down on another vent: thrown on again.
            val ltx = floor(x / TILE).toInt()
            val lty = floor(y / TILE).toInt()
            if (lastTile.vent && lty * hole.w + ltx != ventFrom) ventLaunch(lty * hole.w + ltx, ev)
            else if (lastTile == Tile.THIN_ICE) crack(ltx, lty, ev)
        }
        if (shotTime > MAX_ROLL_S) { vx = 0f; vy = 0f; vz = 0f; z = 0f; endBall(); restX = x; restY = y; ev.onStop(x, y) }
    }

    /** Height of the tallest solid whose footprint holds ([px], [py]), or 0. */
    private fun solidHeightUnder(px: Float, py: Float): Float {
        val tx = floor(px / TILE).toInt()
        val ty = floor(py / TILE).toInt()
        var h = 0f
        val t = hole.tile(tx, ty)
        if (t == Tile.WALL && !hole.rails.isBoulder(tx, ty)) h = WALL_H
        else if (t == Tile.HULL) h = WALL_H
        else if (t == Tile.PLANK && !state.plankBroken(tx, ty)) h = WOOD_H
        else if (t == Tile.GATE && !state.gateOpen(tx, ty)) h = WALL_H
        val rails = hole.rails
        for (i in rails.near(tx, ty)) {
            if (rails.distance(i, px, py) < rails.r[i]) {
                h = max(h, when (rails.kind[i]) { Rails.WOOD -> WOOD_H; Rails.BOULDER -> BOULDER_H; else -> WALL_H })
            }
        }
        val p = hole.posts
        var i = 0
        while (i < p.size) { if (hypot(px - p[i], py - p[i + 1]) < Hole.POST_R) h = max(h, POST_H); i += 2 }
        return h
    }

    /** Whether ([px], [py]) is inside a wall block, a rail or a palm trunk. */
    private fun insideSolid(px: Float, py: Float): Boolean {
        val tx = floor(px / TILE).toInt()
        val ty = floor(py / TILE).toInt()
        val t = hole.tile(tx, ty)
        if (t == Tile.WALL && !hole.rails.isBoulder(tx, ty)) return true
        if (t == Tile.HULL || (t == Tile.PLANK && !state.plankBroken(tx, ty))) return true
        if (t == Tile.GATE && !state.gateOpen(tx, ty)) return true
        if (!clearOfBodies(px, py, 0.5f - BALL_R)) return true
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
            if (tt.isLedge) { if (height < WALL_H) hitLedge(tx, ty, tt, ev); continue }
            if (!tt.solid || tt == Tile.WOOD || rails.isBoulder(tx, ty) || height >= WALL_H) continue
            if (tt == Tile.PLANK) { if (height < WOOD_H) hitPlank(tx, ty, ev); continue }
            if (tt == Tile.GATE && state.gateOpen(tx, ty)) continue
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
                if (-vn > maxImpact) { maxImpact = -vn; impactWood = hole.tile(tx, ty).wooden }
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
        collideBodies(ev, height)
        if (maxImpact > 6f) ev.onWall(x, y, maxImpact, impactWood)
    }

    /** A cracked plank: a hard enough hit smashes it (the ball runs on, slowed); otherwise it bounces. */
    private fun hitPlank(tx: Int, ty: Int, ev: PhysicsEvents) {
        if (state.plankBroken(tx, ty)) return
        val r = BALL_R
        val left = (tx * TILE).toFloat()
        val top = (ty * TILE).toFloat()
        val px = min(max(x, left), left + TILE)
        val py = min(max(y, top), top + TILE)
        var nx = x - px
        var ny = y - py
        val d2 = nx * nx + ny * ny
        if (d2 >= r * r) return
        val d = sqrt(d2)
        if (d < 1e-4f) { val sp = max(speed, 1e-4f); nx = -vx / sp; ny = -vy / sp } else { nx /= d; ny /= d }
        val vn = vx * nx + vy * ny
        if (-vn > PLANK_BREAK) {
            state.broken[hole.plankAt(tx, ty)] = true
            vx *= PLANK_KEEP; vy *= PLANK_KEEP
            ev.onBreak((tx + 0.5f) * TILE, (ty + 0.5f) * TILE)
            return
        }
        x = px + nx * r
        y = py + ny * r
        touchedWall = true
        if (vn < 0f) {
            bounce(nx, ny, vn, Tile.PLANK.e)
            if (-vn > 6f) ev.onWall(x, y, -vn, true)
        }
    }

    /** A ledge seen from below is a cliff: solid only for a ball on its downhill side. */
    private fun hitLedge(tx: Int, ty: Int, t: Tile, ev: PhysicsEvents) {
        val left = (tx * TILE).toFloat()
        val top = (ty * TILE).toFloat()
        val below = when {
            t.dropY > 0f -> y > top + TILE
            t.dropY < 0f -> y < top
            t.dropX > 0f -> x > left + TILE
            else -> x < left
        }
        if (!below) return
        val r = BALL_R
        val px = min(max(x, left), left + TILE)
        val py = min(max(y, top), top + TILE)
        var nx = x - px
        var ny = y - py
        val d2 = nx * nx + ny * ny
        if (d2 >= r * r) return
        val d = sqrt(d2)
        if (d < 1e-4f) { nx = t.dropX; ny = t.dropY } else { nx /= d; ny /= d }
        x = px + nx * r
        y = py + ny * r
        touchedWall = true
        val vn = vx * nx + vy * ny
        if (vn < 0f) {
            bounce(nx, ny, vn, LEDGE_E)
            if (-vn > 6f) ev.onWall(x, y, -vn, false)
        }
    }

    /** Corals, buoys, crabs and barrels: round bodies. */
    private fun collideBodies(ev: PhysicsEvents, height: Float) {
        if (height < CORAL_H) circles(hole.corals, Hole.CORAL_R) { nx, ny, vn ->
            bounce(nx, ny, vn, CORAL_E)
            if (-vn > 6f) ev.onWall(x, y, -vn, false)
        }
        if (height < BUOY_H) circles(hole.buoys, Hole.BUOY_R) { nx, ny, vn ->
            // A bumper: it sends the ball off faster than it came, a few times a shot at most.
            val kick = if (kicks < MAX_KICKS) BUOY_KICK else 0f
            kicks++
            val out = -vn * BUOY_E + kick
            val tx = vx - vn * nx
            val ty = vy - vn * ny
            vx = tx + out * nx
            vy = ty + out * ny
            val sp = speed
            if (sp > MAX_SPEED) { vx *= MAX_SPEED / sp; vy *= MAX_SPEED / sp }
            ev.onBumper(x, y)
        }
        if (height < CRAB_H) for (c in hole.crabs.indices) circle(state.crabX(c), state.crabY(c), Hole.CRAB_R) { nx, ny, vn ->
            bounce(nx, ny, vn, CRAB_E)
            if (-vn > 6f) ev.onWall(x, y, -vn, false)
        }
        if (height < MILL_H) for (m in hole.windmills) {
            circle(m.hubX, m.hubY, MILL_HUB_R) { nx, ny, vn -> bounce(nx, ny, vn, 0.5f); if (-vn > 6f) ev.onWall(x, y, -vn, true) }
            // Each blade sweeps round the hub: bounce off it, taking on the blade's own speed.
            val a0 = m.angle(shotTime)
            for (k in 0 until m.blades) {
                val a = a0 + k * (2f * Math.PI.toFloat() / m.blades)
                val ex = m.hubX + cos(a) * m.length
                val ey = m.hubY + sin(a) * m.length
                val sx = ex - m.hubX
                val sy = ey - m.hubY
                val t = (((x - m.hubX) * sx + (y - m.hubY) * sy) / (sx * sx + sy * sy)).coerceIn(0f, 1f)
                val px = m.hubX + sx * t
                val py = m.hubY + sy * t
                val dx = x - px
                val dy = y - py
                val rr = BALL_R + MILL_BLADE_R
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr || d2 < 1e-6f) continue
                val d = sqrt(d2)
                val nx = dx / d
                val ny = dy / d
                x = px + nx * rr; y = py + ny * rr
                touchedWall = true
                val bvx = -m.omega * (py - m.hubY)
                val bvy = m.omega * (px - m.hubX)
                val rvn = (vx - bvx) * nx + (vy - bvy) * ny
                if (rvn < 0f) {
                    vx -= (1f + MILL_E) * rvn * nx; vy -= (1f + MILL_E) * rvn * ny
                    if (-rvn > 6f) ev.onWall(x, y, -rvn, true)
                }
            }
        }
        if (height < BARREL_H) for (b in state.bx.indices) {
            if (state.sunk[b]) continue
            val dx = x - state.bx[b]
            val dy = y - state.by[b]
            val rr = BALL_R + Hole.BARREL_R
            val d2 = dx * dx + dy * dy
            if (d2 >= rr * rr || d2 < 1e-6f) continue
            val d = sqrt(d2)
            val nx = dx / d
            val ny = dy / d
            // Push apart by mass, then trade momentum along the line between them.
            val over = rr - d
            val wb = 1f / (1f + BARREL_M)
            x += nx * over * (1f - wb); y += ny * over * (1f - wb)
            state.bx[b] -= nx * over * wb; state.by[b] -= ny * over * wb
            val rel = (vx - state.bvx[b]) * nx + (vy - state.bvy[b]) * ny
            if (rel < 0f) {
                val j = -(1f + BARREL_E) * rel / (1f + 1f / BARREL_M)
                vx += j * nx; vy += j * ny
                state.bvx[b] -= j / BARREL_M * nx; state.bvy[b] -= j / BARREL_M * ny
                touchedWall = true
                if (-rel > 6f) ev.onBarrel(x, y, -rel)
            }
        }
    }

    private inline fun circles(a: FloatArray, r: Float, hit: (Float, Float, Float) -> Unit) {
        var i = 0
        while (i < a.size) { circle(a[i], a[i + 1], r, hit); i += 2 }
    }

    /** The ball against a fixed round body at ([cx], [cy]): pushed out, and [hit] if it was moving in. */
    private inline fun circle(cx: Float, cy: Float, r: Float, hit: (Float, Float, Float) -> Unit) {
        val dx = x - cx
        val dy = y - cy
        val rr = BALL_R + r
        val d2 = dx * dx + dy * dy
        if (d2 >= rr * rr || d2 < 1e-6f) return
        val d = sqrt(d2)
        val nx = dx / d
        val ny = dy / d
        x = cx + nx * rr
        y = cy + ny * rr
        touchedWall = true
        val vn = vx * nx + vy * ny
        if (vn < 0f) hit(nx, ny, vn)
    }

    // ------------------------------------------------------------ barrels

    private fun stopBarrels() { state.bvx.fill(0f); state.bvy.fill(0f) }

    /** Barrels roll heavily, bounce off walls and each other, and sink in the water. */
    private fun stepBarrels(ev: PhysicsEvents) {
        val st = state
        val n = st.bx.size
        if (n == 0) return
        val dt = STEP
        for (i in 0 until n) {
            if (st.sunk[i] || (st.bvx[i] == 0f && st.bvy[i] == 0f)) continue
            val t = hole.at(st.bx[i], st.by[i])
            var sp = hypot(st.bvx[i], st.bvy[i])
            val ns = max(0f, sp - (t.c * 1.4f + BARREL_C + (t.k + 1.5f) * sp) * dt)
            if (sp > 0f) { st.bvx[i] *= ns / sp; st.bvy[i] *= ns / sp }
            st.bx[i] += st.bvx[i] * dt
            st.by[i] += st.bvy[i] * dt
            barrelWalls(i)
            for (j in 0 until n) if (j != i && !st.sunk[j]) barrelPair(i, j)
            if (st.coast.waterDepth(st.bx[i], st.by[i]) >= Hole.BARREL_R) {
                st.sunk[i] = true; st.bvx[i] = 0f; st.bvy[i] = 0f
                ev.onBarrelSink(st.bx[i], st.by[i])
                continue
            }
            sp = hypot(st.bvx[i], st.bvy[i])
            if (sp < STOP_SPEED) { st.bvx[i] = 0f; st.bvy[i] = 0f }
        }
    }

    /** One barrel against walls, rails, round bodies and the cup (barrels never come to rest in it). */
    private fun barrelWalls(i: Int) {
        val st = state
        val r = Hole.BARREL_R
        fun push(nx: Float, ny: Float, e: Float) {
            val vn = st.bvx[i] * nx + st.bvy[i] * ny
            if (vn < 0f) { st.bvx[i] -= (1f + e) * vn * nx; st.bvy[i] -= (1f + e) * vn * ny }
        }
        val tx0 = floor((st.bx[i] - r) / TILE).toInt()
        val tx1 = floor((st.bx[i] + r) / TILE).toInt()
        val ty0 = floor((st.by[i] - r) / TILE).toInt()
        val ty1 = floor((st.by[i] + r) / TILE).toInt()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val tt = hole.tile(tx, ty)
            if (!tt.solid || tt == Tile.WOOD || hole.rails.isBoulder(tx, ty)) continue
            if (tt == Tile.PLANK && st.plankBroken(tx, ty)) continue
            if (tt == Tile.GATE && st.gateOpen(tx, ty)) continue
            val left = (tx * TILE).toFloat()
            val top = (ty * TILE).toFloat()
            val px = min(max(st.bx[i], left), left + TILE)
            val py = min(max(st.by[i], top), top + TILE)
            val dx = st.bx[i] - px
            val dy = st.by[i] - py
            val d2 = dx * dx + dy * dy
            if (d2 >= r * r || d2 < 1e-6f) continue
            val d = sqrt(d2)
            st.bx[i] = px + dx / d * r; st.by[i] = py + dy / d * r
            push(dx / d, dy / d, 0.4f)
        }
        val rails = hole.rails
        for (k in rails.near(floor(st.bx[i] / TILE).toInt(), floor(st.by[i] / TILE).toInt())) {
            val t = rails.along(k, st.bx[i], st.by[i])
            val px = rails.x0[k] + (rails.x1[k] - rails.x0[k]) * t
            val py = rails.y0[k] + (rails.y1[k] - rails.y0[k]) * t
            val dx = st.bx[i] - px
            val dy = st.by[i] - py
            val rr = r + rails.r[k]
            val d2 = dx * dx + dy * dy
            if (d2 >= rr * rr || d2 < 1e-6f) continue
            val d = sqrt(d2)
            st.bx[i] = px + dx / d * rr; st.by[i] = py + dy / d * rr
            push(dx / d, dy / d, 0.4f)
        }
        fun round(cx: Float, cy: Float, cr: Float) {
            val dx = st.bx[i] - cx
            val dy = st.by[i] - cy
            val rr = r + cr
            val d2 = dx * dx + dy * dy
            if (d2 >= rr * rr || d2 < 1e-6f) return
            val d = sqrt(d2)
            st.bx[i] = cx + dx / d * rr; st.by[i] = cy + dy / d * rr
            push(dx / d, dy / d, 0.4f)
        }
        var k = 0
        while (k < hole.posts.size) { round(hole.posts[k], hole.posts[k + 1], Hole.POST_R); k += 2 }
        k = 0
        while (k < hole.corals.size) { round(hole.corals[k], hole.corals[k + 1], Hole.CORAL_R); k += 2 }
        k = 0
        while (k < hole.buoys.size) { round(hole.buoys[k], hole.buoys[k + 1], Hole.BUOY_R); k += 2 }
        for (c in hole.crabs.indices) round(st.crabX(c), st.crabY(c), Hole.CRAB_R)
        round(hole.cupX, hole.cupY, CUP_R + 1f)
    }

    private fun barrelPair(i: Int, j: Int) {
        val st = state
        val dx = st.bx[i] - st.bx[j]
        val dy = st.by[i] - st.by[j]
        val rr = 2 * Hole.BARREL_R
        val d2 = dx * dx + dy * dy
        if (d2 >= rr * rr || d2 < 1e-6f) return
        val d = sqrt(d2)
        val nx = dx / d
        val ny = dy / d
        val over = (rr - d) / 2f
        st.bx[i] += nx * over; st.by[i] += ny * over
        st.bx[j] -= nx * over; st.by[j] -= ny * over
        val rel = (st.bvx[i] - st.bvx[j]) * nx + (st.bvy[i] - st.bvy[j]) * ny
        if (rel < 0f) {
            val jj = -(1f + 0.5f) * rel / 2f
            st.bvx[i] += jj * nx; st.bvy[i] += jj * ny
            st.bvx[j] -= jj * nx; st.bvy[j] -= jj * ny
        }
    }

    // ------------------------------------------------------------ between shots

    /**
     * After a shot comes to rest (not in the cup): the tide and crabs move on. If the tide
     * covers the ball, it's lifted to the nearest dry spot for free. Returns true if it was moved.
     */
    fun endShot(): Boolean {
        if (inCup) return false
        state.advance(x, y)
        val coast = state.coast
        // Only water moves the ball (resting against a barrel or buoy is fine).
        if (coast.waterDepth(x, y) <= 0f && !nearOpenIce(x, y)) return false
        // Search outward for dry, clear ground, as far as it takes.
        var r = 1f
        val far = hypot(hole.pxW.toFloat(), hole.pxH.toFloat())
        while (r <= far) {
            val steps = (r * 6f).toInt().coerceAtLeast(8)
            var best = -1f
            var bx = 0f
            var by = 0f
            for (k in 0 until steps) {
                val a = (2 * Math.PI * k / steps).toFloat()
                val px = x + cos(a) * r
                val py = y + sin(a) * r
                val t = hole.at(px, py)
                val dry = coast.landDepth(px, py)
                if (dry >= BALL_R + 1f && !t.solid && !t.isRamp && !t.flows && !t.vent && !t.isLedge && !nearOpenIce(px, py) &&
                    !(t == Tile.GATE && !state.gateOpen(floor(px / TILE).toInt(), floor(py / TILE).toInt())) && clearOfPosts(px, py) && dry > best) { best = dry; bx = px; by = py }
            }
            if (best > 0f) { placeAt(bx, by); return true }
            r += 1f
        }
        return false
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
        moving = o.moving; inCup = o.inCup; lipped = false; shotTime = o.shotTime; lastTile = hole.at(x, y)
        ballStill = o.ballStill; kicks = o.kicks; ventFrom = o.ventFrom
        if (state !== o.state) state.copyFrom(o.state)
    }

    fun placeAt(px: Float, py: Float) {
        x = px; y = py; restX = px; restY = py; vx = 0f; vy = 0f; moving = false; inCup = false
        z = 0f; vz = 0f; ballStill = false
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

        // Later courses.
        /** A current's drift speed (above [TEETER_SPEED], so a rip really does carry you out) and pull. */
        const val CURRENT_V = 55f
        const val CURRENT_K = 2.4f
        /** Normal speed that smashes a cracked plank, and how much pace the ball keeps through it. */
        const val PLANK_BREAK = 170f
        const val PLANK_KEEP = 0.6f
        /** How much pace a ball keeps rolling into mud. */
        const val MUD_PLUG = 0.25f
        const val CORAL_E = 0.5f
        const val CORAL_H = 6f
        const val BUOY_E = 0.9f
        const val BUOY_KICK = 80f
        const val MAX_KICKS = 6
        const val BUOY_H = 10f
        const val CRAB_E = 0.45f
        const val CRAB_H = 5f
        /** Barrels: mass against the ball's, bounce, extra rolling resistance, height. */
        const val BARREL_M = 2.5f
        const val BARREL_E = 0.5f
        const val BARREL_C = 60f
        const val BARREL_H = 7f
        /** Vents: the hop's lift, and the least speed they send a ball on with. */
        const val VENT_LIFT = 170f
        const val VENT_MIN_V = 70f
        /** Snow keeps this much of a ball's pace. */
        const val SNOW_PLUG = 0.1f
        /** A tunnel sends the ball out at least this fast. */
        const val TUNNEL_MIN = 50f
        /** A ledge's lip tips the ball over this hard; its cliff bounces like this. */
        const val LEDGE_G = 300f
        const val LEDGE_E = 0.5f
        /** The wind on a ridge (less than the fairway's grip, so a ball can still stop there). */
        const val WIND_G = 30f
        const val MILL_HUB_R = 3f
        const val MILL_BLADE_R = 1.5f
        const val MILL_E = 0.5f
        const val MILL_H = 12f

        /** The softest tap still rolls the ball a few pixels. */
        const val MIN_SPEED = 22f

        /** Power 0..1 to launch speed; eased so short putts get fine control. */
        fun launchSpeed(power: Float): Float =
            MIN_SPEED + (MAX_SPEED - MIN_SPEED) * power.coerceIn(0f, 1f).pow(1.45f)
    }
}

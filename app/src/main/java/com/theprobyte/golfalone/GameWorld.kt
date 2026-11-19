package com.theprobyte.golfalone

import android.graphics.Canvas
import com.theprobyte.golfalone.GameView.Companion.GULL_SPEED
import com.theprobyte.golfalone.GameView.Companion.NONE
import com.theprobyte.golfalone.GameView.Companion.TEE_MARKS
import com.theprobyte.golfalone.GameView.Companion.TIDE_S
import com.theprobyte.golfalone.GameView.Companion.CRAB_STEP_S
import com.theprobyte.golfalone.GameView.Companion.WATER_FPS
import com.theprobyte.golfalone.GameView.Mode
import com.theprobyte.golfalone.GameView.Sheet
import com.theprobyte.golfalone.game.TILE
import com.theprobyte.golfalone.render.Bodies
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import com.theprobyte.golfalone.render.Sprites
import com.theprobyte.golfalone.render.Terrain
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The world layer: sea, ground, effects, ball and aim, drawn in world pixels.
// Part of [GameView], split out by screen; these read and draw its state.

internal fun GameView.drawWorld(ix: Int, iy: Int) {
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
    // The tide: draw the map for its state, crossfading from the other one as it turns.
    val now = terrain.at(holeState.high)
    c.drawBitmap(now.ground, gx, gy, blit)
    c.drawBitmap(now.water(wf), gx, gy, blit)
    if (tideT >= 0f && terrain.flooded != null) {
        val was = terrain.at(!holeState.high)
        val a = ((1f - tideT / TIDE_S) * 255).toInt().coerceIn(0, 255)
        tidePaint.alpha = a
        c.drawBitmap(was.ground, gx, gy, tidePaint)
        c.drawBitmap(was.water(wf), gx, gy, tidePaint)
    } else if (terrain.flooded != null && mode == Mode.PLAY && !physics.moving) {
        // What the tide will do after this shot.
        val warn = if (!holeState.high && holeState.highAfter) terrain.floodSoon else if (holeState.high && !holeState.highAfter) terrain.drainSoon else null
        if (warn != null) {
            tidePaint.alpha = (150 + 100 * sin(steppedTime * 3f)).toInt().coerceIn(0, 255)
            c.drawBitmap(warn, gx, gy, tidePaint)
        }
    }

    // Glints drifting downhill on slopes.
    for (i in slopeTiles) {
        val t = hole.tiles[i]
        val tx = i % hole.w
        val ty = i / hole.w
        val h1 = Terrain.hash(tx, ty, 41)
        if (h1 > 0.6f) continue
        val ph = ((steppedTime * 0.9f + h1 * 7f) % 1f)
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
    for (s in TEE_MARKS) {
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

    // Buoys, coral, crabs, barrels and cracked planks.
    val crabK = if (crabMoveT < 0f || reduceMotion) 1f else min(1f, crabMoveT / CRAB_STEP_S)
    Bodies.draw(c, hole, holeState, ix, iy, if (reduceMotion) 0f else steppedTime, crabFromX, crabFromY, crabK,
        mode == Mode.PLAY && !physics.moving && sinkT < 0f, bumpX, bumpY, bumpT)

    ambient.draw(c, ix, iy, time, physics.x, physics.y, physics.moving, reduceMotion)

    // Trail, coloured by the shot's power.
    val trail = powerColour(shotPower)
    for (i in 0 until tn) {
        val age = time - tAt[i]
        if (age < 0f || age > 0.2f) continue
        px(c, tX[i].roundToInt() - ix, tY[i].roundToInt() - iy, Palette.alpha(trail, (150 * (1f - age / 0.2f)).toInt()))
    }

    drawBall(c, ix, iy)
    if (mode == Mode.PLAY && aiming && power > 0f) drawAim(c, ix, iy)
    spriteFx.draw(c, ix, iy)

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

    // Celebration: three gulls lift off the green.
    if (celebrateT >= 0f) for (g in 0 until 3) {
        val f = celebrateT - g * 0.15f
        if (f < 0f) continue
        val gx = (hole.cupX + (g - 1) * (6f + f * 14f) + sin(f * 3f + g) * 2f).roundToInt() - ix
        val gy = (hole.cupY - 4f - f * 34f - f * f * 6f).roundToInt() - iy
        val flap = ((f * 9f).toInt() + g) % 2 == 0
        val lift = (f * 12f).roundToInt()
        Sprites.gull(c, gx + 2 + lift / 2, gy + 3 + lift, flap, Palette.alpha(Palette.INK_SHADOW, 60))
        Sprites.gull(c, gx, gy, flap, Palette.INK)
    }

    // Lanterns and the lighthouse (with its beam) stand over everything.
    Bodies.scenery(c, hole, ix, iy, steppedTime, reduceMotion)

    // A storm's rain (screen space; none under reduce motion).
    if (!reduceMotion) ambient.rain(c, lw + 1, lh + 1, time)

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

internal fun GameView.drawBall(c: Canvas, ix: Int, iy: Int) {
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
        val ph = (steppedTime * 0.8f) % 1f
        Pix.ring(c, x + 0.5f, y + 0.5f, 3f + ph * 6f, Palette.alpha(Palette.INK, (120 * (1f - ph)).toInt()))
    }
}

internal fun GameView.drawAim(c: Canvas, ix: Int, iy: Int) {
    val len = max(1f, hypot(downX - curX, downY - curY))
    val ux = (downX - curX) / len
    val uy = (downY - curY) / len
    val dots = 2 + (power * 11f).toInt()
    val col = powerColour(power)
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

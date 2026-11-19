package com.theprobyte.golfalone

import android.graphics.Canvas
import com.theprobyte.golfalone.GameView.Companion.B_PAUSE
import com.theprobyte.golfalone.GameView.Companion.CHIP
import com.theprobyte.golfalone.GameView.Companion.NONE
import com.theprobyte.golfalone.GameView.Sheet
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import com.theprobyte.golfalone.render.Sprites
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// The play screen: the HUD strip, hole banner, rubber band, pause chip, and hints.
// Part of [GameView], split out by screen; these read and draw its state.

internal fun GameView.drawHud(c: Canvas) {
    val y = insetTop + 3
    rect(c, 0, 0, lw, y + 10, Palette.alpha(Palette.INK_SHADOW, 170))
    rect(c, 0, y + 10, lw, 1, Palette.alpha(Palette.INK_SHADOW, 80))
    if (hudHole != holeIndex) { hudHole = holeIndex; hudHoleText = "HOLE ${holeIndex + 1}/$n"; hudParText = "PAR ${hole.par}" }
    if (hudStrokes != strokes) { hudStrokes = strokes; hudShotsText = "SHOTS $strokes" }
    val holeText = hudHoleText
    PixelFont.draw(c, holeText, 4, y, Palette.INK, 1, Palette.INK_SHADOW, Face.BIG)
    PixelFont.draw(c, hudParText, 4 + PixelFont.width(holeText, 1, Face.BIG) + 6, y, Palette.INK_DIM, 1, Palette.INK_SHADOW, Face.BIG)
    val s = hudShotsText
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

internal fun GameView.drawPlayUi(c: Canvas) {
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

    // Hints along the bottom (pulses are stepped at 15 Hz so the idle throttle can hold).
    val hint = playHint() ?: return
    val hy = safeBottom - CHIP - 14
    val tq = floor(time * 15f) / 15f
    val pulse = (0.6f + 0.4f * sin(tq * 3f)).coerceIn(0f, 1f)
    val a = if (hint.second) (255 * pulse).toInt() else 255
    val col = if (hint.first == "TAP TO SKIP") Palette.INK_DIM else Palette.INK
    PixelFont.drawCentered(c, hint.first, lw / 2, hy, Palette.alpha(col, a), 1, Palette.alpha(Palette.INK_SHADOW, a), Face.BIG)
}

/** The hint line in play, if any: (text, pulsing). */
internal fun GameView.playHint(): Pair<String, Boolean>? {
    if (sheet != Sheet.NONE) return null
    if (fastForward) return ">> FAST" to false
    if (peeking) return "PEEKING AT THE GREEN" to false
    if (flyover && holeT > 0.4f) return "TAP TO SKIP" to false
    if (progress.best(0) == 0 && holeIndex == 0 && strokes == 0 && !physics.moving && holeT > 1f) {
        return (if (aiming) (if (power > 0f) "LET GO TO SHOOT" else "PULL BACK TO AIM") else "PULL BACK ANYWHERE TO AIM") to true
    }
    if (isLong() && strokes == 0 && !aiming && !physics.moving && holeT > 2.6f) return "HOLD STILL TO PEEK" to true
    return null
}

internal fun GameView.playHintShowing(): Boolean = playHint() != null

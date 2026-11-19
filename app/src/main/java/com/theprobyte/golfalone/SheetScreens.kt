package com.theprobyte.golfalone

import android.graphics.Canvas
import com.theprobyte.golfalone.GameView.Companion.B_BUZZ
import com.theprobyte.golfalone.GameView.Companion.B_DONE
import com.theprobyte.golfalone.GameView.Companion.B_LEFT
import com.theprobyte.golfalone.GameView.Companion.B_MOTION
import com.theprobyte.golfalone.GameView.Companion.B_PULL
import com.theprobyte.golfalone.GameView.Companion.B_QUIT
import com.theprobyte.golfalone.GameView.Companion.B_RESTART
import com.theprobyte.golfalone.GameView.Companion.B_RESUME
import com.theprobyte.golfalone.GameView.Companion.B_SETTINGS
import com.theprobyte.golfalone.GameView.Companion.B_STATUS
import com.theprobyte.golfalone.GameView.Companion.B_VOL_FX
import com.theprobyte.golfalone.GameView.Companion.B_VOL_SURF
import com.theprobyte.golfalone.GameView.Companion.GAP
import com.theprobyte.golfalone.GameView.Companion.PRIMARY_H
import com.theprobyte.golfalone.GameView.Companion.PULL_NAMES
import com.theprobyte.golfalone.GameView.Companion.SECONDARY_H
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import kotlin.math.roundToInt

// The pause and settings sheets, with their sliders.
// Part of [GameView], split out by screen; these read and draw its state.

internal fun GameView.toggleLabel(on: Boolean) = if (on) "ON" else "OFF"

internal fun GameView.drawPause(c: Canvas) {
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

internal fun GameView.drawSettings(c: Canvas) {
    rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 140))
    val h = lh - safeBottom + PRIMARY_H + 7 * (SECONDARY_H + GAP) + 30
    val st = bottomSheet(c, h, sheetT)
    PixelFont.drawCentered(c, "SETTINGS", lw / 2, st + 8, Palette.PAPER_INK, 2, 0, Face.BIG)
    val pad = 8
    var y = st + 26
    fun toggle(id: Int, label: String, on: Boolean, value: String = toggleLabel(on)) {
        button(c, id, pad, y, lw - pad * 2, SECONDARY_H, "$label: $value", false, on)
        y += SECONDARY_H + GAP
    }
    slider(c, B_VOL_FX, 0, pad, y, lw - pad * 2, "EFFECTS", progress.effectsVolume); y += SECONDARY_H + GAP
    slider(c, B_VOL_SURF, 1, pad, y, lw - pad * 2, "SURF", progress.surfVolume); y += SECONDARY_H + GAP
    toggle(B_BUZZ, "VIBRATION", progress.haptics)
    toggle(B_LEFT, "LEFT-HANDED", progress.leftHanded)
    toggle(B_MOTION, "REDUCE MOTION", progress.reduceMotion)
    toggle(B_STATUS, "STATUS BAR", true, if (progress.hideStatusBar) "HIDE IN PLAY" else "ALWAYS")
    toggle(B_PULL, "PULL LENGTH", true, PULL_NAMES[progress.dragLength])
    button(c, B_DONE, pad, y, lw - pad * 2, PRIMARY_H, "DONE", true)
}

/** A paper slider: a label, then a wooden knob on a track. Drag anywhere on the row to set it. */
internal fun GameView.slider(c: Canvas, id: Int, slot: Int, x: Int, y: Int, w: Int, label: String, value: Float) {
    addButton(id, x, y, w, SECONDARY_H)
    Pix.tab(c, x, y, w, SECONDARY_H, false)
    val pct = (value * 100).roundToInt()
    PixelFont.draw(c, "$label ${if (pct == 0) "OFF" else "$pct%"}", x + 6, y + 7, Palette.PAPER_INK, 1, 0, Face.BIG)
    val x0 = x + 92
    val x1 = x + w - 8
    sliderX0[slot] = x0; sliderX1[slot] = x1
    val ty = y + SECONDARY_H / 2 - 1
    rect(c, x0, ty, x1 - x0, 2, Palette.PAPER[1])
    val kx = x0 + ((x1 - x0) * value).roundToInt()
    rect(c, x0, ty, kx - x0, 2, Palette.WOOD)
    Pix.plank(c, kx - 3, y + 4, 7, SECONDARY_H - 7, pressedId == id, id)
}

internal fun GameView.setSlider(id: Int, lx: Int) {
    val slot = if (id == B_VOL_FX) 0 else 1
    val v = ((lx - sliderX0[slot]) / (sliderX1[slot] - sliderX0[slot]).toFloat()).coerceIn(0f, 1f)
    val snapped = (v * 20f).roundToInt() / 20f // 5% steps
    if (id == B_VOL_FX) { progress.effectsVolume = snapped; sfx.effectsVolume = snapped }
    else { progress.surfVolume = snapped; sfx.surfVolume = snapped }
    uiVersion++
}

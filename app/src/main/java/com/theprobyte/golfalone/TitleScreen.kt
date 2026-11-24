package com.theprobyte.golfalone

import android.graphics.Bitmap
import android.graphics.Canvas
import com.theprobyte.golfalone.GameView.Companion.B_BACK
import com.theprobyte.golfalone.GameView.Companion.B_BOOK
import com.theprobyte.golfalone.GameView.Companion.B_HOLES
import com.theprobyte.golfalone.GameView.Companion.B_NEW
import com.theprobyte.golfalone.GameView.Companion.B_NEXT_HOLE
import com.theprobyte.golfalone.GameView.Companion.B_PLAY
import com.theprobyte.golfalone.GameView.Companion.B_PREV_COURSE
import com.theprobyte.golfalone.GameView.Companion.B_NEXT_COURSE
import com.theprobyte.golfalone.GameView.Companion.B_PLAY_HOLE
import com.theprobyte.golfalone.GameView.Companion.B_PREV_HOLE
import com.theprobyte.golfalone.GameView.Companion.B_SETTINGS
import com.theprobyte.golfalone.GameView.Companion.GAP
import com.theprobyte.golfalone.GameView.Companion.PRIMARY_H
import com.theprobyte.golfalone.GameView.Companion.SECONDARY_H
import com.theprobyte.golfalone.game.Holes
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import com.theprobyte.golfalone.render.Sprites
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The title screen and hole select.
// Part of [GameView], split out by screen; these read and draw its state.

internal fun GameView.drawTitle(c: Canvas) {
    rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 110))
    val a = (255 * min(1f, modeT / 0.5f)).toInt()
    val top = insetTop + 34
    val bob = if (reduceMotion) 0 else (sin(steppedTime * 1.2f) * 1.5f).roundToInt()
    val sh = Palette.alpha(Palette.INK_SHADOW, a)
    PixelFont.drawCentered(c, "GOLF", lw / 2, top + bob, Palette.alpha(Palette.INK, a), 5, sh, Face.BIG)
    PixelFont.drawCentered(c, "ALONE", lw / 2, top + 42 + bob, Palette.alpha(Palette.GOLD, a), 4, sh, Face.BIG)
    Sprites.flagIcon(c, lw / 2 + 56, top + 20 + bob, Palette.FLAG, 14)
    PixelFont.drawCentered(c, "A QUIET ISLAND COURSE", lw / 2, top + 76, Palette.alpha(Palette.INK, a), 1, sh, Face.BIG)

    // The sheet: the course (swipe or arrows to change it), the main button, and a row of secondary ones.
    val h = safeBottom.let { lh - it } + PRIMARY_H + GAP * 2 + SECONDARY_H * 2 + 14
    val st = bottomSheet(c, h, modeT)
    val pad = 8
    val open = courseOpen(course)
    val ci = Holes.COURSES.indexOf(course)
    val aw = 26
    iconButton(c, B_PREV_COURSE, pad, st + 6, aw, SECONDARY_H, Sprites.iconLeft, ci > 0)
    iconButton(c, B_NEXT_COURSE, lw - pad - aw, st + 6, aw, SECONDARY_H, Sprites.iconRight, ci < Holes.COURSES.size - 1)
    // The name slides in from the side you moved towards.
    val slide = if (reduceMotion) 0 else ((1f - courseSlideT) * 14f * courseSlideDir).roundToInt()
    bigText(c, course.name, lw / 2 + slide, st + 8, if (open) Palette.PAPER_INK else Palette.PAPER_INK_DIM)
    val best = progress.courseBest()
    val info = when {
        !open -> "LOCKED * ${course.holes.size} HOLES * PAR ${course.par}"
        best == 0 -> "${course.holes.size} HOLES * PAR ${course.par}"
        else -> "BEST $best (${parText(best - course.par)}) * PAR ${course.par}"
    }
    smallText(c, info, lw / 2 + slide, st + 19, Palette.PAPER_INK_DIM)
    // Course dots.
    val cn = Holes.COURSES.size
    for (k in 0 until cn) rect(c, lw / 2 - cn * 3 + k * 6 + 1, st + 26, 3, 2, if (k == ci) Palette.STAMP_RED else Palette.PAPER[1])
    val by = st + 32
    val saved = open && hasSavedRound
    button(c, B_PLAY, pad, by, lw - pad * 2, PRIMARY_H, if (!open) "FINISH ${Holes.COURSES[ci - 1].name}" else if (saved) "CONTINUE HOLE ${progress.runHole + 1}" else "PLAY COURSE", open)
    val ry = by + PRIMARY_H + GAP
    // [NEW] [HOLES] [BOOK] [gear], without NEW when there's no round to start over.
    val gw = 30
    val labels = if (saved) TITLE_ROW_SAVED else TITLE_ROW
    val bw = (lw - pad * 2 - gw - GAP * labels.size) / labels.size
    for ((k, b) in labels.withIndex()) button(c, b.first, pad + k * (bw + GAP), ry, bw, SECONDARY_H, b.second, false)
    iconButton(c, B_SETTINGS, lw - pad - gw, ry, gw, SECONDARY_H, Sprites.iconGear)
}

/** A hole's thumbnail sized to fit [maxW]×[maxH], cached per box size. */
internal fun GameView.thumb(i: Int, maxW: Int, maxH: Int): Bitmap? {
    val box = (maxW.toLong() shl 32) or maxH.toLong()
    if (box != thumbBox) { thumbs.fill(null); thumbBox = box }
    thumbs[i]?.let { return it }
    val f = terrains[i]
    if (!f.isDone) return null
    val t = f.get()
    val step = max(ceil(t.w / maxW.toFloat()).toInt(), ceil(t.h / maxH.toFloat()).toInt()).coerceAtLeast(1)
    return t.thumbnail(step).also { thumbs[i] = it }
}

internal fun GameView.drawSelect(c: Canvas) {
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

private val TITLE_ROW_SAVED = listOf(GameView.B_NEW to "NEW", GameView.B_HOLES to "HOLES", GameView.B_BOOK to "BOOK")
private val TITLE_ROW = listOf(GameView.B_HOLES to "HOLES", GameView.B_BOOK to "BOOK")

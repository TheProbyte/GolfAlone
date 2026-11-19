package com.theprobyte.golfalone

import android.graphics.Canvas
import com.theprobyte.golfalone.GameView.Companion.B_AGAIN
import com.theprobyte.golfalone.GameView.Companion.B_MENU
import com.theprobyte.golfalone.GameView.Companion.B_NEXT
import com.theprobyte.golfalone.GameView.Companion.B_RETRY
import com.theprobyte.golfalone.GameView.Companion.B_SELECT
import com.theprobyte.golfalone.GameView.Companion.CARD_READY
import com.theprobyte.golfalone.GameView.Companion.GAP
import com.theprobyte.golfalone.GameView.Companion.PRIMARY_H
import com.theprobyte.golfalone.GameView.Companion.SECONDARY_H
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.px
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import com.theprobyte.golfalone.render.Sprites
import kotlin.math.min

// The hole card (with its stamp) and the end-of-round scorecard.
// Part of [GameView], split out by screen; these read and draw its state.

internal fun GameView.flagColour(strokes: Int, par: Int) = when {
    strokes == 1 -> Palette.FLAG
    strokes <= par -> Palette.GOLD
    else -> Palette.PAPER[1]
}

internal fun GameView.resultWord(strokes: Int, par: Int): String {
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

internal fun GameView.resultInk(strokes: Int, par: Int) = when {
    strokes < par || strokes == 1 -> Palette.STAMP_RED
    strokes == par -> Palette.PAPER_INK
    else -> Palette.PAPER_INK_DIM
}

internal fun GameView.drawCard(c: Canvas) {
    val k = min(1f, cardT / 0.3f)
    rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, (90 * k).toInt()))
    val stripH = if (courseRun) 32 + 14 else 0 // the round strip, plus room for the tap hint
    val buttonsH = if (courseRun) PRIMARY_H else PRIMARY_H + GAP + SECONDARY_H
    val h = lh - safeBottom + buttonsH + stripH + 76
    val st = bottomSheet(c, h, cardT)

    val word = resultWord(strokes, hole.par)
    val ws = if (PixelFont.width(word, 2, Face.BIG) <= lw - 16) 2 else 1
    // A rubber stamp: the word comes down a size larger, then lands with a thump.
    val big = !reduceMotion && !stamped && PixelFont.width(word, ws + 1, Face.BIG) <= lw - 8
    val wsNow = if (big) ws + 1 else ws
    val wy = st + 10 - (if (big) 3 else 0)
    PixelFont.drawCentered(c, word, lw / 2, wy, resultInk(strokes, hole.par), wsNow, 0, Face.BIG)
    // Under par: a few twinkles around the stamp.
    if (strokes < hole.par && stampT >= 0f && !reduceMotion) {
        val ww = PixelFont.width(word, ws, Face.BIG)
        for (k in 0 until 6) {
            val ph = ((stampT * 15f).toInt() + k * 2) % 6
            if (ph > 2) continue
            val sx = lw / 2 + (if (k % 2 == 0) -1 else 1) * (ww / 2 + 4 + (k / 2) * 5)
            val sy = wy + 2 + (k / 2) * 4 - (k % 3)
            px(c, sx, sy, Palette.HOT)
            if (ph == 1) { px(c, sx - 1, sy, Palette.GOLD); px(c, sx + 1, sy, Palette.GOLD); px(c, sx, sy - 1, Palette.GOLD); px(c, sx, sy + 1, Palette.GOLD) }
        }
    }
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

internal fun GameView.drawFinal(c: Canvas) {
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
    val best = progress.courseBest()
    if (courseNewBest) bigText(c, "NEW COURSE BEST!", lw / 2, ry, Palette.STAMP_RED)
    else bigText(c, "COURSE BEST $best (${parText(best - course.par)})", lw / 2, ry, Palette.PAPER_INK_DIM)
    if (modeT < 0.4f) return
    val pad = 8
    val bw = (lw - pad * 2 - GAP) / 3
    button(c, B_MENU, pad, safeBottom - PRIMARY_H, bw, PRIMARY_H, "MENU", false)
    button(c, B_AGAIN, pad + bw + GAP, safeBottom - PRIMARY_H, lw - pad * 2 - bw - GAP, PRIMARY_H, "PLAY AGAIN", true)
}

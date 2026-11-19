package com.theprobyte.golfalone

import android.graphics.Canvas
import com.theprobyte.golfalone.GameView.Companion.B_DONE
import com.theprobyte.golfalone.GameView.Companion.B_TAB_CARDS
import com.theprobyte.golfalone.GameView.Companion.B_TAB_STATS
import com.theprobyte.golfalone.GameView.Companion.GAP
import com.theprobyte.golfalone.GameView.Companion.PRIMARY_H
import com.theprobyte.golfalone.GameView.Companion.SECONDARY_H
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.PixelFont.Face
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// The book: stats and postcards.
// Part of [GameView], split out by screen; these read and draw its state.

/** The book: your stats, and a postcard for every hole you've beaten par on. */
internal fun GameView.drawBook(c: Canvas) {
    rect(c, 0, 0, lw, lh, Palette.alpha(Palette.INK_SHADOW, 140))
    val h = lh - insetTop - 16
    val st = bottomSheet(c, h, sheetT)
    PixelFont.drawCentered(c, "BOOK", lw / 2, st + 8, Palette.PAPER_INK, 2, 0, Face.BIG)
    val pad = 8
    val tw = (lw - pad * 2 - GAP) / 2
    val ty = st + 26
    button(c, B_TAB_STATS, pad, ty, tw, SECONDARY_H, "STATS", false, bookTab == 0)
    button(c, B_TAB_CARDS, pad + tw + GAP, ty, tw, SECONDARY_H, "POSTCARDS", false, bookTab == 1)
    val top = ty + SECONDARY_H + 8
    val bottom = safeBottom - PRIMARY_H - 6
    if (bookTab == 0) drawStats(c, top) else drawPostcards(c, top, bottom)
    button(c, B_DONE, pad, safeBottom - PRIMARY_H, lw - pad * 2, PRIMARY_H, "DONE", true)
}

internal fun GameView.drawStats(c: Canvas, top: Int) {
    var y = top
    fun cells(items: List<Pair<String, String>>) {
        val cw = (lw - 16) / items.size
        for ((k, it) in items.withIndex()) {
            val cx = 8 + k * cw + cw / 2
            PixelFont.drawCentered(c, it.second, cx, y, Palette.PAPER_INK, 2, 0, Face.BIG)
            smallText(c, it.first, cx, y + 16, Palette.PAPER_INK_DIM)
        }
        y += 26
    }
    fun count(k: String) = progress.statCount(k).toString()
    cells(listOf("ROUNDS" to count("rounds"), "HOLES" to count("holes"), "JUMPS" to count("jumps")))
    cells(listOf("ACES" to count("aces"), "EAGLES" to count("eagles"), "BIRDIES" to count("birdies"), "PARS" to count("pars")))
    val longest = progress.longestShot
    bigText(c, if (longest == 0) "LONGEST SHOT -" else "LONGEST SHOT ${longest / 2} YD", lw / 2, y, Palette.PAPER_INK)
    y += 11
    val best = progress.courseBest()
    bigText(c, if (best == 0) "COURSE BEST -" else "COURSE BEST $best (${parText(best - course.par)})", lw / 2, y, Palette.PAPER_INK)
    y += 16
    // Hole by hole: best and average.
    Pix.ruled(c, 4, y + 6, lw - 8, (n + 1) * 9, 9)
    PixelFont.draw(c, "HOLE", 8, y, Palette.PAPER_INK_DIM, 1, 0, Face.SMALL)
    smallText(c, "PAR", lw - 64, y, Palette.PAPER_INK_DIM)
    smallText(c, "BEST", lw - 40, y, Palette.PAPER_INK_DIM)
    smallText(c, "AVG", lw - 16, y, Palette.PAPER_INK_DIM)
    y += 9
    for (i in 0 until n) {
        val h = course.holes[i]
        val b = progress.best(i)
        val avg = progress.averageOn(i)
        PixelFont.draw(c, "${i + 1} ${h.name}", 8, y, Palette.PAPER_INK, 1, 0, Face.SMALL)
        smallText(c, "${h.par}", lw - 64, y, Palette.PAPER_INK)
        smallText(c, if (b == 0) "-" else "$b", lw - 40, y, if (b in 1 until h.par) Palette.STAMP_RED else Palette.PAPER_INK)
        smallText(c, if (avg == 0f) "-" else fmt(avg), lw - 16, y, Palette.PAPER_INK)
        y += 9
    }
}

internal fun GameView.drawPostcards(c: Canvas, top: Int, bottom: Int) {
    val earned = (0 until n).count { postcardEarned(it) }
    bigText(c, "POSTCARDS $earned/$n", lw / 2, top, Palette.PAPER_INK)
    smallText(c, "BEAT PAR ON A HOLE TO EARN ITS CARD", lw / 2, top + 10, Palette.PAPER_INK_DIM)
    val gy = top + 20
    val cols = 2
    val rows = (n + cols - 1) / cols
    val cw = (lw - 16 - GAP) / cols
    val ch = min(80, (bottom - gy - GAP * (rows - 1)) / rows)
    val box = ((cw - 8).toLong() shl 32) or (ch - 17).toLong()
    if (box != postcardBox) { postcardThumbs.fill(null); postcardBox = box }
    for (i in 0 until n) {
        val x = 8 + (i % cols) * (cw + GAP)
        val y = gy + (i / cols) * (ch + GAP)
        val h = course.holes[i]
        if (postcardEarned(i)) {
            Pix.paper(c, x, y, cw, ch, perforated = false)
            val tb = postcardThumbs[i] ?: run {
                val f = terrains[i]
                if (!f.isDone) null else {
                    val t = f.get()
                    val step = max(ceil(t.w / (cw - 8f)).toInt(), ceil(t.h / (ch - 17f)).toInt()).coerceAtLeast(1)
                    t.thumbnail(step).also { postcardThumbs[i] = it }
                }
            }
            if (tb != null) c.drawBitmap(tb, (x + (cw - tb.width) / 2).toFloat(), (y + 4 + (ch - 17 - tb.height) / 2).toFloat(), blit)
            smallText(c, h.name, x + cw / 2, y + ch - 10, Palette.PAPER_INK)
            // The postmark: a red ring with your best in it.
            val px0 = x + cw - 10
            val py0 = y + 9
            Pix.ring(c, px0 + 0.5f, py0 + 0.5f, 6f, Palette.STAMP_RED)
            smallText(c, "${progress.best(i)}", px0 + 1, py0 - 2, Palette.STAMP_RED)
        } else {
            rect(c, x, y, cw, ch, Palette.alpha(Palette.PAPER[1], 160))
            rect(c, x + 1, y + 1, cw - 2, ch - 2, Palette.alpha(Palette.PAPER[2], 200))
            PixelFont.drawCentered(c, "${i + 1}", x + cw / 2, y + ch / 2 - 10, Palette.PAPER_INK_DIM, 2, 0, Face.BIG)
            smallText(c, "UNDER PAR ${h.par}", x + cw / 2, y + ch / 2 + 8, Palette.PAPER_INK_DIM)
        }
    }
}

internal fun GameView.postcardEarned(i: Int): Boolean { val b = progress.best(i); return b in 1 until course.holes[i].par }

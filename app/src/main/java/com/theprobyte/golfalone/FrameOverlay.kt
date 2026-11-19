package com.theprobyte.golfalone

import android.graphics.Canvas
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix.rect
import com.theprobyte.golfalone.render.PixelFont
import kotlin.math.max
import kotlin.math.roundToInt

// The frame-time overlay (long-press the logo).
// Part of [GameView], split out by screen; these read and draw its state.

internal fun GameView.median95(a: FloatArray, tmp: FloatArray): Pair<Float, Float>? {
    var n = 0
    for (v in a) if (v > 0f) tmp[n++] = v
    if (n == 0) return null
    java.util.Arrays.sort(tmp, 0, n)
    return tmp[n / 2] to tmp[(n * 95 / 100).coerceAtMost(n - 1)]
}

internal fun GameView.fmt(v: Float): String = ((v * 10f).roundToInt() / 10f).toString()

internal fun GameView.drawFrameTimes(c: Canvas) {
    if (time - overlayAt > 0.5f || overlayAt < 0f) {
        overlayAt = time
        val f = median95(frameMs, sortTmp)
        val d = median95(drawMs, sortTmp)
        overlay1 = if (f == null) "FRAME IDLE" else "FRAME ${fmt(f.first)} P95 ${fmt(f.second)} WORST ${fmt(worstFrameMs)}"
        overlay2 = if (d == null) "" else "DRAW ${fmt(d.first)} P95 ${fmt(d.second)} WORST ${fmt(worstDrawMs)}"
        overlay3 = (if (metricGpu >= 0f) "GPU ${fmt(metricGpu)} " else "") + "SYNC ${fmt(metricSync)} TOTAL ${fmt(metricTotal)}" +
            (if (startupMs >= 0) " START ${startupMs}MS" else "")
    }
    val w = max(PixelFont.width(overlay1), max(PixelFont.width(overlay2), PixelFont.width(overlay3))) + 6
    rect(c, 0, insetTop + 14, w, 23, Palette.alpha(Palette.INK_SHADOW, 200))
    PixelFont.draw(c, overlay1, 3, insetTop + 16, Palette.GOLD)
    PixelFont.draw(c, overlay2, 3, insetTop + 23, Palette.GOLD)
    PixelFont.draw(c, overlay3, 3, insetTop + 30, Palette.GOLD)
}

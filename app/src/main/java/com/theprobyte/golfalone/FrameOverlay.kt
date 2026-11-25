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
        // Where the time goes, per phase of a frame (smoothed ms), and this hole's build costs.
        fun ph(i: Int) = "${GameView.PHASE_NAMES[i]} ${fmt(phaseMs[i])}"
        overlay4 = "${ph(GameView.PH_STEP)} ${ph(GameView.PH_SEA)} ${ph(GameView.PH_MAP)} ${ph(GameView.PH_PIECES)}"
        overlay5 = "${ph(GameView.PH_LIFE)} ${ph(GameView.PH_FX)} ${ph(GameView.PH_UI)} ${ph(GameView.PH_BLIT)}"
        val t = if (ready) terrain else null
        overlay6 = "PRIMS ${drawCalls.roundToInt()}" + (if (t != null) " BAKE ${t.bakeMs.roundToInt()}MS WATER ${t.waterMs.roundToInt()}MS" else "")
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        overlay7 = "HEAP ${(rt.totalMemory() - rt.freeMemory()) / mb}/${rt.maxMemory() / mb}MB ${course.id.uppercase()}-${holeIndex + 1}"
    }
    val lines = arrayOf(overlay1, overlay2, overlay3, overlay4, overlay5, overlay6, overlay7)
    var w = 0
    for (l in lines) w = max(w, PixelFont.width(l))
    rect(c, 0, insetTop + 14, w + 6, lines.size * 7 + 2, Palette.alpha(Palette.INK_SHADOW, 200))
    for ((i, l) in lines.withIndex()) PixelFont.draw(c, l, 3, insetTop + 16 + i * 7, if (i < 3) Palette.GOLD else Palette.PAPER[3])
}

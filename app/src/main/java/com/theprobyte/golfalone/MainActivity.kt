package com.theprobyte.golfalone

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View

class MainActivity : Activity() {

    private lateinit var progress: Progress
    private lateinit var sfx: Sfx
    private lateinit var haptics: Haptics
    internal lateinit var game: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        createdAt = android.os.SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        progress = Progress(this)
        sfx = Sfx(this).apply { effectsVolume = progress.effectsVolume; surfVolume = progress.surfVolume }
        haptics = Haptics(this).apply { enabled = progress.haptics }
        game = GameView(this, progress, sfx, haptics)
        game.onImmersive = { hide -> setStatusBarHidden(hide) }
        setContentView(game)
        goEdgeToEdge()
        game.post { preferHighRefreshRate() }
        listenToFrameMetrics()
    }

    /** Full-screen play: hide the status bar while a hole is on, show it again in menus. */
    private fun setStatusBarHidden(hide: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val c = window.insetsController ?: return
        if (hide) {
            c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(android.view.WindowInsets.Type.statusBars())
        } else c.show(android.view.WindowInsets.Type.statusBars())
    }

    /** Android's own per-frame timings (upload, GPU, total) for the frame-time overlay. */
    private fun listenToFrameMetrics() {
        if (Build.VERSION.SDK_INT < 26) return
        val thread = android.os.HandlerThread("frame-metrics").apply { start() }
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            val ms = 1e-6f
            val gpu = if (Build.VERSION.SDK_INT >= 31) m.getMetric(android.view.FrameMetrics.GPU_DURATION) * ms else -1f
            game.onFrameMetrics(m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) * ms,
                m.getMetric(android.view.FrameMetrics.SYNC_DURATION) * ms, gpu)
        }, android.os.Handler(thread.looper))
    }

    override fun onResume() {
        super.onResume()
        sfx.onResume()
    }

    override fun onPause() {
        game.onHostPause()
        sfx.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Asks for the panel's fastest mode at the current resolution (e.g. 120 Hz). */
    private fun preferHighRefreshRate() {
        if (Build.VERSION.SDK_INT < 30) return
        val d = try { game.display } catch (_: RuntimeException) { null } ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!game.onBack()) super.onBackPressed()
    }

    companion object {
        /** When the activity was created, so the overlay's START includes building every course. */
        @Volatile var createdAt = 0L
    }
}

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
        super.onCreate(savedInstanceState)
        progress = Progress(this)
        sfx = Sfx(this).apply { enabled = progress.sound }
        haptics = Haptics(this).apply { enabled = progress.haptics }
        game = GameView(this, progress, sfx, haptics)
        setContentView(game)
        goEdgeToEdge()
        game.post { preferHighRefreshRate() }
    }

    override fun onResume() {
        super.onResume()
        sfx.onResume()
    }

    override fun onPause() {
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
}

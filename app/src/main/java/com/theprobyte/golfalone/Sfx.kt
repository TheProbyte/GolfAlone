package com.theprobyte.golfalone

import android.content.Context
import com.theprobyte.golfalone.audio.AudioEngine
import com.theprobyte.golfalone.audio.Synth

/**
 * Golf sounds on the live synthesizer: wooden knocks for the club and walls, noise
 * sweeps for whooshes, sand, splashes and surf, and a little mallet phrase in the cup.
 * Pans follow where things happen on screen (-1 left … 1 right). Sound never takes
 * audio focus, so your own music keeps playing.
 */
class Sfx(context: Context) {

    private val engine = AudioEngine(context)

    var enabled = true
        set(v) {
            field = v
            val g = if (v) 0.85f else 0f
            engine.post { it.effectsGain = g }
        }

    init { engine.post { it.effectsGain = 0.85f } }

    fun onResume() = engine.onResume()
    fun onPause() = engine.onPause()
    fun release() = engine.release()

    private inline fun play(crossinline f: (Synth) -> Unit) {
        if (enabled) engine.post { f(it) }
    }

    /** Pulling back: soft clicks that climb with power. */
    fun tension(step: Int, max: Boolean) = play {
        it.click(0.25f + 0.05f * step, 0f)
        if (max) it.glass(Synth.midiToHz(88f), 0.25f, 0f)
    }

    fun strike(power: Float, pan: Float) = play {
        it.tok(150f + 70f * power, 0.55f + 0.45f * power, pan)
        it.click(0.7f, pan)
        if (power > 0.3f) it.sweep(0.18f + 0.3f * power, pan, 160f + 160f * power, 2200f, 700f)
    }

    /** Stone gives a bright knock; driftwood and palm trunks a warmer, hollow one. */
    fun wall(impact: Float, pan: Float, wood: Boolean) = play {
        val v = (impact / 260f).coerceIn(0.08f, 1f)
        if (wood) {
            it.tok(230f + impact * 0.35f, v, pan)
            it.tok(460f + impact * 0.5f, v * 0.35f, pan, delayMs = 8f)
        } else {
            it.tok(380f + impact * 0.6f, v, pan)
            it.click(v * 0.6f, pan)
        }
    }

    /** The ball dropping back on the tee after a splash. */
    fun pop(pan: Float) = play { it.tok(520f, 0.25f, pan) }

    /** Moving between screens: a soft breath of air. */
    fun whoosh() = play { it.sweep(0.12f, 0f, 380f, 500f, 1500f) }

    fun sand(pan: Float) = play {
        it.sweep(0.35f, pan, 260f, 1500f, 450f, q = 0.9f)
    }

    fun splash(pan: Float) = play {
        it.tok(95f, 0.45f, pan)
        it.sweep(0.6f, pan, 520f, 2800f, 320f, q = 0.8f)
        it.sweep(0.25f, pan, 380f, 4200f, 1400f, q = 1.2f, delayMs = 90f)
    }

    fun lipOut(pan: Float) = play {
        it.tok(560f, 0.55f, pan)
        it.tok(720f, 0.35f, pan, delayMs = 55f)
        it.tok(640f, 0.2f, pan, delayMs = 120f)
    }

    /** In the cup. [toPar] is strokes − par; hole-in-ones and birdies sing more. */
    fun cup(pan: Float, toPar: Int, holeInOne: Boolean) = play {
        it.tok(260f, 0.5f, pan)
        it.tok(330f, 0.35f, pan, delayMs = 70f)
        it.tok(210f, 0.3f, pan, delayMs = 130f)
        val root = 67f // G
        val notes = when {
            holeInOne -> floatArrayOf(0f, 4f, 7f, 12f, 16f, 19f, 24f)
            toPar < 0 -> floatArrayOf(0f, 4f, 7f, 12f, 16f)
            toPar == 0 -> floatArrayOf(0f, 4f, 7f, 12f)
            else -> floatArrayOf(0f, 3f, 7f)
        }
        notes.forEachIndexed { i, n ->
            it.mallet(Synth.midiToHz(root + n), 0.55f, pan * 0.5f, bright = 0.6f, delayMs = 240f + i * 105f)
        }
        if (holeInOne || toPar < 0) it.glass(Synth.midiToHz(root + 31f), 0.4f, 0f, delayMs = 240f + notes.size * 105f)
    }

    /** Distant surf for the island's ambience. */
    fun surf(pan: Float) = play {
        it.sweep(0.13f, pan, 2600f, 750f, 230f, q = 0.7f)
    }

    fun tap() = play { it.click(0.5f) }
}

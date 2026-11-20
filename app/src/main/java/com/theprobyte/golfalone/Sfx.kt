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

    /** Effects volume, 0..1 (0 is off). */
    var effectsVolume = 0.85f
        set(v) {
            field = v.coerceIn(0f, 1f)
            val g = field
            engine.post { it.effectsGain = g }
        }

    /** Distant surf, 0..1, relative to the effects volume. */
    var surfVolume = 0.7f

    val enabled get() = effectsVolume > 0f

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

    /** Off a ramp: a rising whoosh. */
    fun launch(speed: Float, pan: Float) = play {
        val v = (speed / 300f).coerceIn(0.2f, 1f)
        it.tok(300f, 0.35f * v, pan)
        it.sweep(0.25f + 0.25f * v, pan, 320f, 500f, 2400f)
    }

    /** Coming down: a soft thump, duller in sand. */
    fun land(impact: Float, pan: Float, sand: Boolean) = play {
        val v = (impact / 250f).coerceIn(0.1f, 1f)
        if (sand) it.sweep(0.3f * v, pan, 200f, 1200f, 400f, q = 0.9f)
        else it.tok(140f + impact * 0.2f, 0.6f * v, pan)
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
            toPar <= -2 -> floatArrayOf(0f, 4f, 7f, 12f, 16f, 19f)
            toPar < 0 -> floatArrayOf(0f, 4f, 7f, 12f, 16f)
            toPar == 0 -> floatArrayOf(0f, 4f, 7f, 12f)
            else -> floatArrayOf(0f, 3f, 7f)
        }
        notes.forEachIndexed { i, n ->
            it.mallet(Synth.midiToHz(root + n), 0.55f, pan * 0.5f, bright = 0.6f, delayMs = 240f + i * 105f)
        }
        if (holeInOne || toPar < 0) it.glass(Synth.midiToHz(root + 31f), 0.4f, 0f, delayMs = 240f + notes.size * 105f)
    }

    /** A course's own background sound now and then: frogs, crickets and a foghorn, lapping water, rain and thunder. */
    fun ambience(theme: String, pan: Float, roll: Float) {
        val v = surfVolume
        if (v <= 0f) return
        when (theme) {
            "marsh" -> play {
                val f = 90f + roll * 40f
                it.tok(f, 0.22f * v, pan); it.tok(f * 1.1f, 0.18f * v, pan, delayMs = 110f)
            }
            "light" -> play {
                if (roll < 0.15f) it.sweep(0.16f * v, pan, 1800f, 120f, 110f, q = 2f) // the foghorn, far off
                else for (k in 0 until 3) it.glass(Synth.midiToHz(98f + roll * 4f), 0.06f * v, pan, delayMs = k * 70f)
            }
            "lagoon" -> play { it.sweep(0.12f * v, pan, 900f, 400f, 700f, q = 0.6f) }
            "ember" -> play {
                it.sweep(0.22f * v, 0f, 2400f, 70f, 45f, q = 0.8f) // the mountain's rumble
                if (roll < 0.3f) it.sweep(0.08f * v, pan, 400f, 5000f, 3500f, q = 0.6f)
            }
            "frost" -> play {
                it.sweep(0.14f * v, pan, 2200f, 500f, 900f, q = 0.5f) // wind over the ice
                if (roll < 0.3f) it.glass(Synth.midiToHz(100f), 0.08f * v, pan, delayMs = 400f)
            }
            "temple" -> play {
                val n = 88f + roll * 8f
                for (k in 0 until 2 + (roll * 3).toInt()) it.glass(Synth.midiToHz(n + (k % 2) * 3f), 0.1f * v, pan, delayMs = k * 90f)
            }
            "bluff" -> play { it.sweep(0.2f * v, pan, 1800f, 300f, 700f, q = 0.5f) }
            "wreck" -> play {
                it.sweep(0.16f * v, pan, 2400f, 3000f, 2000f, q = 0.5f)
                if (roll < 0.12f) it.sweep(0.3f * v, 0f, 2600f, 90f, 40f, q = 0.7f, delayMs = 300f) // thunder
            }
        }
    }

    /** Distant surf for the island's ambience. */
    fun surf(pan: Float) {
        val v = surfVolume
        if (v <= 0f) return
        play { it.sweep(0.19f * v, pan, 2600f, 750f, 230f, q = 0.7f) }
    }

    fun tap() = play { it.click(0.5f) }

    /** The tide turning: a long wash in (rising) or out (falling). */
    fun tide(rising: Boolean) = play {
        if (rising) it.sweep(0.3f, 0f, 1400f, 300f, 900f, q = 0.7f) else it.sweep(0.25f, 0f, 1400f, 900f, 300f, q = 0.7f)
    }

    /** A bumper buoy's bell. */
    fun bell(pan: Float) = play {
        it.glass(Synth.midiToHz(84f), 0.5f, pan)
        it.tok(420f, 0.35f, pan)
    }

    /** A cracked plank giving way. */
    fun crack(pan: Float) = play {
        it.tok(180f, 0.7f, pan)
        it.click(0.8f, pan)
        it.sweep(0.35f, pan, 220f, 2600f, 600f, q = 1.1f)
        it.tok(120f, 0.4f, pan, delayMs = 40f)
    }

    /** Knocking a barrel: a hollow, low thunk. */
    fun barrel(impact: Float, pan: Float) = play {
        val v = (impact / 250f).coerceIn(0.12f, 1f)
        it.tok(110f + impact * 0.15f, v, pan)
        it.tok(220f, v * 0.3f, pan, delayMs = 12f)
    }

    /** A steam vent: a hiss of noise and a soft pop. */
    fun hiss(pan: Float) = play {
        it.sweep(0.35f, pan, 420f, 5200f, 3000f, q = 0.6f)
        it.tok(160f, 0.3f, pan)
    }

    /** A gate grinding open (or a decoy plate's click). */
    fun gate(opened: Boolean, pan: Float) = play {
        it.click(0.6f, pan)
        if (opened) { it.sweep(0.45f, 0f, 900f, 120f, 70f, q = 0.8f); it.tok(70f, 0.5f, 0f, delayMs = 600f) }
    }

    /** Thin ice cracking: a bright ping. */
    fun icePing(pan: Float) = play {
        it.glass(Synth.midiToHz(93f), 0.35f, pan)
        it.click(0.4f, pan)
    }

    /** Through a tunnel: a hollow whoomp in, and out. */
    fun tunnel(pan: Float) = play {
        it.tok(90f, 0.6f, pan)
        it.sweep(0.25f, pan, 300f, 300f, 900f, q = 1.4f, delayMs = 120f)
    }

    /** Into the lava: a sizzle. */
    fun sizzle(pan: Float) = play {
        it.sweep(0.5f, pan, 700f, 6000f, 2500f, q = 0.5f)
        it.tok(80f, 0.4f, pan)
    }

    /** A crab's scuttle. */
    fun scuttle(pan: Float) = play { it.click(0.25f, pan); it.click(0.2f, pan) }

    /** The result word landing on the card: a low, papery thump. */
    fun stamp() = play {
        it.tok(115f, 0.6f, 0f)
        it.sweep(0.18f, 0f, 90f, 900f, 300f, q = 0.8f)
    }
}

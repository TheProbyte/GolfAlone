package com.theprobyte.golfalone.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A small real-time synthesizer: a pool of voices (mallet, glass, wooden tok, felt
 * click, air), an optional ambient pad, a reverb and a soft limiter.
 *
 * Pure Kotlin with no Android dependencies, so it renders identically on the device
 * (via [AudioEngine]) and offline in unit tests. Not thread-safe: call it from the
 * audio thread only.
 */
class Synth(val sampleRate: Int) {

    enum class Kind { MALLET, GLASS, TOK, CLICK, AIR }

    /** Instrument colours for the melody voice. */
    enum class Timbre { KALIMBA, MARIMBA, MUSIC_BOX, BELL, FELT }

    /** Volume of all sound effects, 0..1. */
    var effectsGain = 0.8f
    /** Volume of the ambient pad, 0..1 (0 = off). */
    var ambienceGain = 0f

    private val sr = sampleRate.toFloat()
    private val voices = Array(28) { Voice() }
    private val reverb = Reverb(sampleRate)
    val pad = Pad(sampleRate)

    // master state
    private var lpL = 0f
    private var lpR = 0f
    private val lpCoef = onePole(8200f)
    private var limEnv = 0f
    private var duckFollow = 0f
    private var silentSamples = 0
    private var rng = 0x2545F491

    /**
     * True once nothing has been audible for about two seconds *and* nothing is
     * waiting to sound (a freshly started note or the ambience wakes it immediately).
     */
    val isIdle: Boolean
        get() {
            if (silentSamples <= sampleRate * 2 || ambienceGain > 0.0005f || pad.audible) return false
            for (v in voices) if (v.active) return false
            return true
        }

    // ------------------------------------------------------------------ voices

    private class Voice {
        var active = false
        var kind = Kind.MALLET
        var delay = 0
        var age = 0
        var attack = 1f
        var np = 0
        val inc = FloatArray(6)
        val ph = FloatArray(6)
        val amp = FloatArray(6)
        val env = FloatArray(6)
        val dec = FloatArray(6)
        var gl = 0.7f
        var gr = 0.7f
        var send = 0.3f
        // noise transient / body
        var nAmp = 0f
        var nEnv = 0f
        var nDec = 1f
        var nLp = 0f
        var nLpCoef = 0.5f
        // band-pass (state variable filter) on the noise
        var bpF = 0f
        var bpQ = 1f
        var low = 0f
        var band = 0f
        // pitch glide on the fundamental
        var glide = 0f
        var glideDec = 1f
        // AIR: swept band and sine-shaped envelope
        var dur = 1
        var sweepFrom = 0f
        var sweepTo = 0f
    }

    private fun freeVoice(): Voice {
        var best = voices[0]
        for (v in voices) {
            if (!v.active) return v
            if (v.age > best.age) best = v
        }
        return best // steal the oldest
    }

    private fun panGains(v: Voice, pan: Float) {
        val a = (pan.coerceIn(-1f, 1f) + 1f) * (PI.toFloat() / 4f)
        v.gl = cos(a); v.gr = sin(a)
    }

    private fun start(v: Voice, kind: Kind, delayMs: Float, pan: Float, send: Float) {
        silentSamples = 0
        v.active = true; v.kind = kind; v.age = 0
        v.delay = (delayMs * sr / 1000f).toInt().coerceAtLeast(0)
        v.np = 0; v.nEnv = 0f; v.glide = 0f; v.glideDec = 1f; v.low = 0f; v.band = 0f; v.nLp = 0f; v.bpF = 0f
        v.send = send
        panGains(v, pan)
    }

    private fun partial(v: Voice, freq: Float, amp: Float, decaySec: Float) {
        if (freq >= sr * 0.45f || v.np >= 6) return
        val i = v.np++
        v.inc[i] = freq / sr
        v.ph[i] = 0f
        v.amp[i] = amp
        v.env[i] = 1f
        v.dec[i] = exp(-1f / (decaySec * sr))
    }

    /**
     * A soft mallet on a glass-kalimba tine: a warm fundamental with a detuned twin,
     * a few inharmonic overtones and a felt "touch" of filtered noise. [bright] 0..1.
     */
    fun mallet(
        freq: Float, vel: Float, pan: Float, bright: Float = 0.6f, delayMs: Float = 0f,
        timbre: Timbre = Timbre.KALIMBA, sustain: Float = 1f, send: Float = 0.42f,
    ) {
        val v = freeVoice()
        start(v, Kind.MALLET, delayMs, pan, send)
        val b = bright.coerceIn(0f, 1f)
        val sus = sustain.coerceIn(0.3f, 3f)
        when (timbre) {
            Timbre.KALIMBA -> {
                val g = vel * 0.30f
                val len = (1.45f - 0.35f * b) * sus
                v.attack = 0.0025f * sr
                partial(v, freq, g, len)
                partial(v, freq * 1.0021f, g * 0.38f, len * 0.9f)       // +3.6 cents twin: gentle beating
                partial(v, freq * 2.01f, g * 0.13f * b, 0.42f * sus)
                partial(v, freq * 3.92f, g * 0.09f * b, 0.20f)
                partial(v, freq * 5.43f, g * 0.05f * b, 0.11f)
                partial(v, freq * 6.79f, g * 0.025f * b, 0.07f)
                noise(v, g * (0.10f + 0.1f * b), 0.004f, 2600f)
            }
            Timbre.MARIMBA -> { // warm wooden bar: strong fundamental, tuned overtone at ~4x
                val g = vel * 0.34f
                v.attack = 0.002f * sr
                partial(v, freq, g, 0.62f * sus)
                partial(v, freq * 1.0012f, g * 0.25f, 0.55f * sus)
                partial(v, freq * 3.93f, g * 0.22f * b, 0.13f)
                partial(v, freq * 9.2f, g * 0.05f * b, 0.035f)
                noise(v, g * 0.16f, 0.006f, 1200f)
            }
            Timbre.MUSIC_BOX -> { // small bright comb tines, an octave up
                val f = freq * 2f
                val g = vel * 0.22f
                v.attack = 0.001f * sr
                partial(v, f, g, 0.95f * sus)
                partial(v, f * 2.0f, g * 0.28f * (0.5f + b), 0.40f)
                partial(v, f * 3.0f, g * 0.11f * b, 0.24f)
                partial(v, f * 4.07f, g * 0.07f * b, 0.14f)
                partial(v, f * 5.9f, g * 0.035f * b, 0.08f)
                noise(v, g * 0.06f, 0.002f, 5000f)
            }
            Timbre.BELL -> { // soft glass bell, long ring
                val g = vel * 0.24f
                v.attack = 0.004f * sr
                partial(v, freq, g, 1.9f * sus)
                partial(v, freq * 1.002f, g * 0.30f, 1.6f * sus)
                partial(v, freq * 2.0f, g * 0.22f, 0.95f)
                partial(v, freq * 2.76f, g * 0.26f * b, 0.75f)
                partial(v, freq * 5.4f, g * 0.08f * b, 0.32f)
                noise(v, g * 0.05f, 0.003f, 3000f)
            }
            Timbre.FELT -> { // felt piano: soft hammer on a warm string
                val g = vel * 0.26f
                v.attack = 0.006f * sr
                partial(v, freq, g, 1.25f * sus)
                partial(v, freq * 0.998f, g * 0.30f, 1.1f * sus)
                partial(v, freq * 2f, g * 0.42f * (0.5f + b * 0.5f), 0.70f)
                partial(v, freq * 3f, g * 0.18f * b, 0.45f)
                partial(v, freq * 4f, g * 0.09f * b, 0.30f)
                partial(v, freq * 5f, g * 0.045f * b, 0.20f)
                noise(v, g * 0.12f, 0.008f, 900f)
            }
        }
    }

    private fun noise(v: Voice, amp: Float, decaySec: Float, lowpassHz: Float) {
        v.nAmp = amp; v.nEnv = 1f; v.nDec = exp(-1f / (decaySec * sr)); v.nLpCoef = onePole(lowpassHz)
    }

    /** A bell-like glass tone (hints, cadences). */
    fun glass(freq: Float, vel: Float, pan: Float, delayMs: Float = 0f) {
        val v = freeVoice()
        start(v, Kind.GLASS, delayMs, pan, 0.6f)
        val g = vel * 0.22f
        v.attack = 0.008f * sr
        partial(v, freq, g, 2.0f)
        partial(v, freq * 1.0015f, g * 0.3f, 1.8f)
        partial(v, freq * 2.756f, g * 0.30f, 0.9f)
        partial(v, freq * 5.404f, g * 0.10f, 0.40f)
        partial(v, freq * 8.933f, g * 0.035f, 0.18f)
    }

    /** A muted wooden "tok": a low body with a pitch drop and a filtered knock. */
    fun tok(freq: Float, vel: Float, pan: Float, delayMs: Float = 0f) {
        val v = freeVoice()
        start(v, Kind.TOK, delayMs, pan, 0.25f)
        val g = vel * 0.42f
        v.attack = 0.0015f * sr
        partial(v, freq, g, 0.085f)
        partial(v, freq * 2.03f, g * 0.18f, 0.035f)
        v.glide = 0.55f; v.glideDec = exp(-1f / (0.018f * sr))
        v.nAmp = g * 0.9f; v.nEnv = 1f; v.nDec = exp(-1f / (0.012f * sr))
        v.bpF = bandF(freq * 6.5f); v.bpQ = 1.1f
    }

    /** A soft felt click for UI. */
    fun click(vel: Float, pan: Float = 0f) {
        val v = freeVoice()
        start(v, Kind.CLICK, 0f, pan, 0.12f)
        val g = vel * 0.16f
        v.attack = 0.0006f * sr
        partial(v, 1650f, g * 0.5f, 0.012f)
        v.nAmp = g * 1.6f; v.nEnv = 1f; v.nDec = exp(-1f / (0.005f * sr))
        v.bpF = bandF(2300f); v.bpQ = 1.4f
    }

    /** A barely audible filtered breath of air (screen transitions). */
    fun air(vel: Float, pan: Float = 0f, durMs: Float = 300f) {
        val v = freeVoice()
        start(v, Kind.AIR, 0f, pan, 0.35f)
        v.attack = 1f
        v.dur = (durMs * sr / 1000f).toInt().coerceAtLeast(1)
        v.nAmp = vel * 0.32f; v.nEnv = 1f; v.nDec = 1f
        v.sweepFrom = 380f; v.sweepTo = 1500f; v.bpQ = 1.6f
        v.bpF = bandF(v.sweepFrom)
    }

    /**
     * A band-passed noise swell sweeping [fromHz] to [toHz] over [durMs]: whooshes,
     * splashes, sand and distant surf.
     */
    fun sweep(vel: Float, pan: Float, durMs: Float, fromHz: Float, toHz: Float, q: Float = 1.6f, delayMs: Float = 0f) {
        val v = freeVoice()
        start(v, Kind.AIR, delayMs, pan, 0.35f)
        v.attack = 1f
        v.dur = (durMs * sr / 1000f).toInt().coerceAtLeast(1)
        v.nAmp = vel * 0.32f; v.nEnv = 1f; v.nDec = 1f
        v.sweepFrom = fromHz; v.sweepTo = toHz; v.bpQ = q
        v.bpF = bandF(v.sweepFrom)
    }

    // --------------------------------------------------------------- rendering

    private fun noise(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rng = x
        return (x and 0xFFFFFF) / 8388608f - 1f
    }

    private val mixL = FloatArray(4096)
    private val mixR = FloatArray(4096)
    private val sendBuf = FloatArray(4096)

    /** Renders [frames] stereo frames, interleaved, into [out] starting at 0. */
    fun render(out: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = min(4096, frames - done)
            renderBlock(out, done, n)
            done += n
        }
    }

    private fun renderBlock(out: FloatArray, offset: Int, frames: Int) {
        java.util.Arrays.fill(mixL, 0, frames, 0f)
        java.util.Arrays.fill(mixR, 0, frames, 0f)
        java.util.Arrays.fill(sendBuf, 0, frames, 0f)
        var anyVoice = false
        for (v in voices) {
            if (!v.active) continue
            anyVoice = true
            renderVoice(v, frames)
        }

        // pad (ambience) is rendered into the same buses, ducked under the effects
        pad.gain = ambienceGain
        val padOn = pad.audible
        var peak = 0f
        for (i in 0 until frames) {
            val fxL = mixL[i] * effectsGain
            val fxR = mixR[i] * effectsGain
            val fxLevel = abs(fxL) + abs(fxR)
            duckFollow = if (fxLevel > duckFollow) duckFollow + (fxLevel - duckFollow) * 0.01f else duckFollow * 0.99995f
            var l = fxL
            var r = fxR
            var send = sendBuf[i] * effectsGain
            if (padOn) {
                val duck = 1f - min(0.55f, duckFollow * 3f)
                pad.next()
                l += pad.outL * duck
                r += pad.outR * duck
                send += (pad.outL + pad.outR) * 0.25f * duck
            }
            reverb.process(send)
            l += reverb.outL
            r += reverb.outR
            // gentle master low-pass: nothing sharp
            lpL += (l - lpL) * lpCoef
            lpR += (r - lpR) * lpCoef
            // soft-knee limiter
            val p = max(abs(lpL), abs(lpR))
            limEnv = if (p > limEnv) limEnv + (p - limEnv) * 0.3f else limEnv + (p - limEnv) * 0.0004f
            val gain = if (limEnv > 0.62f) 0.62f / limEnv else 1f
            val oL = softClip(lpL * gain)
            val oR = softClip(lpR * gain)
            out[(offset + i) * 2] = oL
            out[(offset + i) * 2 + 1] = oR
            peak = max(peak, max(abs(oL), abs(oR)))
        }
        silentSamples = if (!anyVoice && !padOn && peak < 1e-4f) silentSamples + frames else 0
    }

    private fun softClip(x: Float): Float = when {
        x > 1f -> 0.95f
        x < -1f -> -0.95f
        else -> x - 0.05f * x * x * x
    }

    private fun renderVoice(v: Voice, frames: Int) {
        var start = 0
        if (v.delay > 0) {
            if (v.delay >= frames) { v.delay -= frames; return }
            start = v.delay; v.delay = 0
        }
        val np = v.np
        val air = v.kind == Kind.AIR
        for (i in start until frames) {
            var s = 0f
            val gm = 1f + v.glide
            v.glide *= v.glideDec
            for (p in 0 until np) {
                var ph = v.ph[p] + v.inc[p] * (if (p == 0) gm else 1f)
                if (ph >= 1f) ph -= 1f
                v.ph[p] = ph
                s += v.amp[p] * v.env[p] * Wave.sin(ph)
                v.env[p] *= v.dec[p]
            }
            if (v.nEnv > 1e-5f) {
                val w = noise()
                val src = if (v.bpF > 0f) {
                    if (air) {
                        val k = v.age / v.dur.toFloat()
                        v.bpF = bandF(v.sweepFrom + (v.sweepTo - v.sweepFrom) * k)
                    }
                    v.low += v.bpF * v.band
                    val high = w - v.low - v.bpQ * v.band
                    v.band += v.bpF * high
                    v.band
                } else {
                    v.nLp += (w - v.nLp) * v.nLpCoef
                    v.nLp
                }
                val e = if (air) sin(PI.toFloat() * min(1f, v.age / v.dur.toFloat())) else v.nEnv
                s += v.nAmp * e * src
                if (!air) v.nEnv *= v.nDec
            }
            if (v.age < v.attack) s *= v.age / v.attack
            mixL[i] += s * v.gl
            mixR[i] += s * v.gr
            sendBuf[i] += s * v.send
            v.age++
        }
        // finished?
        if (air) {
            if (v.age >= v.dur) v.active = false
            return
        }
        // Stop once the voice is ~60 dB down: inaudible, and lets the engine go idle sooner.
        var alive = v.nEnv * v.nAmp > 1e-4f
        var level = 0f
        for (p in 0 until np) level += v.env[p] * v.amp[p]
        if (level > 3e-4f) alive = true
        if (!alive) v.active = false
    }

    private fun onePole(hz: Float): Float = 1f - exp(-2f * PI.toFloat() * hz / sr)
    private fun bandF(hz: Float): Float = 2f * sin(PI.toFloat() * min(hz, sr * 0.2f) / sr)

    companion object {
        fun midiToHz(m: Float): Float = 440f * Math.pow(2.0, ((m - 69f) / 12f).toDouble()).toFloat()
    }
}

/** Shared sine table with linear interpolation. */
internal object Wave {
    private const val SIZE = 4096
    private val table = FloatArray(SIZE + 1) { sin(2.0 * PI * it / SIZE).toFloat() }

    fun sin(phase: Float): Float {
        val x = phase * SIZE
        val i = x.toInt()
        val f = x - i
        val a = table[i and (SIZE - 1)]
        val b = table[(i and (SIZE - 1)) + 1]
        return a + (b - a) * f
    }
}

/**
 * A short, soft, dark reverb (Freeverb-style: parallel damped combs into series
 * all-passes), slightly decorrelated between left and right.
 */
class Reverb(sampleRate: Int) {
    private val scale = sampleRate / 44100f
    private val combL = arrayOf(1116, 1188, 1277, 1356, 1422, 1491).map { Comb((it * scale).toInt()) }
    private val combR = arrayOf(1139, 1211, 1300, 1379, 1445, 1514).map { Comb((it * scale).toInt()) }
    private val apL = arrayOf(556, 441, 341).map { AllPass((it * scale).toInt()) }
    private val apR = arrayOf(579, 464, 364).map { AllPass((it * scale).toInt()) }
    var outL = 0f; private set
    var outR = 0f; private set
    private val wet = 0.30f

    fun process(input: Float) {
        val x = input * 0.012f
        var l = 0f
        var r = 0f
        for (c in combL) l += c.process(x)
        for (c in combR) r += c.process(x)
        for (a in apL) l = a.process(l)
        for (a in apR) r = a.process(r)
        outL = l * wet
        outR = r * wet
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(max(1, size))
        private var i = 0
        private var store = 0f
        fun process(x: Float): Float {
            val y = buf[i]
            store = y * 0.62f + store * 0.38f // damping: darker tail
            buf[i] = x + store * 0.835f        // room size
            i = if (i + 1 == buf.size) 0 else i + 1
            return y
        }
    }

    private class AllPass(size: Int) {
        private val buf = FloatArray(max(1, size))
        private var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * 0.5f
            i = if (i + 1 == buf.size) 0 else i + 1
            return b - x
        }
    }
}

/**
 * The optional ambience: a very quiet evolving pad on the current chord, with slow
 * breathing amplitude, a drifting low-pass and a faint air texture. Chord changes
 * glide over a few seconds.
 */
class Pad(sampleRate: Int) {
    private val sr = sampleRate.toFloat()
    var gain = 0f
    private var level = 0f
    var outL = 0f; private set
    var outR = 0f; private set

    private val tones = 4
    private val freq = FloatArray(tones) { 110f * (it + 1) }
    private val target = FloatArray(tones) { 110f * (it + 1) }
    private val ph = FloatArray(tones * 2)
    private val lfo = FloatArray(tones) { it * 0.37f }
    private val lfoInc = FloatArray(tones) { (1f / (7f + it * 1.9f)) / sampleRate }
    private var filt = 0f
    private var filtR = 0f
    private var sweep = 0f
    private var airLp = 0f
    private var airLp2 = 0f
    private var airLp3 = 0f
    private var rng = 0x1234567
    private var counter = 0

    val audible get() = gain > 0.0005f || level > 1e-5f

    /** Sets the chord (Hz for up to four tones); the pad glides there slowly. */
    fun chord(hz: FloatArray) {
        for (i in 0 until tones) target[i] = hz[i % hz.size]
    }

    fun next() {
        // level follows the gain slowly (fade in/out over ~2 s)
        level += (gain * 0.055f - level) * (1f / (sr * 0.6f))
        if (++counter >= 64) {
            counter = 0
            for (i in 0 until tones) freq[i] += (target[i] - freq[i]) * 0.004f
        }
        var l = 0f
        var r = 0f
        for (i in 0 until tones) {
            lfo[i] += lfoInc[i]; if (lfo[i] >= 1f) lfo[i] -= 1f
            val breath = 0.55f + 0.45f * Wave.sin(lfo[i])
            var p0 = ph[i * 2] + freq[i] / sr; if (p0 >= 1f) p0 -= 1f; ph[i * 2] = p0
            var p1 = ph[i * 2 + 1] + freq[i] * 1.003f / sr; if (p1 >= 1f) p1 -= 1f; ph[i * 2 + 1] = p1
            val a = Wave.sin(p0) + 0.25f * Wave.sin(if (p0 * 2f >= 1f) p0 * 2f - 1f else p0 * 2f)
            val b = Wave.sin(p1)
            val pan = (i - 1.5f) / 3f
            l += (a * (0.6f - pan * 0.4f) + b * 0.4f) * breath
            r += (a * (0.6f + pan * 0.4f) + b * 0.4f) * breath
        }
        // slow filter drift
        sweep += 1f / (sr * 23f); if (sweep >= 1f) sweep -= 1f
        val cutoff = 650f + 500f * (0.5f + 0.5f * Wave.sin(sweep))
        val k = 1f - exp(-2f * PI.toFloat() * cutoff / sr)
        filt += (l - filt) * k
        filtR += (r - filtR) * k
        // faint air
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); rng = x
        val w = (x and 0xFFFFFF) / 8388608f - 1f
        // three cascaded poles (~18 dB/oct): a low, dark rumble of air, no hiss
        airLp += (w - airLp) * 0.012f
        airLp2 += (airLp - airLp2) * 0.012f
        airLp3 += (airLp2 - airLp3) * 0.012f
        outL = (filt * 0.25f + airLp3 * 2.2f) * level
        outR = (filtR * 0.25f + airLp3 * 2.2f) * level
    }
}

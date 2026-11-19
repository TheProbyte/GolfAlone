package com.theprobyte.golfalone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Streams [Synth] to an AudioTrack on its own thread. Events are queued from the UI
 * thread and applied at the start of the next audio block. After a couple of seconds
 * of silence the stream pauses (no cost at rest) and any new sound wakes it again.
 *
 * Output is opened as low-latency float where the device allows it, falling back to
 * default latency and then to 16-bit PCM. If the output dies (e.g. the audio route
 * changes) it is recreated on the next sound.
 */
class AudioEngine(context: Context) {

    private val sampleRate: Int = run {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
    }
    private val burst: Int = run {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 256
    }

    val synth = Synth(sampleRate)
    private val queue = ConcurrentLinkedQueue<(Synth) -> Unit>()
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var paused = false // app in background

    private var track: AudioTrack? = null
    private var shortOutput = false
    private var failedAt = 0L

    init {
        Thread({ loop() }, "golf-audio").apply {
            isDaemon = true
            start()
        }
    }

    /** Runs [cmd] on the audio thread before the next block. */
    fun post(cmd: (Synth) -> Unit) {
        queue.add(cmd)
        synchronized(lock) { lock.notifyAll() }
    }

    fun onPause() { paused = true }

    fun onResume() {
        paused = false
        synchronized(lock) { lock.notifyAll() }
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun build(encoding: Int, lowLatency: Boolean): AudioTrack? = try {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        if (minBytes <= 0) null else {
            val bytes = maxOf(minBytes, burst * 2 * bytesPerSample * 4)
            val b = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            b.build().takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: null
        }
    } catch (e: Exception) {
        Log.w(TAG, "AudioTrack ($encoding, lowLatency=$lowLatency) failed", e)
        null
    }

    private fun openTrack(): AudioTrack? {
        build(AudioFormat.ENCODING_PCM_FLOAT, true)?.let { shortOutput = false; return it }
        build(AudioFormat.ENCODING_PCM_FLOAT, false)?.let { shortOutput = false; return it }
        build(AudioFormat.ENCODING_PCM_16BIT, false)?.let { shortOutput = true; return it }
        return null
    }

    private fun closeTrack() {
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frames = burst.coerceIn(128, 512)
        val buf = FloatArray(frames * 2)
        // Written as raw bytes in native order: works for float and 16-bit output alike.
        val bytes = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        // Keep at most ~120 ms of audio ahead of real time (guards against outputs
        // whose write() does not block).
        var aheadStart = 0L
        var written = 0L
        while (running) {
            var cmd = queue.poll()
            while (cmd != null) {
                try { cmd(synth) } catch (e: Exception) { Log.w(TAG, "audio command failed", e) }
                cmd = queue.poll()
            }

            if (paused || synth.isIdle) {
                try { track?.pause() } catch (_: Exception) {}
                synchronized(lock) {
                    if (running && queue.isEmpty() && (paused || synth.isIdle)) {
                        try { lock.wait(if (paused) 0L else 2000L) } catch (_: InterruptedException) {}
                    }
                }
                aheadStart = 0L
                continue
            }

            var t = track
            if (t == null && wallMs() - failedAt > 3000) {
                t = openTrack()
                track = t
                if (t == null) failedAt = wallMs()
            }
            synth.render(buf, frames)
            if (t == null) {
                // No output available right now: keep time moving, try again later.
                sleepMs((frames * 1000L / sampleRate).coerceAtLeast(1))
                continue
            }
            try {
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                bytes.clear()
                if (shortOutput) {
                    for (v in buf) bytes.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                } else {
                    for (v in buf) bytes.putFloat(v)
                }
                bytes.flip()
                val result = t.write(bytes, bytes.remaining(), AudioTrack.WRITE_BLOCKING)
                if (result < 0) {
                    Log.w(TAG, "AudioTrack write failed ($result); reopening")
                    closeTrack()
                    continue
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack error; reopening", e)
                closeTrack()
                continue
            }
            if (aheadStart == 0L) { aheadStart = wallMs(); written = 0L }
            written += frames
            val audioMs = written * 1000L / sampleRate
            val elapsed = wallMs() - aheadStart
            if (audioMs - elapsed > 120) sleepMs(audioMs - elapsed - 100)
        }
        closeTrack()
    }

    // Real wall-clock time for pacing (not the UI clock, which tests may drive).
    private fun wallMs() = System.nanoTime() / 1_000_000L

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private companion object {
        const val TAG = "ArrowAudio"
    }
}

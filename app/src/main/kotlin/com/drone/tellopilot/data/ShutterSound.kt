package com.drone.tellopilot.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Camera shutter sound: confirms a photo without anything on screen (the pilot keeps a clear
 * view of the video).
 *
 * Plays the phone's own camera click (`camera_click.ogg`, the file MediaActionSound uses) with a
 * preloaded [SoundPool], on the media usage: the volume keys control it in the app, and it is not
 * muted with the system sounds (MediaActionSound plays them on the system/enforced stream, which
 * stays silent on Pixels). Without that file, or if it cannot be decoded, a mechanical shutter is
 * synthesised instead ([ShutterSynth]).
 */
@Singleton
class ShutterSound @Inject constructor() {

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool: SoundPool? = runCatching {
        SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
    }.onFailure { Log.w(TAG, "no SoundPool", it) }.getOrNull()

    /** Sample id of the system click, 0 until it is decoded (or when there is none). */
    @Volatile private var systemSoundId = 0

    /** System files still to try, in order (a file that fails to decode moves on to the next). */
    private val candidates: Iterator<File> = SYSTEM_SOUND_PATHS.asSequence().map(::File)
        .filter { runCatching { it.isFile && it.canRead() }.getOrDefault(false) }
        .iterator()

    init {
        pool?.setOnLoadCompleteListener { soundPool, sampleId, status ->
            if (status == 0) {
                systemSoundId = sampleId
            } else {
                Log.w(TAG, "system shutter sound not decoded (status $status)")
                soundPool.unload(sampleId)
                loadNextSystemSound()
            }
        }
        loadNextSystemSound()
    }

    @Synchronized
    private fun loadNextSystemSound() {
        val p = pool ?: return
        while (candidates.hasNext()) {
            val file = candidates.next()
            val id = runCatching { p.load(file.path, 1) }.getOrDefault(0)
            if (id != 0) {
                Log.i(TAG, "shutter sound: ${file.path}")
                return
            }
        }
        Log.i(TAG, "no usable system shutter sound: synthesised one")
    }

    private val synthesized: AudioTrack? by lazy {
        runCatching {
            val pcm = ShutterSynth.synthesize(ShutterSynth.SAMPLE_RATE)
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ShutterSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply { write(pcm, 0, pcm.size) }
        }.onFailure { Log.w(TAG, "synthesised shutter unavailable", it) }.getOrNull()
    }

    @Synchronized
    fun play() {
        val id = systemSoundId
        val p = pool
        if (id != 0 && p != null && runCatching { p.play(id, 1f, 1f, 1, 0, 1f) }.getOrDefault(0) != 0) return
        val t = synthesized ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }.onFailure { Log.w(TAG, "cannot play", it) }
    }

    private companion object {
        const val TAG = "ShutterSound"

        /**
         * Where Android keeps the camera click: `/product` since Android 10 (Pixels included,
         * AOSP's AudioPackage copies `camera_click_48k.ogg` there), `/system` before and on many
         * other brands; the other partitions for OEM builds that move their sounds there.
         */
        val SYSTEM_SOUND_PATHS = listOf(
            "/product/media/audio/ui/camera_click.ogg",
            "/system/media/audio/ui/camera_click.ogg",
            "/system_ext/media/audio/ui/camera_click.ogg",
            "/vendor/media/audio/ui/camera_click.ogg",
            "/odm/media/audio/ui/camera_click.ogg",
        )
    }
}

/**
 * A mechanical camera shutter (~220 ms, deterministic): the first curtain slaps open, the second
 * one closes 48 ms later, then the mechanism re-cocks (a short ratchet and a softer, darker
 * click). Each click is a very short band-passed noise transient, a low "body" thump and the
 * short metallic ring of the camera body.
 */
internal object ShutterSynth {
    const val SAMPLE_RATE = 44_100
    const val DURATION_MS = 220

    private class Click(
        val startMs: Double,
        val gain: Double,
        /** Scales the body / ring frequencies (the re-cock click is darker). */
        val pitch: Double,
        /** Noise transient decay. */
        val snapMs: Double,
    )

    private val CLICKS = listOf(
        Click(0.0, 1.0, 1.0, 2.2), // first curtain
        Click(48.0, 0.78, 1.08, 2.0), // second curtain
        Click(150.0, 0.42, 0.78, 3.2), // re-cocking
    )

    /** Ring partials of the body: frequency (Hz), level, decay (ms). */
    private val RING = listOf(
        Triple(2_350.0, 0.26, 16.0),
        Triple(3_870.0, 0.18, 11.0),
        Triple(5_600.0, 0.11, 7.0),
    )

    fun synthesize(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val n = sampleRate * DURATION_MS / 1000
        val signal = DoubleArray(n)
        val random = Random(7)
        // Band-passed noise (~700 Hz – 7 kHz): a click rather than a hiss or a thud.
        val hi = 1 - exp(-2 * PI * 7_000 / sampleRate)
        val lo = 1 - exp(-2 * PI * 700 / sampleRate)
        var lpHigh = 0.0
        var lpLow = 0.0
        val phases = DoubleArray(RING.size * CLICKS.size) { random.nextDouble(0.0, 2 * PI) }
        for (i in 0 until n) {
            val tMs = i * 1000.0 / sampleRate
            val white = random.nextDouble(-1.0, 1.0)
            lpHigh += hi * (white - lpHigh)
            lpLow += lo * (white - lpLow)
            val noise = lpHigh - lpLow
            var s = 0.0
            CLICKS.forEachIndexed { c, click ->
                val dt = tMs - click.startMs
                if (dt < 0) return@forEachIndexed
                val attack = (dt / 0.25).coerceAtMost(1.0)
                val sec = dt / 1000
                s += click.gain * attack * 1.6 * noise * exp(-dt / click.snapMs)
                s += click.gain * 0.45 * sin(2 * PI * 150 * click.pitch * sec) * exp(-dt / 10.0)
                RING.forEachIndexed { r, (freq, level, decay) ->
                    s += click.gain * attack * level *
                        sin(2 * PI * freq * click.pitch * sec + phases[c * RING.size + r]) * exp(-dt / decay)
                }
            }
            // Spring / gear ratchet just before the re-cock click.
            if (tMs in 112.0..148.0) {
                val tick = (tMs - 112.0) % 6.0
                s += 0.12 * (0.4 + (tMs - 112.0) / 60) * noise * exp(-tick / 0.6)
            }
            signal[i] = s
        }
        // Short fade-out so that the end never clicks, then normalise.
        val fade = sampleRate * 12 / 1000
        for (k in 0 until fade) signal[n - 1 - k] *= k.toDouble() / fade
        val peak = signal.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = PEAK * Short.MAX_VALUE / peak
        return ShortArray(n) { (signal[it] * scale).toInt().coerceIn(-32767, 32767).toShort() }
    }

    const val PEAK = 0.89
}

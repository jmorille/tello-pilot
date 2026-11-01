package com.drone.tellopilot.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.random.Random

/**
 * Camera shutter sound: confirms a photo without anything on screen (the pilot keeps a clear
 * view of the video). Synthesised once ("clic-clac": two short damped noise bursts) instead of
 * the system's MediaActionSound, whose sound file is missing on some phones (silent on Pixels),
 * and played on the media volume, the one the volume keys control in the app.
 */
@Singleton
class ShutterSound @Inject constructor() {
    private val track: AudioTrack? by lazy {
        runCatching {
            val pcm = synthesize()
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply { write(pcm, 0, pcm.size) }
        }.onFailure { Log.w("ShutterSound", "unavailable", it) }.getOrNull()
    }

    @Synchronized
    fun play() {
        val t = track ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }.onFailure { Log.w("ShutterSound", "cannot play", it) }
    }

    private fun synthesize(): ShortArray {
        val length = SAMPLE_RATE * DURATION_MS / 1000
        val out = ShortArray(length)
        val random = Random(42)
        var low = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / SAMPLE_RATE
            // Two clicks (shutter opens, then closes), each a fast-decaying burst.
            val env = burst(t, 0.0, 0.012) + 0.8 * burst(t, 0.075, 0.016)
            // Slightly low-passed noise: a mechanical click rather than a hiss.
            low += 0.45 * (random.nextDouble(-1.0, 1.0) - low)
            out[i] = (low * env * 0.9 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun burst(t: Double, start: Double, decay: Double): Double =
        if (t < start) 0.0 else exp(-(t - start) / decay)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val DURATION_MS = 160
    }
}

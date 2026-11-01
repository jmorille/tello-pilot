package com.drone.tellopilot.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.drone.tello.control.BatteryAnnouncement
import com.drone.tello.control.BatteryAnnouncer
import com.drone.tello.control.BatteryReading
import com.drone.tello.protocol.FlightData
import com.drone.tellopilot.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Low / critical battery alert in flight: a tone, then a French spoken announcement
 * ("Batterie faible, 20 pour cent", "Batterie critique, atterrissez"), with a vibration.
 * [BatteryAnnouncer] decides when; this class only plays.
 *
 * Audio usage: [AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE] (speech), the usage of
 * spoken GPS directions: it follows the media volume (audible as soon as the media volume is
 * up, also in silent / vibrate mode, unlike notifications or system sounds), and Android treats
 * it as a short spoken prompt over other audio. Transient focus with ducking: a video or music
 * playing on the phone is lowered for the announcement, not stopped. The Tello video has no
 * sound, so the media volume may well be at zero: the alert then uses the alarm usage instead
 * (a safety warning must not be silent).
 *
 * Without a TextToSpeech engine or French voice, the tone and vibration alone are played.
 */
@Singleton
class BatteryVoiceAlert @Inject constructor(
    @ApplicationContext private val context: Context,
    private val haptics: Haptics,
) {
    /** One per process: what was already announced survives the reconnections. */
    private val announcer = BatteryAnnouncer()

    private val guidance = attributes(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
    private val alarm = attributes(AudioAttributes.USAGE_ALARM)

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val guidanceFocus = focusRequest(guidance)
    private val alarmFocus = focusRequest(alarm)

    private val lock = Any()
    /** Running [watch] calls: the voice is released when the last one ends. */
    private var watchers = 0

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    /** No new engine before this time (a failing engine is not retried every second). */
    @Volatile private var nextTtsAttemptMs = 0L
    private val utteranceIds = AtomicInteger()
    @Volatile private var speaking: Pair<String, CompletableDeferred<Unit>>? = null

    /** Static tone tracks by (critical, alarm usage), recreated after a playback failure. */
    private val tones = HashMap<Pair<Boolean, Boolean>, AudioTrack>()

    /**
     * Watches the battery until cancelled (sampled every [POLL_MS]: the critical reminder is
     * time based). [flight] gives the live flight data, `null` when not connected.
     */
    suspend fun watch(flight: () -> FlightData?, settings: Flow<AppSettings>) {
        synchronized(lock) { watchers++ }
        try {
            coroutineScope {
                val latest = settings.stateIn(this, SharingStarted.Eagerly, null)
                while (true) {
                    delay(POLL_MS)
                    val s = latest.value ?: continue
                    if (s.pilot.batteryVoiceAlert) ensureTts()
                    val reading = flight()?.let {
                        BatteryReading(it.batteryPercent, it.batteryLow, it.batteryCritical, airborne = it.flying || it.motorsOn)
                    }
                    // Always fed (even disabled): turning the alert on mid-flight does not replay old news.
                    val announcement = synchronized(lock) {
                        announcer.update(reading, SystemClock.elapsedRealtime(), s.drone.lowBatteryPercent)
                    }
                    if (announcement != null && s.pilot.batteryVoiceAlert) {
                        Log.i(TAG, "announce $announcement")
                        announce(announcement)
                    }
                }
            }
        } finally {
            val last = synchronized(lock) { --watchers == 0 }
            if (last) stopTts()
        }
    }

    private suspend fun announce(a: BatteryAnnouncement) {
        val critical = a is BatteryAnnouncement.Critical
        if (critical) haptics.critical() else haptics.warning()
        val text = when (a) {
            is BatteryAnnouncement.Low -> context.getString(R.string.battery_voice_low, a.percent)
            is BatteryAnnouncement.Critical -> context.getString(R.string.battery_voice_critical)
        }
        val useAlarm = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false)
        val focus = if (useAlarm) alarmFocus else guidanceFocus
        // The alert plays even if the focus is refused (a call…): it is a safety warning.
        runCatching { audioManager?.requestAudioFocus(focus) }
        try {
            if (playTone(critical, useAlarm)) {
                delay((if (critical) BatteryTones.CRITICAL_MS else BatteryTones.LOW_MS) + TONE_GAP_MS)
            }
            speak(text, if (useAlarm) alarm else guidance)
        } finally {
            runCatching { audioManager?.abandonAudioFocusRequest(focus) }
        }
    }

    private fun playTone(critical: Boolean, useAlarm: Boolean): Boolean {
        val key = critical to useAlarm
        val track = synchronized(tones) {
            tones[key] ?: track(
                if (critical) BatteryTones.critical() else BatteryTones.low(),
                if (useAlarm) alarm else guidance,
            )?.also { tones[key] = it }
        } ?: return false
        return runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }.onFailure {
            // Dead track (audio server restarted…): rebuilt next time.
            Log.w(TAG, "alert tone failed", it)
            synchronized(tones) { tones.remove(key) }
            runCatching { track.release() }
        }.isSuccess
    }

    /** Speaks and waits for the end of the utterance (bounded); nothing without a French voice. */
    private suspend fun speak(text: String, attributes: AudioAttributes) {
        val engine = tts?.takeIf { ttsReady } ?: return
        val id = "battery-${utteranceIds.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        speaking = id to done
        val queued = runCatching {
            engine.setAudioAttributes(attributes)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (queued == TextToSpeech.SUCCESS) withTimeoutOrNull(SPEECH_TIMEOUT_MS) { done.await() }
        speaking = null
    }

    /** Creates the engine if there is none (at most every [TTS_RETRY_MS] after a failure). */
    private fun ensureTts() {
        val now = SystemClock.elapsedRealtime()
        if (tts != null || now < nextTtsAttemptMs) return
        nextTtsAttemptMs = now + TTS_RETRY_MS
        ttsReady = false
        // onInit may come before the constructor returns (no engine at all): kept for after.
        val created = AtomicReference<TextToSpeech?>()
        val early = AtomicReference<Int?>()
        val engine = runCatching {
            TextToSpeech(context) { status ->
                val e = created.get()
                if (e == null) early.set(status) else onTtsInit(e, status)
            }
        }.onFailure { Log.w(TAG, "no TextToSpeech", it) }.getOrNull() ?: return
        tts = engine
        created.set(engine)
        early.get()?.let { onTtsInit(engine, it) }
    }

    private fun onTtsInit(engine: TextToSpeech, status: Int) {
        if (engine !== tts) return // an engine already replaced or released
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed ($status): tone only, retry later")
            tts = null
            runCatching { engine.shutdown() }
            return
        }
        runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            val lang = engine.setLanguage(Locale.FRENCH)
            ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) Log.w(TAG, "no French voice ($lang): tone only")
        }.onFailure { Log.w(TAG, "TextToSpeech setup failed: tone only", it) }
    }

    private fun finished(utteranceId: String?) {
        speaking?.takeIf { it.first == utteranceId }?.second?.complete(Unit)
    }

    private fun stopTts() {
        ttsReady = false
        nextTtsAttemptMs = 0L
        val engine = tts ?: return
        tts = null
        runCatching { engine.shutdown() }
    }

    private fun track(pcm: ShortArray, attributes: AudioAttributes): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(BatteryTones.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .apply { write(pcm, 0, pcm.size) }
    }.onFailure { Log.w(TAG, "alert tone unavailable", it) }.getOrNull()

    private companion object {
        const val TAG = "BatteryVoiceAlert"
        const val POLL_MS = 1_000L
        const val TONE_GAP_MS = 120L
        const val SPEECH_TIMEOUT_MS = 5_000L
        const val TTS_RETRY_MS = 30_000L

        fun attributes(usage: Int): AudioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun focusRequest(attributes: AudioAttributes): AudioFocusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
    }
}

/** Alert tones (pure PCM synthesis, mono 16 bit). */
internal object BatteryTones {
    const val SAMPLE_RATE = 44_100

    /** Low battery: a calm descending two-note chime. */
    const val LOW_MS = 380L

    /** Critical: five short, high, insistent beeps. */
    const val CRITICAL_MS = 600L

    fun low(): ShortArray = render(LOW_MS) { tMs ->
        note(tMs, 0.0, 160.0, 988.0) + note(tMs, 170.0, 210.0, 740.0)
    }

    fun critical(): ShortArray = render(CRITICAL_MS) { tMs ->
        (0 until 5).sumOf { k -> beep(tMs, k * 120.0, 75.0, if (k % 2 == 0) 1_480.0 else 1_760.0) }
    }

    /** Bell-like note: fast attack, exponential decay, a little octave for brightness. */
    private fun note(tMs: Double, startMs: Double, lengthMs: Double, freq: Double): Double {
        val dt = tMs - startMs
        if (dt < 0 || dt > lengthMs) return 0.0
        val env = min(1.0, dt / 4.0) * exp(-dt / (lengthMs / 2.5)) * min(1.0, (lengthMs - dt) / 8.0)
        val sec = dt / 1000
        return env * (0.8 * sin(2 * PI * freq * sec) + 0.2 * sin(4 * PI * freq * sec))
    }

    /** Square-ish beep (odd harmonics): cuts through wind and propeller noise. */
    private fun beep(tMs: Double, startMs: Double, lengthMs: Double, freq: Double): Double {
        val dt = tMs - startMs
        if (dt < 0 || dt > lengthMs) return 0.0
        val env = min(1.0, dt / 3.0) * min(1.0, (lengthMs - dt) / 3.0)
        val w = 2 * PI * freq * dt / 1000
        return env * (0.75 * sin(w) + 0.2 * sin(3 * w) + 0.08 * sin(5 * w))
    }

    private fun render(lengthMs: Long, signal: (Double) -> Double): ShortArray {
        val n = (SAMPLE_RATE * lengthMs / 1000).toInt()
        val raw = DoubleArray(n) { signal(it * 1000.0 / SAMPLE_RATE) }
        // Normalised: as loud as the media volume allows, without clipping.
        val scale = 0.85 * Short.MAX_VALUE / (raw.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0)
        return ShortArray(n) { (raw[it] * scale).toInt().coerceIn(-32767, 32767).toShort() }
    }
}

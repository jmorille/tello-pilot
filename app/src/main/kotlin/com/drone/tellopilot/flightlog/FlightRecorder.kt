package com.drone.tellopilot.flightlog

import android.os.SystemClock
import android.util.Log
import com.drone.tello.control.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Black box: records every flight (takeoff → landing + a few seconds) to a CSV file.
 *
 * Polls the latest telemetry every 200 ms on an I/O thread: it never touches the control or
 * video threads, and a bursty log stream costs nothing more. Errors only stop the recording
 * of the current flight, never the drone session.
 */
@Singleton
class FlightRecorder @Inject constructor(private val store: FlightLogStore) {

    /**
     * Runs until cancelled. [latest] returns the live telemetry, or `null` when the link is
     * down (a reconnection within [FlightSampler.NO_DATA_TIMEOUT_MS] continues the same flight).
     */
    suspend fun record(latest: () -> Telemetry?) = single.withLock { recordFlights(latest) }

    /** Overlapping session runs (screen recreated) must not record the same flight twice. */
    private val single = Mutex()

    private suspend fun recordFlights(latest: () -> Telemetry?) = withContext(Dispatchers.IO) {
        val sampler = FlightSampler()
        var writer: FlightLogStore.FlightWriter? = null
        try {
            while (isActive) {
                val tick = sampler.onTick(SystemClock.elapsedRealtime(), latest())
                try {
                    if (tick.start) {
                        val start = System.currentTimeMillis()
                        writer = FlightLogStore.FlightWriter(store.create(start), start)
                        Log.i(TAG, "flight started: ${writer.file.name}")
                    }
                    tick.sample?.let { writer?.append(it) }
                    if (tick.stop) {
                        writer?.let(::finish)
                        writer = null
                    }
                } catch (e: IOException) {
                    // Disk full…: give up this flight (what was written stays readable).
                    Log.w(TAG, "flight recording failed", e)
                    writer?.let { runCatching { it.close() } }
                    writer = null
                    store.activeId = null
                }
                delay(FlightSampler.INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "flight recorder stopped", e)
        } finally {
            // Session closed (app leaving) in flight: keep what was recorded.
            writer?.let { w -> withContext(NonCancellable) { runCatching { finish(w) } } }
        }
    }

    private fun finish(writer: FlightLogStore.FlightWriter) {
        val summary = writer.summary()
        if (summary.samples < MIN_SAMPLES) {
            // A spurious "flying" bit, not a flight.
            writer.abandon()
            Log.i(TAG, "flight discarded (${summary.samples} samples)")
        } else {
            writer.close()
            Log.i(TAG, "flight saved: ${writer.file.name}, $summary")
        }
        store.activeId = null
        store.prune()
        store.notifyChanged()
    }

    private companion object {
        const val TAG = "FlightRecorder"
        /** 1 s at 5 Hz. */
        const val MIN_SAMPLES = 5
    }
}

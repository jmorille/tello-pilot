package com.drone.tellopilot.data

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Dynamic Performance Framework (ADPF, API 31+; supported by the Pixel's Tensor):
 * reports, per video frame, the CPU work done by the video receive thread (datagram reassembly
 * and decoder input) against a target, so the scheduler keeps that thread on a core fast enough
 * instead of guessing from load. The hardware decode time is not CPU work: it is only logged.
 *
 * Threads register / unregister themselves. The session is only used under the lock: closing it
 * while a frame is reported would hand a freed native pointer to the framework.
 */
@Singleton
class PerformanceHints @Inject constructor(@ApplicationContext context: Context) {

    private val manager: PerformanceHintManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(PerformanceHintManager::class.java) else null

    private val threads = LinkedHashSet<Int>()
    private var session: Any? = null // PerformanceHintManager.Session (API 31+), guarded by this

    // Diagnostics: average frame latency over the last few seconds (`setprop log.tag.PerfHints DEBUG`).
    private var sum = 0L
    private var count = 0
    private var lastLog = 0L
    private val verbose = Log.isLoggable(TAG, Log.DEBUG)

    @Synchronized
    fun addThreads(vararg tids: Int) {
        if (tids.fold(false) { changed, tid -> (tid > 0 && threads.add(tid)) || changed }) recreate()
    }

    @Synchronized
    fun removeThreads(vararg tids: Int) {
        if (tids.fold(false) { changed, tid -> threads.remove(tid) || changed }) recreate()
    }

    /** CPU work of the video thread for one frame (all its datagrams + decoder input). */
    @Synchronized
    fun reportFrameWork(durationNanos: Long) {
        if (durationNanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) report(durationNanos)
    }

    /** Decode latency (queue to output, mostly hardware time): diagnostics only. */
    fun logDecodeLatency(durationNanos: Long) {
        if (verbose && durationNanos > 0) logAverage(durationNanos)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun report(durationNanos: Long) {
        val s = session as? PerformanceHintManager.Session ?: return
        runCatching { s.reportActualWorkDuration(durationNanos) }
    }

    private fun recreate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        createSession()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createSession() {
        val old = session as? PerformanceHintManager.Session
        session = null
        old?.close()
        val m = manager ?: return
        if (threads.isEmpty() || m.preferredUpdateRateNanos < 0) return
        val created = runCatching { m.createHintSession(threads.toIntArray(), TARGET_FRAME_NANOS) }
            .onFailure { Log.w(TAG, "hint session unavailable", it) }
            .getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { created.setPreferPowerEfficiency(false) } // latency first
        }
        session = created
    }

    @Synchronized
    private fun logAverage(durationNanos: Long) {
        sum += durationNanos
        count++
        val now = System.nanoTime()
        if (now - lastLog > 5_000_000_000L) {
            Log.d(TAG, "decode latency avg %.1f ms over %d frames".format(sum / count / 1e6, count))
            sum = 0; count = 0; lastLog = now
        }
    }

    private companion object {
        const val TAG = "PerfHints"
        /** CPU budget of the video thread per frame (reassembly + decoder input). */
        const val TARGET_FRAME_NANOS = 2_000_000L
    }
}

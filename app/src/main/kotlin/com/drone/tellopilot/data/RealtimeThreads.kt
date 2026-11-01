package com.drone.tellopilot.data

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedicated threads with raised priority: the control loop and the video path must never
 * wait behind UI work or the shared IO pool.
 *
 * They are *scheduled* executors: kotlinx `delay`/`withTimeout` then use the executor's own
 * timer instead of hopping through the normal-priority DefaultExecutor thread (less jitter
 * on the 5 ms / 20 ms stick cadence).
 */
object RealtimeThreads {
    /** Stick loop and commands: never blocked by socket reads. */
    val control: CoroutineDispatcher = dedicated("tello-control", Process.THREAD_PRIORITY_URGENT_DISPLAY)

    /** Blocking reads of the control socket (telemetry, acks). */
    val controlReceive: CoroutineDispatcher = dedicated("tello-control-rx", Process.THREAD_PRIORITY_URGENT_DISPLAY)

    /** Video socket reception, frame assembly and decoder input. */
    val video: CoroutineDispatcher = dedicated("tello-video", Process.THREAD_PRIORITY_DISPLAY)

    private fun dedicated(name: String, priority: Int) = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread({
            runCatching { Process.setThreadPriority(priority) }
            runnable.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

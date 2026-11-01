package com.drone.tellopilot.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drone.tellopilot.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive and the CPU awake (foreground service + partial wake lock) while
 * [TranscodeQueue] works, so that a re-encoding finishes with the screen off or the app left.
 * The work itself lives in the queue.
 */
@AndroidEntryPoint
class TranscodeService : Service() {

    @Inject lateinit var queue: TranscodeQueue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Always honour startForegroundService, even if the queue is already empty.
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                },
            )
        }
        if (started.isFailure) {
            Log.w(TAG, "foreground refused, the queue goes on while the process lives", started.exceptionOrNull())
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TelloPilot:transcode")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        if (!watching) {
            watching = true
            scope.launch {
                while (true) {
                    queue.busy.first { !it }
                    // Only the latest start: a start queued meanwhile keeps the service alive.
                    if (stopSelfResult(lastStartId)) break
                    delay(RECHECK_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: mediaProcessing has a 6 h budget; never reached, but stop cleanly if it is. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_videocam)
        .setContentTitle(getString(R.string.transcode_notification_title))
        .setContentText(getString(R.string.transcode_notification_text))
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
        .also {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.transcode_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }

    companion object {
        private const val TAG = "Transcode"
        private const val CHANNEL_ID = "transcode"
        private const val NOTIFICATION_ID = 42
        private const val RECHECK_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TranscodeService::class.java)) }
                .onFailure { Log.w(TAG, "service not started (app in background?)", it) }
        }
    }
}

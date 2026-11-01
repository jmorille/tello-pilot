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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        // mediaProcessing first (the right type since Android 15); some releases refuse it
        // ("type none"), dataSync is then the fallback, also declared in the manifest.
        val types = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            }
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            // Last resort, needs no permission (3 min, then onTimeout): never leave a
            // startForegroundService() unanswered, Android would kill the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            }
        }
        var started: Result<Unit> = Result.failure(IllegalStateException("no foreground type"))
        for (type in types) {
            started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
            if (started.isSuccess) {
                Log.i(TAG, "foreground service started, type $type")
                break
            }
            Log.w(TAG, "foreground type $type refused: ${started.exceptionOrNull()?.message}")
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
            scope.launch { showProgress() }
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

    /** shortService fallback (Android 14+): its 3 minutes are over, stop at once (else ANR). */
    @Deprecated("Android 15 calls the two-argument version")
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    /** Android 15+: mediaProcessing / dataSync time budget reached; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Mirrors the queue's [TranscodeQueue.board] in the notification: step, progress, stalled,
     * paused. At most one update a second, but the latest state always ends up shown (conflate:
     * a dropped intermediate value can no longer leave a stale percentage on screen).
     */
    private suspend fun showProgress() {
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        queue.board.map { content(it) }.distinctUntilChanged().conflate().collect { (text, progress) ->
            if (manager.areNotificationsEnabled()) {
                try {
                    manager.notify(NOTIFICATION_ID, notification(text, progress))
                } catch (_: SecurityException) {
                    // Permission revoked meanwhile: the service keeps working without updates.
                }
            }
            delay(PROGRESS_UPDATE_MS)
        }
    }

    /** Notification text and progress (null: none) of [board]; the generic text between two videos. */
    private fun content(board: TranscodeBoard): Pair<String, Int?> {
        val active = board.active ?: return getString(R.string.transcode_notification_text) to null
        val waiting = board.queued.size
        val percent = (active.progress * 100).toInt()
        val text = when {
            active.paused -> getString(R.string.transcode_notif_paused, active.name, waiting)
            active.stalled -> getString(R.string.transcode_notif_stalled, active.name, percent, waiting)
            active.stage == TranscodeStage.WAITING_FLIGHT_LOG -> getString(R.string.transcode_notif_waiting_log, active.name, waiting)
            active.stage == TranscodeStage.VERIFYING -> getString(R.string.transcode_notif_verifying, active.name, waiting)
            active.stage == TranscodeStage.PUBLISHING -> getString(R.string.transcode_notif_publishing, active.name, waiting)
            else -> getString(R.string.transcode_notif_running, active.name, percent, waiting)
        }
        val progress = if (active.paused || active.stage != TranscodeStage.EXPORTING) null else percent
        return text to progress
    }

    private fun notification(
        text: String = getString(R.string.transcode_notification_text),
        progress: Int? = null,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_videocam)
        .setContentTitle(getString(R.string.transcode_notification_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .apply { progress?.let { setProgress(100, it, false) } }
        .setContentIntent(openGallery())
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
        .also {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.transcode_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }

    /** Tapping the notification brings the app back (the queue is followed from the gallery). */
    private fun openGallery(): android.app.PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        android.app.PendingIntent.getActivity(
            this, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "Transcode"
        private const val CHANNEL_ID = "transcode"
        private const val NOTIFICATION_ID = 42
        private const val RECHECK_MS = 500L
        private const val PROGRESS_UPDATE_MS = 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TranscodeService::class.java)) }
                .onFailure { Log.w(TAG, "service not started (app in background?)", it) }
        }
    }
}

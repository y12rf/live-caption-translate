package com.example.livetranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.livetranslate.LiveTranslateApp
import com.example.livetranslate.MainActivity
import com.example.livetranslate.R
import com.example.livetranslate.data.history.HistoryExport
import com.example.livetranslate.domain.model.AudioSourceType
import com.example.livetranslate.domain.model.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service: keep-alive + notification with duration and Pause / Stop.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LiveTranslateApp
        val controller = app.container.sessionController

        when (intent?.action) {
            ACTION_PAUSE -> {
                controller.pause()
                return START_STICKY
            }
            ACTION_RESUME -> {
                controller.start()
                return START_STICKY
            }
            ACTION_STOP -> {
                controller.stop()
                stopOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val source = intent?.getStringExtra(EXTRA_AUDIO_SOURCE)
                    ?.let { runCatching { AudioSourceType.valueOf(it) }.getOrNull() }
                    ?: AudioSourceType.Microphone
                controller.setAudioSource(source)

                // Must enter foreground before getMediaProjection on Android 14+
                val notif = buildNotification(
                    phase = SessionPhase.Recording,
                    elapsedMs = 0L
                )
                startAsForeground(source, notif)

                if (source == AudioSourceType.Internal) {
                    val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
                    }
                    if (resultCode != 0 && data != null) {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                        projection = mpm.getMediaProjection(resultCode, data)
                        controller.setMediaProjection(projection)
                    }
                }

                controller.start()
                observeJob?.cancel()
                observeJob = scope.launch {
                    controller.state.collectLatest { st ->
                        val n = buildNotification(st.phase, st.recordedElapsedMs)
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIF_ID, n)
                        if (st.phase == SessionPhase.Idle) {
                            stopOverlay()
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(source: AudioSourceType, notif: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (source == AudioSourceType.Internal) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, notif, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, SubtitleOverlayService::class.java))
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        (application as? LiveTranslateApp)
            ?.container
            ?.sessionController
            ?.setMediaProjection(null)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = getString(R.string.notif_channel_recording_desc)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(phase: SessionPhase, elapsedMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseOrResume = if (phase == SessionPhase.Paused) {
            PendingIntent.getService(
                this,
                1,
                Intent(this, RecordingService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                2,
                Intent(this, RecordingService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (phase) {
            SessionPhase.Paused -> getString(R.string.notif_paused)
            SessionPhase.Processing -> getString(R.string.notif_processing)
            else -> getString(R.string.notif_recording)
        }
        val duration = HistoryExport.formatOffset(elapsedMs)

        val actionLabel = if (phase == SessionPhase.Paused) {
            getString(R.string.notif_resume)
        } else {
            getString(R.string.notif_pause)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_duration, duration))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(phase != SessionPhase.Idle)
            .setOnlyAlertOnce(true)
            .addAction(0, actionLabel, pauseOrResume)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.livetranslate.action.START"
        const val ACTION_PAUSE = "com.example.livetranslate.action.PAUSE"
        const val ACTION_RESUME = "com.example.livetranslate.action.RESUME"
        const val ACTION_STOP = "com.example.livetranslate.action.STOP"
        const val EXTRA_AUDIO_SOURCE = "audio_source"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        fun start(
            context: Context,
            source: AudioSourceType,
            projectionResultCode: Int? = null,
            projectionData: Intent? = null
        ) {
            val i = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AUDIO_SOURCE, source.name)
                if (projectionResultCode != null && projectionData != null) {
                    putExtra(EXTRA_RESULT_CODE, projectionResultCode)
                    putExtra(EXTRA_PROJECTION_DATA, projectionData)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

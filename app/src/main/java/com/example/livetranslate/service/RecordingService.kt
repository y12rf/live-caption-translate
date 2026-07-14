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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
 *
 * Internal audio path (MediaProjection) is hardened for real devices / Android 14+:
 * - startForeground (mediaProjection type) BEFORE getMediaProjection
 * - register MediaProjection.Callback before any capture use
 * - consent Intent kept in-process via [ProjectionTokenStore]
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var projection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            try {
                val controller = (application as LiveTranslateApp).container.sessionController
                controller.pause()
            } catch (e: Exception) {
                Log.w(TAG, "onStop handling failed", e)
            }
        }
    }

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
                try {
                    controller.pause()
                } catch (e: Exception) {
                    Log.e(TAG, "pause failed", e)
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                try {
                    controller.start()
                } catch (e: Exception) {
                    Log.e(TAG, "resume failed", e)
                    reportError(controller, e)
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                try {
                    controller.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "stop failed", e)
                }
                releaseProjection()
                stopOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val source = intent?.getStringExtra(EXTRA_AUDIO_SOURCE)
                    ?.let { runCatching { AudioSourceType.valueOf(it) }.getOrNull() }
                    ?: AudioSourceType.Microphone

                try {
                    startRecordingSession(controller, source)
                } catch (e: Exception) {
                    Log.e(TAG, "startRecordingSession failed", e)
                    reportError(controller, e)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startRecordingSession(
        controller: com.example.livetranslate.domain.SessionController,
        source: AudioSourceType
    ) {
        controller.setAudioSource(source)

        // 1) Foreground first (Android 14 requires this before getMediaProjection)
        val notif = buildNotification(SessionPhase.Recording, 0L)
        startAsForeground(source, notif)

        // 2) Internal audio: create MediaProjection + register callback
        if (source == AudioSourceType.Internal) {
            val token = ProjectionTokenStore.take()
                ?: throw IllegalStateException(
                    "内部录音授权丢失，请重新点 Start 并完成录屏授权"
                )
            val (resultCode, data) = token
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("无法创建 MediaProjection，请重试授权")

            // Register callback on all API levels that support it (required on 14+, good on 13)
            try {
                proj.registerCallback(projectionCallback, mainHandler)
            } catch (t: Throwable) {
                Log.w(TAG, "registerCallback failed (continuing): ${t.message}")
            }
            projection = proj
            controller.setMediaProjection(proj)
        }

        // 3) Start capture after a short delay so OEM MediaProjection is fully ready (Android 12–13)
        mainHandler.postDelayed({
            try {
                controller.start()
            } catch (e: Exception) {
                Log.e(TAG, "delayed start failed", e)
                reportError(controller, e)
            }
        }, if (source == AudioSourceType.Internal) 200L else 0L)

        observeJob?.cancel()
        observeJob = scope.launch {
            controller.state.collectLatest { st ->
                try {
                    val n = buildNotification(st.phase, st.recordedElapsedMs)
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
                } catch (e: Exception) {
                    Log.w(TAG, "notify update failed", e)
                }
                if (st.phase == SessionPhase.Idle) {
                    releaseProjection()
                    stopOverlay()
                    stopSelf()
                }
            }
        }
    }

    private fun reportError(
        controller: com.example.livetranslate.domain.SessionController,
        e: Exception
    ) {
        Log.e(TAG, "Recording error: ${e.message}", e)
        try {
            controller.reportCaptureError(e.message ?: "录音启动失败")
        } catch (_: Exception) {
            try {
                controller.pause()
            } catch (_: Exception) {
            }
        }
    }

    private fun startAsForeground(source: AudioSourceType, notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+: match declared foregroundServiceType in manifest
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (source == AudioSourceType.Internal) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIF_ID, notif, type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29–33: microphone type is enough; mediaProjection FGS type is API 34+
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Fallback: try without mediaProjection type if OEM rejects combo
            Log.w(TAG, "startForeground typed failed, fallback", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
    }

    private fun releaseProjection() {
        val p = projection
        projection = null
        if (p != null) {
            try {
                p.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                p.stop()
            } catch (_: Exception) {
            }
        }
        (application as? LiveTranslateApp)
            ?.container
            ?.sessionController
            ?.setMediaProjection(null)
        ProjectionTokenStore.clear()
    }

    private fun stopOverlay() {
        try {
            stopService(Intent(this, SubtitleOverlayService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        releaseProjection()
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
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.livetranslate.action.START"
        const val ACTION_PAUSE = "com.example.livetranslate.action.PAUSE"
        const val ACTION_RESUME = "com.example.livetranslate.action.RESUME"
        const val ACTION_STOP = "com.example.livetranslate.action.STOP"
        const val EXTRA_AUDIO_SOURCE = "audio_source"

        fun start(
            context: Context,
            source: AudioSourceType,
            projectionResultCode: Int? = null,
            projectionData: Intent? = null
        ) {
            if (source == AudioSourceType.Internal &&
                projectionResultCode != null &&
                projectionData != null
            ) {
                ProjectionTokenStore.put(projectionResultCode, projectionData)
            }
            val i = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AUDIO_SOURCE, source.name)
                // Do NOT put projection Intent in extras — use ProjectionTokenStore
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (e: Exception) {
                Log.e(TAG, "stop service failed", e)
            }
        }
    }
}

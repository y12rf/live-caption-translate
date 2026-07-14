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
            val token = ProjectionTokenStore.peek()
                ?: throw IllegalStateException(
                    "内部录音未获得 MediaProjection 授权：请点 Start 后完成「录制/投射」系统授权（不要只开麦克风）"
                )
            val (resultCode, data) = token
            Log.i(TAG, "getMediaProjection resultCode=$resultCode")
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val proj = try {
                mpm.getMediaProjection(resultCode, data)
            } catch (e: SecurityException) {
                ProjectionTokenStore.consume()
                throw IllegalStateException(
                    "MediaProjection 被拒绝：请确保前台服务类型含 MEDIA_PROJECTION，并重新授权。${e.message}",
                    e
                )
            } ?: run {
                ProjectionTokenStore.consume()
                throw IllegalStateException("无法创建 MediaProjection，请重试授权")
            }

            // Required before capture on API 34+; also register on API 35 Pixel
            try {
                proj.registerCallback(projectionCallback, mainHandler)
            } catch (t: Throwable) {
                Log.w(TAG, "registerCallback failed (continuing): ${t.message}")
            }
            projection = proj
            controller.setMediaProjection(proj)
            // Token consumed only after successful MediaProjection creation
            ProjectionTokenStore.consume()
        }

        // 3) Start capture after a short delay (OEM / Pixel readiness)
        val delayMs = if (source == AudioSourceType.Internal) 300L else 0L
        mainHandler.postDelayed({
            try {
                if (source == AudioSourceType.Internal &&
                    controller.audio.mediaProjection == null
                ) {
                    reportError(
                        controller,
                        IllegalStateException("内部录音未获得 MediaProjection 授权（启动时已丢失）")
                    )
                    return@postDelayed
                }
                controller.start()
            } catch (e: Exception) {
                Log.e(TAG, "delayed start failed", e)
                reportError(controller, e)
            }
        }, delayMs)

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
        // MediaProjection APIs require FGS type MEDIA_PROJECTION (API 29+ constant).
        // Without it, getMediaProjection / capture throws:
        // "Media projections require a foreground service of type ... MEDIA_PROJECTION"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = when (source) {
                AudioSourceType.Internal ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                AudioSourceType.Microphone ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
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
            if (source == AudioSourceType.Internal) {
                if (projectionResultCode == null || projectionData == null) {
                    Log.e(TAG, "Internal start without projection token — refuse")
                    throw IllegalArgumentException(
                        "内部录音必须先完成系统录屏授权"
                    )
                }
                // RESULT_OK == -1
                if (projectionResultCode != android.app.Activity.RESULT_OK) {
                    throw IllegalArgumentException(
                        "录屏授权未成功 resultCode=$projectionResultCode"
                    )
                }
                ProjectionTokenStore.put(projectionResultCode, projectionData)
            }
            val i = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AUDIO_SOURCE, source.name)
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

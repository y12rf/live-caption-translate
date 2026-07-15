package com.example.livetranslate.data.audio

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin [MediaPlayer] wrapper for history-detail playback + seek.
 * Not thread-safe beyond single-UI-thread use.
 */
class SessionAudioPlayer {
    private var player: MediaPlayer? = null
    private val prepared = AtomicBoolean(false)

    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    val durationMs: Int
        get() = try {
            if (prepared.get()) player?.duration?.coerceAtLeast(0) ?: 0 else 0
        } catch (_: Exception) {
            0
        }

    val positionMs: Int
        get() = try {
            if (prepared.get()) player?.currentPosition?.coerceAtLeast(0) ?: 0 else 0
        } catch (_: Exception) {
            0
        }

    fun prepare(file: File): Boolean {
        release()
        if (!file.isFile || file.length() < 44L) return false
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                // Stay prepared at end; UI can restart
            }
            mp.prepare()
            player = mp
            prepared.set(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            release()
            false
        }
    }

    fun play() {
        val mp = player ?: return
        if (!prepared.get()) return
        try {
            if (!mp.isPlaying) mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
        }
    }

    fun pause() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (e: Exception) {
            Log.e(TAG, "pause failed", e)
        }
    }

    fun seekTo(ms: Int) {
        val mp = player ?: return
        if (!prepared.get()) return
        try {
            val d = mp.duration.coerceAtLeast(0)
            mp.seekTo(ms.coerceIn(0, d))
        } catch (e: Exception) {
            Log.e(TAG, "seek failed", e)
        }
    }

    fun release() {
        prepared.set(false)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    companion object {
        private const val TAG = "SessionAudioPlayer"
    }
}

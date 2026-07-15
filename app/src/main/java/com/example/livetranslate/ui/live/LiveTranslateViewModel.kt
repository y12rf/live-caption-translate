package com.example.livetranslate.ui.live

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.livetranslate.data.history.ExportTextMode
import com.example.livetranslate.di.AppContainer
import com.example.livetranslate.domain.LiveSessionUiState
import com.example.livetranslate.domain.SessionController
import com.example.livetranslate.domain.model.AudioSourceType
import kotlinx.coroutines.flow.StateFlow

class LiveTranslateViewModel(
    private val controller: SessionController
) : ViewModel() {

    val state: StateFlow<LiveSessionUiState> = controller.state

    fun setAudioSource(type: AudioSourceType) = controller.setAudioSource(type)
    fun setOverlayEnabled(enabled: Boolean) = controller.setOverlayEnabled(enabled)
    fun start() = controller.start()
    fun startFromFile(uri: Uri) = controller.startFromFile(uri)
    fun pause() = controller.pause()
    fun stop(drain: Boolean = true) = controller.stop(drain)
    fun retry() = controller.retry()
    fun retryAllFailed() = controller.retryAllFailed()
    fun dismissFailures() = controller.dismissFailures()
    fun retrySave() = controller.retrySave()
    fun exportMarkdown(): String? = controller.exportMarkdown()
    fun exportSrt(mode: ExportTextMode = ExportTextMode.Both): String? =
        controller.exportSrt(mode)

    fun exportPlain(mode: ExportTextMode): String? = controller.exportPlain(mode)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LiveTranslateViewModel(container.sessionController) as T
        }
    }
}

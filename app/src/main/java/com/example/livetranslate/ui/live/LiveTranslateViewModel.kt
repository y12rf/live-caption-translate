package com.example.livetranslate.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
    fun pause() = controller.pause()
    fun stop() = controller.stop()
    fun retry() = controller.retry()
    fun exportMarkdown(): String? = controller.exportMarkdown()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LiveTranslateViewModel(container.sessionController) as T
        }
    }
}

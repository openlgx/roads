package org.openlgx.roads.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.settings.AppSettingsRepository

@HiltViewModel
class AllRunsReviewViewModel
@Inject
constructor(
    private val reviewPayloadBuilder: ReviewPayloadBuilder,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val _payloadBase64 = MutableStateFlow<String?>(null)
    val payloadBase64: StateFlow<String?> = _payloadBase64.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching {
                val consensus =
                    appSettingsRepository.settings.first().processingAllRunsOverlayEnabled
                val json = reviewPayloadBuilder.buildAllRunsMapPayload(consensus)
                _payloadBase64.value = reviewPayloadBuilder.encodeForWebView(json)
                _error.value = null
            }.onFailure { _error.value = it.message }
        }
    }
}

package org.openlgx.roads.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SessionReviewViewModel
@Inject
constructor(
    private val reviewPayloadBuilder: ReviewPayloadBuilder,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _payloadBase64 = MutableStateFlow<String?>(null)
    val payloadBase64: StateFlow<String?> = _payloadBase64.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        if (sessionId > 0) load()
    }

    fun load() {
        if (sessionId <= 0) return
        viewModelScope.launch {
            runCatching {
                val json = reviewPayloadBuilder.buildSessionPayload(sessionId)
                _payloadBase64.value = reviewPayloadBuilder.encodeForWebView(json)
            }.onFailure { _error.value = it.message }
        }
    }
}

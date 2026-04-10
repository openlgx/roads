package org.openlgx.roads.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState

data class CollectorForegroundPresentation(
    val sessionId: Long,
    val sessionShortLabel: String,
    val lifecycleState: CollectorLifecycleState,
    val degradedOrPolicy: Boolean,
)

@Singleton
class CollectorForegroundPresentationRegistry
@Inject
constructor() {
    private val _state = MutableStateFlow<CollectorForegroundPresentation?>(null)
    val state: StateFlow<CollectorForegroundPresentation?> = _state.asStateFlow()

    fun publish(presentation: CollectorForegroundPresentation?) {
        _state.value = presentation
    }
}

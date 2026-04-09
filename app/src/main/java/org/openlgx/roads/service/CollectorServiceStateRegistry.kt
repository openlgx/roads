package org.openlgx.roads.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class CollectorServiceStateRegistry
@Inject
constructor() {
    private val _foregroundRunning = MutableStateFlow(false)
    val foregroundRunning: StateFlow<Boolean> = _foregroundRunning.asStateFlow()

    fun setForegroundRunning(running: Boolean) {
        _foregroundRunning.value = running
    }
}

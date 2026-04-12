package org.openlgx.roads.collector

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow surface for UI and diagnostics. Keeps [PassiveCollectionCoordinator] testable via fakes.
 */
interface PassiveCollectionHandle {
    val uiState: StateFlow<PassiveCollectionUiModel>

    fun start()

    /** Poke the reconcile loop (e.g. from keepalive after process survival). */
    fun nudge()

    fun debugSetSimulateDriving(active: Boolean?)

    fun debugForceStartRecording()

    fun debugResetStateMachine()
}

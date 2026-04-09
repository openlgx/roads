package org.openlgx.roads.collector

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow surface for UI and diagnostics. Keeps [PassiveCollectionCoordinator] testable via fakes.
 */
interface PassiveCollectionHandle {
    val uiState: StateFlow<PassiveCollectionUiModel>

    fun start()

    fun debugSetSimulateDriving(active: Boolean?)

    fun debugForceStartRecording()

    fun debugResetStateMachine()
}

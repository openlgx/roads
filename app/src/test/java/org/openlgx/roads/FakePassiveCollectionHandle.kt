package org.openlgx.roads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.collector.PassiveCollectionHandle
import org.openlgx.roads.collector.PassiveCollectionUiModel

class FakePassiveCollectionHandle(
    initial: PassiveCollectionUiModel = PassiveCollectionUiModel(),
) : PassiveCollectionHandle {
    private val mutableCollector = MutableStateFlow(initial)

    override val uiState: StateFlow<PassiveCollectionUiModel> = mutableCollector.asStateFlow()

    override fun start() = Unit

    override fun debugSetSimulateDriving(active: Boolean?) = Unit

    override fun debugForceStartRecording() = Unit

    override fun debugResetStateMachine() = Unit

    fun setCollector(value: PassiveCollectionUiModel) {
        mutableCollector.value = value
    }
}

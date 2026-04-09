package org.openlgx.roads.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.openlgx.roads.collector.PassiveCollectionHandle
import org.openlgx.roads.collector.PassiveCollectionUiModel
import org.openlgx.roads.data.local.settings.AppSettingsRepository

data class HomeUiState(
    val passiveCollectionEffective: Boolean = false,
    val passiveCollectionUserEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val uploadWifiOnly: Boolean = true,
    val uploadAllowCellular: Boolean = false,
    val debugModeEnabled: Boolean = false,
    val pendingUploadSummaryPlaceholder: String = "—",
    val collector: PassiveCollectionUiModel = PassiveCollectionUiModel(),
)

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    appSettingsRepository: AppSettingsRepository,
    private val passiveCollection: PassiveCollectionHandle,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(appSettingsRepository.settings, passiveCollection.uiState) { settings, collector ->
            HomeUiState(
                passiveCollectionEffective = settings.passiveCollectionEffective,
                passiveCollectionUserEnabled = settings.passiveCollectionUserEnabled,
                onboardingCompleted = settings.onboardingCompleted,
                uploadWifiOnly = settings.uploadWifiOnly,
                uploadAllowCellular = settings.uploadAllowCellular,
                debugModeEnabled = settings.debugModeEnabled,
                pendingUploadSummaryPlaceholder = "See Diagnostics for queue (Phase 1)",
                collector = collector,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HomeUiState(),
        )

    fun debugSetSimulatedDriving(active: Boolean?) {
        passiveCollection.debugSetSimulateDriving(active)
    }

    fun debugForceStartRecording() {
        passiveCollection.debugForceStartRecording()
    }

    fun debugResetCollector() {
        passiveCollection.debugResetStateMachine()
    }
}

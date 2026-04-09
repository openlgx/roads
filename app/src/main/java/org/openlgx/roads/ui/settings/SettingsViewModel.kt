package org.openlgx.roads.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        appSettingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue =
                AppSettings(
                    onboardingCompleted = false,
                    passiveCollectionUserEnabled = true,
                    passiveCollectionEffective = false,
                    uploadWifiOnly = true,
                    uploadAllowCellular = false,
                    uploadOnlyWhileCharging = false,
                    uploadPauseOnLowBatteryEnabled = true,
                    uploadLowBatteryThresholdPercent = 20,
                    retentionDays = 30,
                    maxLocalStorageMb = 0,
                    localCompactionEnabled = false,
                    captureMinSpeedMps = 4.5f,
                    debugModeEnabled = false,
                ),
        )

    fun completeOnboarding() {
        viewModelScope.launch { appSettingsRepository.completeOnboarding() }
    }

    fun setPassiveCollectionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setPassiveCollectionEnabled(enabled) }
    }

    fun setUploadWifiOnly(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadWifiOnly(enabled) }
    }

    fun setUploadAllowCellular(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadAllowCellular(enabled) }
    }

    fun setUploadOnlyWhileCharging(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadOnlyWhileCharging(enabled) }
    }

    fun setUploadPauseOnLowBattery(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setUploadPauseOnLowBatteryEnabled(enabled) }
    }

    fun setLowBatteryThreshold(percent: Int) {
        viewModelScope.launch { appSettingsRepository.setUploadLowBatteryThresholdPercent(percent) }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch { appSettingsRepository.setRetentionDays(days) }
    }

    fun setMaxLocalStorageMb(mb: Int) {
        viewModelScope.launch { appSettingsRepository.setMaxLocalStorageMb(mb) }
    }

    fun setLocalCompactionEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setLocalCompactionEnabled(enabled) }
    }

    fun setCaptureMinSpeedMps(mps: Float) {
        viewModelScope.launch { appSettingsRepository.setCaptureMinSpeedMps(mps) }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { appSettingsRepository.setDebugModeEnabled(enabled) }
    }
}

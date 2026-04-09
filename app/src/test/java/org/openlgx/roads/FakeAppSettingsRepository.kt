package org.openlgx.roads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository

/**
 * Test double for ViewModel unit tests; setters are no-ops unless you mutate [mutState] externally.
 */
class FakeAppSettingsRepository(
    initial: AppSettings,
) : AppSettingsRepository {
    private val mutState = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = mutState.asStateFlow()

    fun overrideSettings(value: AppSettings) {
        mutState.value = value
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) = Unit

    override suspend fun completeOnboarding() = Unit

    override suspend fun setPassiveCollectionEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadWifiOnly(enabled: Boolean) = Unit

    override suspend fun setUploadAllowCellular(enabled: Boolean) = Unit

    override suspend fun setUploadOnlyWhileCharging(enabled: Boolean) = Unit

    override suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean) = Unit

    override suspend fun setUploadLowBatteryThresholdPercent(percent: Int) = Unit

    override suspend fun setRetentionDays(days: Int) = Unit

    override suspend fun setMaxLocalStorageMb(mb: Int) = Unit

    override suspend fun setLocalCompactionEnabled(enabled: Boolean) = Unit

    override suspend fun setCaptureMinSpeedMps(mps: Float) = Unit

    override suspend fun setDebugModeEnabled(enabled: Boolean) = Unit

    override suspend fun setCalibrationWorkflowEnabled(enabled: Boolean) = Unit
}

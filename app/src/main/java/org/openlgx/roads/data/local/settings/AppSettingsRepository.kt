package org.openlgx.roads.data.local.settings

import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setOnboardingCompleted(completed: Boolean)

    suspend fun completeOnboarding()

    suspend fun setPassiveCollectionEnabled(enabled: Boolean)

    suspend fun setUploadWifiOnly(enabled: Boolean)

    suspend fun setUploadAllowCellular(enabled: Boolean)

    suspend fun setUploadOnlyWhileCharging(enabled: Boolean)

    suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean)

    suspend fun setUploadLowBatteryThresholdPercent(percent: Int)

    suspend fun setRetentionDays(days: Int)

    suspend fun setMaxLocalStorageMb(mb: Int)

    suspend fun setLocalCompactionEnabled(enabled: Boolean)

    suspend fun setCaptureMinSpeedMps(mps: Float)

    suspend fun setDebugModeEnabled(enabled: Boolean)
}

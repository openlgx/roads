package org.openlgx.roads.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsRepositoryImpl
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsRepository {

    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val passiveCollectionEnabled = booleanPreferencesKey("passive_collection_enabled")
        val uploadWifiOnly = booleanPreferencesKey("upload_wifi_only")
        val uploadAllowCellular = booleanPreferencesKey("upload_allow_cellular")
        val uploadOnlyWhileCharging = booleanPreferencesKey("upload_only_while_charging")
        val uploadPauseOnLowBattery = booleanPreferencesKey("upload_pause_on_low_battery_enabled")
        val uploadLowBatteryThreshold = intPreferencesKey("upload_low_battery_threshold_percent")
        val retentionDays = intPreferencesKey("retention_days")
        val maxLocalStorageMb = intPreferencesKey("max_local_storage_mb")
        val localCompactionEnabled = booleanPreferencesKey("local_compaction_enabled")
        val captureMinSpeedMps = floatPreferencesKey("capture_min_speed_mps")
        val debugModeEnabled = booleanPreferencesKey("debug_mode_enabled")
        val calibrationWorkflowEnabled = booleanPreferencesKey("calibration_workflow_enabled")
    }

    override val settings: Flow<AppSettings> =
        dataStore.data.map { prefs ->
            val onboarding = prefs[Keys.onboardingCompleted] ?: false
            val passiveUser = prefs[Keys.passiveCollectionEnabled] ?: true
            val passiveEffective = onboarding && passiveUser

            AppSettings(
                onboardingCompleted = onboarding,
                passiveCollectionUserEnabled = passiveUser,
                passiveCollectionEffective = passiveEffective,
                uploadWifiOnly = prefs[Keys.uploadWifiOnly] ?: true,
                uploadAllowCellular = prefs[Keys.uploadAllowCellular] ?: false,
                uploadOnlyWhileCharging = prefs[Keys.uploadOnlyWhileCharging] ?: false,
                uploadPauseOnLowBatteryEnabled = prefs[Keys.uploadPauseOnLowBattery] ?: true,
                uploadLowBatteryThresholdPercent = prefs[Keys.uploadLowBatteryThreshold] ?: 20,
                retentionDays = prefs[Keys.retentionDays] ?: 30,
                maxLocalStorageMb = prefs[Keys.maxLocalStorageMb] ?: 0,
                localCompactionEnabled = prefs[Keys.localCompactionEnabled] ?: false,
                captureMinSpeedMps = prefs[Keys.captureMinSpeedMps] ?: DEFAULT_MIN_SPEED_MPS,
                debugModeEnabled = prefs[Keys.debugModeEnabled] ?: false,
                calibrationWorkflowEnabled = prefs[Keys.calibrationWorkflowEnabled] ?: false,
            )
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    override suspend fun completeOnboarding() {
        dataStore.edit { prefs ->
            prefs[Keys.onboardingCompleted] = true
            if (!prefs.contains(Keys.passiveCollectionEnabled)) {
                prefs[Keys.passiveCollectionEnabled] = true
            }
        }
    }

    override suspend fun setPassiveCollectionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.passiveCollectionEnabled] = enabled }
    }

    override suspend fun setUploadWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadWifiOnly] = enabled }
    }

    override suspend fun setUploadAllowCellular(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadAllowCellular] = enabled }
    }

    override suspend fun setUploadOnlyWhileCharging(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadOnlyWhileCharging] = enabled }
    }

    override suspend fun setUploadPauseOnLowBatteryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.uploadPauseOnLowBattery] = enabled }
    }

    override suspend fun setUploadLowBatteryThresholdPercent(percent: Int) {
        require(percent in 5..50)
        dataStore.edit { it[Keys.uploadLowBatteryThreshold] = percent }
    }

    override suspend fun setRetentionDays(days: Int) {
        require(days in 1..3650)
        dataStore.edit { it[Keys.retentionDays] = days }
    }

    override suspend fun setMaxLocalStorageMb(mb: Int) {
        require(mb in 0..1_000_000)
        dataStore.edit { it[Keys.maxLocalStorageMb] = mb }
    }

    override suspend fun setLocalCompactionEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.localCompactionEnabled] = enabled }
    }

    override suspend fun setCaptureMinSpeedMps(mps: Float) {
        require(mps in 0f..50f)
        dataStore.edit { it[Keys.captureMinSpeedMps] = mps }
    }

    override suspend fun setDebugModeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.debugModeEnabled] = enabled }
    }

    override suspend fun setCalibrationWorkflowEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.calibrationWorkflowEnabled] = enabled }
    }

    private companion object {
        const val DEFAULT_MIN_SPEED_MPS = 4.5f
    }
}

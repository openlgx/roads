package org.openlgx.roads.data.local.settings

/**
 * Capture policy vs upload policy are intentionally separate.
 *
 * [passiveCollectionEffective] is what runtime capture should use: disabled until onboarding, then
 * follows [passiveCollectionUserEnabled] (DataStore default is true when the key is absent).
 */
data class AppSettings(
    val onboardingCompleted: Boolean,
    /** User-visible toggle; persisted even before onboarding completes. */
    val passiveCollectionUserEnabled: Boolean,
    val passiveCollectionEffective: Boolean,
    val uploadWifiOnly: Boolean,
    val uploadAllowCellular: Boolean,
    val uploadOnlyWhileCharging: Boolean,
    val uploadPauseOnLowBatteryEnabled: Boolean,
    val uploadLowBatteryThresholdPercent: Int,
    val retentionDays: Int,
    /** 0 = unset / unlimited placeholder. */
    val maxLocalStorageMb: Int,
    val localCompactionEnabled: Boolean,
    val captureMinSpeedMps: Float,
    val debugModeEnabled: Boolean,
)

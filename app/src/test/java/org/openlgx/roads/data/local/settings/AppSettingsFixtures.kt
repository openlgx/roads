package org.openlgx.roads.data.local.settings

/** Defaults for unit tests after capture/processing settings expansion. */
fun testAppSettings(
    onboardingCompleted: Boolean = true,
    passiveCollectionUserEnabled: Boolean = true,
    passiveCollectionEffective: Boolean = true,
): AppSettings =
    AppSettings(
        onboardingCompleted = onboardingCompleted,
        passiveCollectionUserEnabled = passiveCollectionUserEnabled,
        passiveCollectionEffective = passiveCollectionEffective,
        uploadWifiOnly = true,
        uploadAllowCellular = false,
        uploadOnlyWhileCharging = false,
        uploadPauseOnLowBatteryEnabled = true,
        uploadLowBatteryThresholdPercent = 20,
        retentionDays = 30,
        maxLocalStorageMb = 0,
        localCompactionEnabled = false,
        captureStartSpeedMps = 4.5f,
        captureImmediateStartSpeedMps = 4.5f,
        captureStopSpeedMps = 1.0f,
        captureStopHoldSeconds = 180,
        captureStationaryRadiusMeters = 25f,
        captureFastArmingEnabled = true,
        processingWindowSeconds = 1.0f,
        processingDistanceBinMeters = 10f,
        processingLiveAfterSessionEnabled = true,
        processingAllRunsOverlayEnabled = true,
        debugModeEnabled = false,
        calibrationWorkflowEnabled = false,
        lastRecordingStartedAtEpochMs = null,
        lastRecordingStoppedAtEpochMs = null,
    )

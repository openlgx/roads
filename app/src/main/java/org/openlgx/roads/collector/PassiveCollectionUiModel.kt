package org.openlgx.roads.collector

import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState

data class PassiveCollectionUiModel(
    val lifecycleState: CollectorLifecycleState = CollectorLifecycleState.IDLE,
    val passiveCollectionRuntimeDesired: Boolean = false,
    val likelyInVehicle: Boolean = false,
    val activityRecognitionSupported: Boolean = false,
    val activityRecognitionUpdatesActive: Boolean = false,
    val activityRecognitionPermissionGranted: Boolean = false,
    val lastActivityType: Int = 0,
    val lastActivityConfidence: Int = 0,
    val lastActivityLabel: String = "—",
    val recordingActive: Boolean = false,
    val activeSessionId: Long? = null,
    val foregroundServiceRunning: Boolean = false,
    /** Updated when [lifecycleState] changes (not on every activity tick). */
    val lastLifecycleTransitionEpochMs: Long? = null,
    val lastLifecycleTransitionLabel: String = "—",
)

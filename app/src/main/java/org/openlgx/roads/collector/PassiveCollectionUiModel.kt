package org.openlgx.roads.collector

import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState

data class PassiveCollectionUiModel(
    val lifecycleState: CollectorLifecycleState = CollectorLifecycleState.IDLE,
    val passiveCollectionRuntimeDesired: Boolean = false,
    val likelyInVehicle: Boolean = false,
    val activityRecognitionSupported: Boolean = false,
    val activityRecognitionUpdatesActive: Boolean = false,
    val activityRecognitionPermissionGranted: Boolean = false,
    val fineLocationPermissionGranted: Boolean = false,
    val lastActivityType: Int = 0,
    /** Last activity transition label, e.g. "In vehicle · enter" (Activity Transition API). */
    val lastActivityTransitionLabel: String = "—",
    val recordingActive: Boolean = false,
    val activeSessionId: Long? = null,
    val foregroundServiceRunning: Boolean = false,
    /** Updated when [lifecycleState] changes (not on every activity tick). */
    val lastLifecycleTransitionEpochMs: Long? = null,
    val lastLifecycleTransitionLabel: String = "—",
)

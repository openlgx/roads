package org.openlgx.roads.activityrecognition

import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.StateFlow

data class ActivityRecognitionSnapshot(
    val playServicesAvailable: Boolean,
    val updatesActive: Boolean,
    val lastErrorMessage: String?,
    val likelyInVehicle: Boolean,
    val lastDetectedActivityType: Int,
    val lastConfidence: Int,
    val lastUpdateEpochMs: Long?,
) {
    companion object {
        fun unavailable(reason: String?): ActivityRecognitionSnapshot =
            ActivityRecognitionSnapshot(
                playServicesAvailable = false,
                updatesActive = false,
                lastErrorMessage = reason,
                likelyInVehicle = false,
                lastDetectedActivityType = DetectedActivity.UNKNOWN,
                lastConfidence = 0,
                lastUpdateEpochMs = null,
            )

        fun idleSupportedButNotRunning(): ActivityRecognitionSnapshot =
            ActivityRecognitionSnapshot(
                playServicesAvailable = true,
                updatesActive = false,
                lastErrorMessage = null,
                likelyInVehicle = false,
                lastDetectedActivityType = DetectedActivity.UNKNOWN,
                lastConfidence = 0,
                lastUpdateEpochMs = null,
            )
    }
}

interface ActivityRecognitionGateway {
    val snapshot: StateFlow<ActivityRecognitionSnapshot>

    suspend fun startUpdates()

    suspend fun stopUpdates()
}

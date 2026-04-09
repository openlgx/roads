package org.openlgx.roads.activityrecognition

import android.content.Intent
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

    /**
     * Applies an Activity Recognition result delivered to the manifest receiver (or any [Intent]
     * carrying [com.google.android.gms.location.ActivityRecognitionResult]). Returns true if a
     * result was applied.
     */
    fun ingestActivityRecognitionIntent(intent: Intent): Boolean

    suspend fun startUpdates()

    suspend fun stopUpdates()
}

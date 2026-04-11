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
    /**
     * Last Activity Transition type (ENTER / EXIT from Play services), or [TRANSITION_UNKNOWN] before any event.
     */
    val lastActivityTransitionType: Int,
    val lastUpdateEpochMs: Long?,
) {
    companion object {
        const val TRANSITION_UNKNOWN: Int = -1

        fun unavailable(reason: String?): ActivityRecognitionSnapshot =
            ActivityRecognitionSnapshot(
                playServicesAvailable = false,
                updatesActive = false,
                lastErrorMessage = reason,
                likelyInVehicle = false,
                lastDetectedActivityType = DetectedActivity.UNKNOWN,
                lastActivityTransitionType = TRANSITION_UNKNOWN,
                lastUpdateEpochMs = null,
            )

        fun idleSupportedButNotRunning(): ActivityRecognitionSnapshot =
            ActivityRecognitionSnapshot(
                playServicesAvailable = true,
                updatesActive = false,
                lastErrorMessage = null,
                likelyInVehicle = false,
                lastDetectedActivityType = DetectedActivity.UNKNOWN,
                lastActivityTransitionType = TRANSITION_UNKNOWN,
                lastUpdateEpochMs = null,
            )
    }
}

interface ActivityRecognitionGateway {
    val snapshot: StateFlow<ActivityRecognitionSnapshot>

    /**
     * Applies Activity Transition results delivered to the manifest receiver (or any [Intent]
     * carrying [com.google.android.gms.location.ActivityTransitionResult]). Returns true if a
     * result was applied.
     */
    fun ingestActivityRecognitionIntent(intent: Intent): Boolean

    suspend fun startUpdates()

    suspend fun stopUpdates()
}

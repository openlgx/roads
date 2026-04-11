package org.openlgx.roads.activityrecognition

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity

object ActivityRecognitionLabels {
    fun forType(type: Int): String =
        when (type) {
            DetectedActivity.IN_VEHICLE -> "In vehicle"
            DetectedActivity.ON_BICYCLE -> "On bicycle"
            DetectedActivity.ON_FOOT -> "On foot"
            DetectedActivity.RUNNING -> "Running"
            DetectedActivity.STILL -> "Still"
            DetectedActivity.TILTING -> "Tilting"
            DetectedActivity.WALKING -> "Walking"
            DetectedActivity.UNKNOWN -> "Unknown"
            else -> "Other ($type)"
        }

    fun forTransitionType(transitionType: Int): String =
        when (transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "enter"
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "exit"
            ActivityRecognitionSnapshot.TRANSITION_UNKNOWN -> "—"
            else -> "other ($transitionType)"
        }

    /** Human-readable activity + transition, e.g. "In vehicle · enter". */
    fun forActivityAndTransition(activityType: Int, transitionType: Int): String =
        "${forType(activityType)} · ${forTransitionType(transitionType)}"
}

package org.openlgx.roads.activityrecognition

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlgx.roads.BuildConfig
import timber.log.Timber

@Singleton
class ActivityRecognitionGatewayImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ActivityRecognitionGateway {

    private val appContext = context.applicationContext

    private val _snapshot =
        MutableStateFlow<ActivityRecognitionSnapshot>(
            ActivityRecognitionSnapshot.idleSupportedButNotRunning(),
        )
    override val snapshot: StateFlow<ActivityRecognitionSnapshot> = _snapshot.asStateFlow()

    private var pendingIntent: PendingIntent? = null

    private val recognitionClient: ActivityRecognitionClient by lazy {
        ActivityRecognition.getClient(appContext)
    }

    override fun ingestActivityRecognitionIntent(intent: Intent): Boolean {
        if (!ActivityTransitionResult.hasResult(intent)) return false
        val result = ActivityTransitionResult.extractResult(intent) ?: return false
        val events = result.transitionEvents
        if (events.isEmpty()) return false
        val last = events.last()
        applyTransitionEvent(last, markUpdatesActive = true)
        return true
    }

    override suspend fun startUpdates() {
        val playOk = ensurePlayServicesAvailable()
        if (!playOk) return

        try {
            val pi = pendingIntentForUpdates()
            pendingIntent = pi
            val request = ActivityTransitionRequest(buildActivityTransitions())
            Tasks.await(recognitionClient.requestActivityTransitionUpdates(request, pi))
            _snapshot.value =
                _snapshot.value.copy(
                    playServicesAvailable = true,
                    updatesActive = true,
                    lastErrorMessage = null,
                )
        } catch (se: SecurityException) {
            Timber.w(se, "Activity recognition permission missing")
            cleanupLocalPendingIntentOnly()
            _snapshot.value =
                ActivityRecognitionSnapshot.idleSupportedButNotRunning().copy(
                    lastErrorMessage = "SECURITY_EXCEPTION",
                    updatesActive = false,
                )
        } catch (t: Throwable) {
            Timber.w(t, "Activity recognition start failed")
            cleanupLocalPendingIntentOnly()
            _snapshot.value =
                ActivityRecognitionSnapshot.unavailable(t.message ?: "START_FAILED")
                    .copy(playServicesAvailable = true)
        }
    }

    override suspend fun stopUpdates() {
        val pi = pendingIntent
        if (pi != null) {
            try {
                Tasks.await(recognitionClient.removeActivityTransitionUpdates(pi))
            } catch (_: Throwable) {
                // Best-effort cleanup; PendingIntent may already be unregistered.
            }
        }
        cleanupLocalPendingIntentOnly()
        _snapshot.value =
            ActivityRecognitionSnapshot.idleSupportedButNotRunning().copy(
                playServicesAvailable = _snapshot.value.playServicesAvailable,
                lastErrorMessage = null,
                likelyInVehicle = false,
                lastDetectedActivityType = DetectedActivity.UNKNOWN,
                lastActivityTransitionType = ActivityRecognitionSnapshot.TRANSITION_UNKNOWN,
                lastUpdateEpochMs = null,
            )
    }

    private fun ensurePlayServicesAvailable(): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val result = availability.isGooglePlayServicesAvailable(appContext)
        if (result != ConnectionResult.SUCCESS) {
            val msg = availability.getErrorString(result)
            _snapshot.value = ActivityRecognitionSnapshot.unavailable(msg)
            Timber.w("Google Play services not available for activity recognition: $msg")
            return false
        }
        return true
    }

    private fun pendingIntentForUpdates(): PendingIntent {
        val intent = Intent(BuildConfig.ACTIVITY_RECOGNITION_UPDATES_ACTION).setPackage(appContext.packageName)
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
        return PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
    }

    private fun cleanupLocalPendingIntentOnly() {
        val pi = pendingIntent
        pendingIntent = null
        pi?.cancel()
    }

    private fun applyTransitionEvent(event: ActivityTransitionEvent, markUpdatesActive: Boolean) {
        val activityType = event.activityType
        val transitionType = event.transitionType
        val now = System.currentTimeMillis()

        val likelyInVehicle =
            when {
                activityType == DetectedActivity.IN_VEHICLE &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> true
                activityType == DetectedActivity.IN_VEHICLE &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT -> false
                activityType == DetectedActivity.ON_FOOT &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> false
                activityType == DetectedActivity.STILL &&
                    transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER -> false
                else -> _snapshot.value.likelyInVehicle
            }

        _snapshot.value =
            _snapshot.value.copy(
                playServicesAvailable = true,
                updatesActive = markUpdatesActive || _snapshot.value.updatesActive,
                likelyInVehicle = likelyInVehicle,
                lastDetectedActivityType = activityType,
                lastActivityTransitionType = transitionType,
                lastUpdateEpochMs = now,
            )
    }

    private fun buildActivityTransitions(): List<ActivityTransition> =
        listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_FOOT)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
        )

    private companion object {
        const val REQUEST_CODE: Int = 0xAC71
    }
}

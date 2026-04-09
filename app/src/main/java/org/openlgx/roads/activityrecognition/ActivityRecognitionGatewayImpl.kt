package org.openlgx.roads.activityrecognition

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
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
        if (!ActivityRecognitionResult.hasResult(intent)) return false
        val result = ActivityRecognitionResult.extractResult(intent) ?: return false
        applyDetected(result.mostProbableActivity, markUpdatesActive = true)
        return true
    }

    override suspend fun startUpdates() {
        val playOk = ensurePlayServicesAvailable()
        if (!playOk) return

        try {
            val pi = pendingIntentForUpdates()
            pendingIntent = pi
            Tasks.await(recognitionClient.requestActivityUpdates(UPDATE_INTERVAL_MS, pi))
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
                Tasks.await(recognitionClient.removeActivityUpdates(pi))
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
                lastConfidence = 0,
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

    private fun applyDetected(activity: DetectedActivity, markUpdatesActive: Boolean) {
        val inVehicle =
            activity.type == DetectedActivity.IN_VEHICLE && activity.confidence >= LIKELY_VEHICLE_CONFIDENCE
        val now = System.currentTimeMillis()
        _snapshot.value =
            _snapshot.value.copy(
                playServicesAvailable = true,
                updatesActive = markUpdatesActive || _snapshot.value.updatesActive,
                likelyInVehicle = inVehicle,
                lastDetectedActivityType = activity.type,
                lastConfidence = activity.confidence,
                lastUpdateEpochMs = now,
            )
    }

    private companion object {
        const val UPDATE_INTERVAL_MS: Long = 10_000L
        const val REQUEST_CODE: Int = 0xAC71
        const val LIKELY_VEHICLE_CONFIDENCE: Int = 50
    }
}

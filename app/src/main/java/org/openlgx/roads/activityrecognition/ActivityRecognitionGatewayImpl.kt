package org.openlgx.roads.activityrecognition

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
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

    private val actionUpdates = "${BuildConfig.APPLICATION_ID}.ACTION_ACTIVITY_RECOGNITION_UPDATES"

    private val _snapshot =
        MutableStateFlow<ActivityRecognitionSnapshot>(
            ActivityRecognitionSnapshot.idleSupportedButNotRunning(),
        )
    override val snapshot: StateFlow<ActivityRecognitionSnapshot> = _snapshot.asStateFlow()

    private var pendingIntent: PendingIntent? = null
    private var receiver: BroadcastReceiver? = null

    private val recognitionClient: ActivityRecognitionClient by lazy {
        ActivityRecognition.getClient(appContext)
    }

    private val activityReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                if (!ActivityRecognitionResult.hasResult(intent)) return
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                val activity = result.mostProbableActivity
                applyDetected(activity)
            }
        }

    override suspend fun startUpdates() {
        val playOk = ensurePlayServicesAvailable()
        if (!playOk) return

        if (_snapshot.value.updatesActive) return

        try {
            registerReceiverIfNeeded()
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
            teardownReceiverAndPendingIntent(clearPlayServicesFlag = false)
            _snapshot.value =
                ActivityRecognitionSnapshot.idleSupportedButNotRunning().copy(
                    lastErrorMessage = "SECURITY_EXCEPTION",
                    updatesActive = false,
                )
        } catch (t: Throwable) {
            Timber.w(t, "Activity recognition start failed")
            teardownReceiverAndPendingIntent(clearPlayServicesFlag = false)
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
        teardownReceiverAndPendingIntent(clearPlayServicesFlag = false)
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

    private fun registerReceiverIfNeeded() {
        if (receiver != null) return
        val filter = IntentFilter(actionUpdates)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                appContext,
                activityReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(activityReceiver, filter)
        }
        receiver = activityReceiver
    }

    private fun unregisterReceiverIfNeeded() {
        if (receiver == null) return
        try {
            appContext.unregisterReceiver(activityReceiver)
        } catch (_: Throwable) {
            // Already unregistered.
        }
        receiver = null
    }

    private fun pendingIntentForUpdates(): PendingIntent {
        val intent = Intent(actionUpdates).setPackage(appContext.packageName)
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
        return PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
    }

    private fun teardownReceiverAndPendingIntent(clearPlayServicesFlag: Boolean) {
        val pi = pendingIntent
        pendingIntent = null
        unregisterReceiverIfNeeded()
        if (pi != null) {
            pi.cancel()
        }
        if (clearPlayServicesFlag) {
            _snapshot.value = ActivityRecognitionSnapshot.unavailable(null)
        } else {
            _snapshot.value = _snapshot.value.copy(updatesActive = false)
        }
    }

    private fun applyDetected(activity: DetectedActivity) {
        val inVehicle =
            activity.type == DetectedActivity.IN_VEHICLE && activity.confidence >= LIKELY_VEHICLE_CONFIDENCE
        val now = System.currentTimeMillis()
        _snapshot.value =
            _snapshot.value.copy(
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

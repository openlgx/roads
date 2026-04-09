package org.openlgx.roads.location

import com.google.android.gms.location.Priority
import javax.inject.Inject
import javax.inject.Singleton
import org.openlgx.roads.permission.FineLocationPermissionChecker

fun interface ArmingDrivingGate {
    suspend fun passesLikelyDrivingGate(minSpeedMps: Float): Boolean
}

@Singleton
class DefaultArmingDrivingGate
@Inject
constructor(
    private val locationGateway: LocationGateway,
    private val fineLocationPermissionChecker: FineLocationPermissionChecker,
    private val config: LocationCaptureConfig,
) : ArmingDrivingGate {
    override suspend fun passesLikelyDrivingGate(minSpeedMps: Float): Boolean {
        if (!fineLocationPermissionChecker.isGranted()) return false
        val loc =
            locationGateway.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                config.armingLocationTimeoutMs,
            ) ?: return false
        if (!loc.hasSpeed()) return false
        return loc.speed >= minSpeedMps
    }
}

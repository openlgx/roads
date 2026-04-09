package org.openlgx.roads.location

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest

/**
 * Abstraction over fused location for tests and future provider swaps.
 */
interface LocationGateway {
    suspend fun getCurrentLocation(
        priority: Int,
        timeoutMs: Long,
    ): Location?

    fun requestLocationUpdates(
        request: LocationRequest,
        looper: Looper,
        onResult: (Location) -> Unit,
    ): LocationCallback

    fun removeLocationUpdates(callback: LocationCallback)
}

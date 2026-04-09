package org.openlgx.roads.location

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import timber.log.Timber

@Singleton
class FusedLocationGateway
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : LocationGateway {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    override suspend fun getCurrentLocation(
        priority: Int,
        timeoutMs: Long,
    ): Location? {
        val cancel = CancellationTokenSource()
        return try {
            Tasks.await(
                client.getCurrentLocation(priority, cancel.token),
                timeoutMs + 500L,
                TimeUnit.MILLISECONDS,
            )
        } catch (t: Throwable) {
            Timber.w(t, "getCurrentLocation failed")
            null
        } finally {
            cancel.cancel()
        }
    }

    override fun requestLocationUpdates(
        request: LocationRequest,
        looper: Looper,
        onResult: (Location) -> Unit,
    ): LocationCallback {
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    onResult(loc)
                }
            }
        client.requestLocationUpdates(request, callback, looper)
        return callback
    }

    override fun removeLocationUpdates(callback: LocationCallback) {
        client.removeLocationUpdates(callback).addOnFailureListener { e ->
            Timber.w(e, "removeLocationUpdates failed")
        }
    }
}

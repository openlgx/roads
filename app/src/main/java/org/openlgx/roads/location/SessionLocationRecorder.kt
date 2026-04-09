package org.openlgx.roads.location

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.di.ApplicationScope
import timber.log.Timber

@Singleton
class SessionLocationRecorder
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val gateway: LocationGateway,
    private val database: RoadsDatabase,
    private val config: LocationCaptureConfig,
    @ApplicationScope private val scope: CoroutineScope,
) : LocationRecordingController {

    private val mainLooper: Looper = context.mainLooper

    private val bufferLock = Any()

    private val buffer = ArrayList<LocationSampleEntity>(64)

    @Volatile
    private var locationCallback: LocationCallback? = null

    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var publishedTotal: Long = 0L

    private var flushJob: Job? = null

    private val _uiState = MutableStateFlow(LocationRecordingUiState())
    override val uiState: StateFlow<LocationRecordingUiState> = _uiState.asStateFlow()

    override fun startRecording(sessionId: Long) {
        if (sessionId <= 0L) {
            Timber.w("Ignoring location start: invalid sessionId=%s", sessionId)
            return
        }

        scope.launch {
            stopUpdatesAndFlushAll()

            publishedTotal = database.locationSampleDao().countForSession(sessionId)
            activeSessionId = sessionId
            publishAggregate(
                LocationRecordingUiState(
                    activeSessionId = sessionId,
                    publishedSampleCount = publishedTotal,
                    bufferedSampleCount = 0,
                    recordingLocation = true,
                    statusMessage = null,
                ),
            )

            val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, config.updateIntervalMs)
                    .setMinUpdateIntervalMillis(config.minUpdateIntervalMs)
                    .setMaxUpdateDelayMillis(config.maxUpdateDelayMs)
                    .build()

            val cb =
                gateway.requestLocationUpdates(request, mainLooper) { location ->
                    scope.launch {
                        onLocationUpdate(location, sessionId)
                    }
                }

            synchronized(bufferLock) { locationCallback = cb }

            flushJob =
                scope.launch {
                    while (isActive) {
                        delay(config.batchFlushIntervalMs)
                        flushToDatabase()
                    }
                }
        }
    }

    override suspend fun stopAndFlush() {
        stopUpdatesAndFlushAll()
    }

    private suspend fun stopUpdatesAndFlushAll() {
        flushJob?.cancel()
        flushJob = null

        val cb =
            synchronized(bufferLock) {
                val old = locationCallback
                locationCallback = null
                old
            }
        if (cb != null) {
            gateway.removeLocationUpdates(cb)
        }

        flushToDatabase()

        activeSessionId = null
        synchronized(bufferLock) { buffer.clear() }
        publishedTotal = 0L
        publishAggregate(LocationRecordingUiState())
    }

    private suspend fun onLocationUpdate(
        location: Location,
        expectedSessionId: Long,
    ) {
        if (activeSessionId != expectedSessionId) return

        val entity = location.toSampleEntity(expectedSessionId)
        var sizeAfter = 0
        synchronized(bufferLock) {
            buffer.add(entity)
            sizeAfter = buffer.size
        }

        publishAggregate(
            _uiState.value.copy(
                activeSessionId = expectedSessionId,
                lastWallClockEpochMs = location.time,
                lastSpeedMps = if (location.hasSpeed()) location.speed else null,
                lastLatitude = location.latitude,
                lastLongitude = location.longitude,
                bufferedSampleCount = sizeAfter,
                publishedSampleCount = publishedTotal,
                recordingLocation = true,
                statusMessage = null,
            ),
        )

        if (sizeAfter >= config.batchMaxSize) {
            flushToDatabase()
        }
    }

    private suspend fun flushToDatabase() {
        val batch =
            synchronized(bufferLock) {
                if (buffer.isEmpty()) return@synchronized emptyList()
                val copy = ArrayList(buffer)
                buffer.clear()
                copy
            }
        if (batch.isEmpty()) return

        database.locationSampleDao().insertAll(batch)
        publishedTotal += batch.size

        val sid = activeSessionId
        val buffered =
            synchronized(bufferLock) { buffer.size }

        publishAggregate(
            _uiState.value.copy(
                activeSessionId = sid,
                publishedSampleCount = publishedTotal,
                bufferedSampleCount = buffered,
            ),
        )
    }

    private fun publishAggregate(model: LocationRecordingUiState) {
        _uiState.value = model
    }
}

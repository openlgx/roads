package org.openlgx.roads.sensor

import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.HandlerThread
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.di.ApplicationScope
import timber.log.Timber

@Singleton
class SessionSensorRecorder
@Inject
constructor(
    private val gateway: SensorGateway,
    private val database: RoadsDatabase,
    private val config: SensorCaptureConfig,
    private val recordingSessionRepository: RecordingSessionRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : SensorRecordingController {

    private val bufferLock = Any()
    private val buffer = ArrayList<SensorSampleEntity>(128)

    private val flushSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    @Volatile
    private var handlerThread: HandlerThread? = null

    @Volatile
    private var registration: SensorRegistration? = null

    @Volatile
    private var listener: SensorEventListener? = null

    private var flushJob: Job? = null

    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var publishedTotal: Long = 0L

    private var callbackCountWindow: Int = 0

    private val _uiState = MutableStateFlow(SensorRecordingUiState())
    override val uiState: StateFlow<SensorRecordingUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            val initial = gateway.queryAvailability()
            _uiState.value = SensorRecordingUiState(hardwareAvailable = initial)
        }
        scope.launch {
            for (tick in flushSignal) {
                flushToDatabase()
            }
        }
    }

    override fun startRecording(sessionId: Long) {
        if (sessionId <= 0L) {
            Timber.w("Ignoring sensor start: invalid sessionId=%s", sessionId)
            return
        }

        scope.launch {
            stopUpdatesAndFlushAllInternal()

            val availability = gateway.queryAvailability()
            val enabled = resolveEnabledSubscription(availability, config)
            val (degraded, degradedReason) = degradedState(availability, config)

            val snapshotJson = buildSensorSnapshotJson(availability, enabled, degraded, degradedReason)
            recordingSessionRepository.updateSensorCaptureSnapshot(sessionId, snapshotJson)

            publishedTotal = database.sensorSampleDao().countForSession(sessionId)
            activeSessionId = sessionId

            callbackCountWindow = 0
            val thread = HandlerThread("roads-sensors").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)

            val lis =
                object : SensorEventListener {
                    override fun onAccuracyChanged(
                        sensor: android.hardware.Sensor?,
                        accuracy: Int,
                    ) = Unit

                    override fun onSensorChanged(event: SensorEvent) {
                        onSensorEventInline(event, sessionId)
                    }
                }
            listener = lis

            registration =
                gateway.registerEnabledSensors(
                    delayMicros = config.sensorDelayMicros,
                    callbackHandler = handler,
                    listener = lis,
                    enabledTypes = enabled.toGatewayTypes(),
                )

            flushJob =
                scope.launch {
                    while (isActive) {
                        delay(config.flushIntervalMs)
                        refreshCallbackRateEstimate()
                        flushToDatabase()
                    }
                }

            publishAggregate(
                SensorRecordingUiState(
                    activeSessionId = sessionId,
                    recordingSensors = true,
                    hardwareAvailable = availability,
                    enabledSubscribed = enabled.toAvailabilitySnapshot(),
                    publishedSampleCount = publishedTotal,
                    bufferedSampleCount = 0,
                    lastWallClockEpochMs = null,
                    lastElapsedRealtimeNanos = null,
                    estimatedCallbacksPerSecond = null,
                    degradedRecording = degraded,
                    degradedReason = degradedReason,
                ),
            )
        }
    }

    override suspend fun stopAndFlush() {
        stopUpdatesAndFlushAllInternal()
    }

    private suspend fun stopUpdatesAndFlushAllInternal() {
        flushJob?.cancel()
        flushJob = null

        registration?.unregister()
        registration = null

        listener = null

        handlerThread?.quitSafely()
        handlerThread = null

        flushToDatabase()

        activeSessionId = null
        synchronized(bufferLock) { buffer.clear() }
        publishedTotal = 0L
        callbackCountWindow = 0
        val idleAvail = gateway.queryAvailability()
        publishAggregate(SensorRecordingUiState(hardwareAvailable = idleAvail))
    }

    private fun onSensorEventInline(
        event: SensorEvent,
        expectedSessionId: Long,
    ) {
        if (activeSessionId != expectedSessionId) return

        val entity = event.toSampleEntity(expectedSessionId)
        var sizeAfter = 0
        synchronized(bufferLock) {
            buffer.add(entity)
            sizeAfter = buffer.size
            callbackCountWindow++
        }

        if ((callbackCountWindow and 0x1F) == 0) {
            publishThinUpdate(
                expectedSessionId,
                sizeAfter,
                entity.wallClockUtcEpochMs,
                entity.elapsedRealtimeNanos,
            )
        }

        if (sizeAfter >= config.batchMaxSize) {
            flushSignal.trySend(Unit)
        }
    }

    private fun publishThinUpdate(
        sessionId: Long,
        buffered: Int,
        lastWall: Long,
        lastElapsedNanos: Long,
    ) {
        val cur = _uiState.value
        if (cur.activeSessionId != sessionId) return
        _uiState.value =
            cur.copy(
                bufferedSampleCount = buffered,
                lastWallClockEpochMs = lastWall,
                lastElapsedRealtimeNanos = lastElapsedNanos,
                publishedSampleCount = publishedTotal,
            )
    }

    private fun refreshCallbackRateEstimate() {
        val count: Int
        synchronized(bufferLock) {
            count = callbackCountWindow
            callbackCountWindow = 0
        }
        val windowSec = config.flushIntervalMs / 1000f
        val perSec = if (windowSec > 0f) count / windowSec else null
        _uiState.value = _uiState.value.copy(estimatedCallbacksPerSecond = perSec)
    }

    private suspend fun flushToDatabase() {
        val expectedSession = activeSessionId
        val batch =
            synchronized(bufferLock) {
                if (buffer.isEmpty()) return@synchronized emptyList()
                val copy = ArrayList(buffer)
                buffer.clear()
                copy
            }
        if (batch.isEmpty()) return

        database.sensorSampleDao().insertAll(batch)
        publishedTotal += batch.size

        val sid = activeSessionId
        val buffered = synchronized(bufferLock) { buffer.size }

        val cur = _uiState.value
        if (sid == expectedSession && cur.activeSessionId == expectedSession) {
            _uiState.value =
                cur.copy(
                    publishedSampleCount = publishedTotal,
                    bufferedSampleCount = buffered,
                )
        }
    }

    private fun publishAggregate(model: SensorRecordingUiState) {
        _uiState.value = model
    }

    private data class ResolvedEnablement(
        val accelerometer: Boolean,
        val gyroscope: Boolean,
        val gravity: Boolean,
        val linearAcceleration: Boolean,
        val rotationVector: Boolean,
    ) {
        fun toGatewayTypes(): SensorEnabledTypes =
            SensorEnabledTypes(
                accelerometer = accelerometer,
                gyroscope = gyroscope,
                gravity = gravity,
                linearAcceleration = linearAcceleration,
                rotationVector = rotationVector,
            )

        fun toAvailabilitySnapshot(): SensorAvailabilitySnapshot =
            SensorAvailabilitySnapshot(
                accelerometer = accelerometer,
                gyroscope = gyroscope,
                gravity = gravity,
                linearAcceleration = linearAcceleration,
                rotationVector = rotationVector,
            )
    }

    private fun resolveEnabledSubscription(
        availability: SensorAvailabilitySnapshot,
        cfg: SensorCaptureConfig,
    ): ResolvedEnablement =
        ResolvedEnablement(
            accelerometer = cfg.enableAccelerometer && availability.accelerometer,
            gyroscope = cfg.enableGyroscope && availability.gyroscope,
            gravity = cfg.enableGravity && availability.gravity,
            linearAcceleration =
                cfg.enableLinearAcceleration && availability.linearAcceleration,
            rotationVector = cfg.enableRotationVector && availability.rotationVector,
        )

    private fun degradedState(
        availability: SensorAvailabilitySnapshot,
        cfg: SensorCaptureConfig,
    ): Pair<Boolean, String?> {
        val critical = ArrayList<String>(2)
        if (cfg.enableAccelerometer && !availability.accelerometer) {
            critical.add("accelerometer unavailable")
        }
        if (cfg.enableGyroscope && !availability.gyroscope) {
            critical.add("gyroscope unavailable")
        }
        val degraded = critical.isNotEmpty()
        val reason = if (degraded) critical.joinToString("; ") else null
        return degraded to reason
    }

    private fun buildSensorSnapshotJson(
        availability: SensorAvailabilitySnapshot,
        enabled: ResolvedEnablement,
        degraded: Boolean,
        degradedReason: String?,
    ): String {
        val o =
            JSONObject().apply {
                put("sensorDelayMicros", config.sensorDelayMicros)
                put("batchMaxSize", config.batchMaxSize)
                put("flushIntervalMs", config.flushIntervalMs)
                put(
                    "hardwareAvailable",
                    JSONObject().apply {
                        put("accelerometer", availability.accelerometer)
                        put("gyroscope", availability.gyroscope)
                        put("gravity", availability.gravity)
                        put("linearAcceleration", availability.linearAcceleration)
                        put("rotationVector", availability.rotationVector)
                    },
                )
                put(
                    "enabledSubscribed",
                    JSONObject().apply {
                        put("accelerometer", enabled.accelerometer)
                        put("gyroscope", enabled.gyroscope)
                        put("gravity", enabled.gravity)
                        put("linearAcceleration", enabled.linearAcceleration)
                        put("rotationVector", enabled.rotationVector)
                    },
                )
                put("degradedRecording", degraded)
                if (degradedReason != null) {
                    put("degradedReason", degradedReason)
                }
            }
        return o.toString()
    }
}

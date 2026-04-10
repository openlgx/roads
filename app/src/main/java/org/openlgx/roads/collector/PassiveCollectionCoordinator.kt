package org.openlgx.roads.collector

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.openlgx.roads.activityrecognition.ActivityRecognitionGateway
import org.openlgx.roads.activityrecognition.ActivityRecognitionLabels
import org.openlgx.roads.activityrecognition.ActivityRecognitionSnapshot
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.repo.AutoRecordingSessionStartParams
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.di.ApplicationScope
import org.openlgx.roads.location.ArmingDrivingGate
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.location.LocationRecordingUiState
import org.openlgx.roads.permission.ActivityRecognitionPermissionChecker
import org.openlgx.roads.permission.FineLocationPermissionChecker
import org.openlgx.roads.processing.calibration.SessionCalibrationHook
import org.openlgx.roads.processing.ondevice.SessionProcessingScheduler
import org.openlgx.roads.upload.SessionUploadScheduling
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.service.CollectorForegroundPresentation
import org.openlgx.roads.service.CollectorForegroundPresentationRegistry
import org.openlgx.roads.service.CollectorForegroundServiceController
import org.openlgx.roads.service.CollectorServiceStateRegistry
import org.openlgx.roads.service.RecordingEventNotifier
import timber.log.Timber

@Singleton
class PassiveCollectionCoordinator
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val activityRecognitionGateway: ActivityRecognitionGateway,
    private val recordingSessionRepository: RecordingSessionRepository,
    private val foregroundServiceController: CollectorForegroundServiceController,
    private val permissionChecker: ActivityRecognitionPermissionChecker,
    private val fineLocationPermissionChecker: FineLocationPermissionChecker,
    private val armingDrivingGate: ArmingDrivingGate,
    private val locationRecordingController: LocationRecordingController,
    private val sensorRecordingController: SensorRecordingController,
    private val serviceStateRegistry: CollectorServiceStateRegistry,
    private val sessionCalibrationHook: SessionCalibrationHook,
    private val sessionProcessingScheduler: SessionProcessingScheduler,
    private val sessionUploadScheduling: SessionUploadScheduling,
    private val foregroundPresentationRegistry: CollectorForegroundPresentationRegistry,
    private val recordingEventNotifier: RecordingEventNotifier,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : PassiveCollectionHandle {
    private val mailbox = Channel<Unit>(capacity = Channel.CONFLATED)

    private var lifecycleState: CollectorLifecycleState = CollectorLifecycleState.IDLE
    private var armingStartedAtEpochMs: Long? = null
    private var activeSessionId: Long? = null
    private var armingJob: Job? = null
    private var stopHoldTimeoutJob: Job? = null
    private var debugDrivingOverride: Boolean? = null

    /** Streak tracking for entering [CollectorLifecycleState.STOP_HOLD]. */
    private var lowMovementSinceEpochMs: Long? = null

    @Volatile private var pendingDebugForceRecording: Boolean = false

    @Volatile private var pendingDebugReset: Boolean = false

    private var lastSettings: AppSettings? = null

    private var previousPublishedLifecycle: CollectorLifecycleState? = null
    private var lastLifecycleTransitionEpochMs: Long? = null
    private var lastLifecycleTransitionLabel: String = "—"

    private val _uiState = MutableStateFlow(PassiveCollectionUiModel())
    override val uiState: StateFlow<PassiveCollectionUiModel> = _uiState.asStateFlow()

    private var started: Boolean = false

    override fun start() {
        if (started) return
        started = true

        applicationScope.launch {
            for (tick in mailbox) {
                runCatching { reconcileOnce() }
                    .onFailure { Timber.w(it, "Passive collector reconcile failed") }
            }
        }

        applicationScope.launch {
            appSettingsRepository.settings.collectLatest {
                lastSettings = it
                mailbox.trySend(Unit)
            }
        }

        applicationScope.launch {
            activityRecognitionGateway.snapshot.collectLatest { mailbox.trySend(Unit) }
        }

        applicationScope.launch {
            serviceStateRegistry.foregroundRunning.collectLatest { mailbox.trySend(Unit) }
        }

        applicationScope.launch {
            locationRecordingController.uiState.collectLatest {
                mailbox.trySend(Unit)
            }
        }

        mailbox.trySend(Unit)
    }

    override fun debugSetSimulateDriving(active: Boolean?) {
        debugDrivingOverride = active
        mailbox.trySend(Unit)
    }

    override fun debugForceStartRecording() {
        applicationScope.launch {
            val settings = lastSettings ?: appSettingsRepository.settings.first()
            if (!settings.debugModeEnabled) return@launch
            pendingDebugForceRecording = true
            mailbox.trySend(Unit)
        }
    }

    override fun debugResetStateMachine() {
        applicationScope.launch {
            pendingDebugReset = true
            mailbox.trySend(Unit)
        }
    }

    private suspend fun reconcileOnce() {
        if (pendingDebugReset) {
            pendingDebugReset = false
            val settings = lastSettings ?: return
            cancelDeferredJobs()
            endActiveSessionIfNeeded(settings, SessionState.INTERRUPTED)
            lifecycleState = CollectorLifecycleState.IDLE
            publishUi()
            return
        }

        if (pendingDebugForceRecording) {
            pendingDebugForceRecording = false
            val settings = lastSettings ?: return
            if (!settings.debugModeEnabled) return
            if (lifecycleState == CollectorLifecycleState.RECORDING ||
                lifecycleState == CollectorLifecycleState.STOP_HOLD
            ) {
                return
            }
            enterRecordingBypassArming(settings)
            return
        }

        val settings = lastSettings ?: return

        // If the process died (or the FGS was killed) before [finalizeCompletedSession] / interrupt paths
        // ran, Room can still have an ACTIVE session with null [endedAtEpochMs] while this coordinator
        // restarts idle with no [activeSessionId]. Close that row so the UI, exports, and processing
        // backfill match reality.
        if (activeSessionId == null &&
            lifecycleState != CollectorLifecycleState.RECORDING &&
            lifecycleState != CollectorLifecycleState.STOP_HOLD
        ) {
            val orphanId = recordingSessionRepository.findOpenSessionId()
            if (orphanId != null) {
                finalizeOrphanOpenSessionInDb(settings, orphanId)
                return
            }
        }

        val snap = activityRecognitionGateway.snapshot.value
        val perm = permissionChecker.isGranted()
        val passive = settings.passiveCollectionEffective
        val arOk = snap.playServicesAvailable

        if (passive && perm && arOk) {
            activityRecognitionGateway.startUpdates()
        } else {
            activityRecognitionGateway.stopUpdates()
        }

        val snapAfter = activityRecognitionGateway.snapshot.value
        val updatesActive = snapAfter.updatesActive

        if (!passive) {
            cancelDeferredJobs()
            if (lifecycleState == CollectorLifecycleState.RECORDING ||
                lifecycleState == CollectorLifecycleState.STOP_HOLD
            ) {
                endActiveSessionIfNeeded(settings, SessionState.INTERRUPTED)
            }
            if (lifecycleState == CollectorLifecycleState.ARMING) {
                lifecycleState = CollectorLifecycleState.IDLE
            }
            lifecycleState = CollectorLifecycleState.IDLE
            publishUi(settings, snapAfter, perm, updatesActive, passive, arOk)
            return
        }

        if (!arOk) {
            cancelDeferredJobs()
            if (lifecycleState == CollectorLifecycleState.RECORDING ||
                lifecycleState == CollectorLifecycleState.STOP_HOLD
            ) {
                endActiveSessionIfNeeded(settings, SessionState.INTERRUPTED)
            }
            if (lifecycleState == CollectorLifecycleState.ARMING) {
                lifecycleState = CollectorLifecycleState.IDLE
            }
            lifecycleState = CollectorLifecycleState.DEGRADED
            publishUi(settings, snapAfter, perm, updatesActive, passive, arOk)
            return
        }

        if (!perm) {
            cancelDeferredJobs()
            if (lifecycleState == CollectorLifecycleState.RECORDING ||
                lifecycleState == CollectorLifecycleState.STOP_HOLD
            ) {
                endActiveSessionIfNeeded(settings, SessionState.ABORTED_POLICY)
            }
            if (lifecycleState == CollectorLifecycleState.ARMING) {
                lifecycleState = CollectorLifecycleState.IDLE
            }
            lifecycleState = CollectorLifecycleState.PAUSED_POLICY
            publishUi(settings, snapAfter, perm, updatesActive, passive, arOk)
            return
        }

        if (lifecycleState == CollectorLifecycleState.PAUSED_POLICY ||
            lifecycleState == CollectorLifecycleState.DEGRADED
        ) {
            lifecycleState = CollectorLifecycleState.IDLE
        }

        val likelyDriving =
            when (debugDrivingOverride) {
                true -> true
                false -> false
                null ->
                    updatesActive && snapAfter.likelyInVehicle && passive && perm && arOk
            }

        val loc = locationRecordingController.uiState.value

        when (lifecycleState) {
            CollectorLifecycleState.IDLE -> {
                if (likelyDriving) {
                    startArmingWindow(settings, snapAfter)
                }
            }

            CollectorLifecycleState.ARMING -> {
                if (!likelyDriving) {
                    cancelDeferredJobs()
                    lifecycleState = CollectorLifecycleState.IDLE
                }
            }

            CollectorLifecycleState.RECORDING -> {
                if (movementConfident(settings, loc)) {
                    lowMovementSinceEpochMs = null
                } else {
                    val now = System.currentTimeMillis()
                    val start = lowMovementSinceEpochMs ?: now
                    lowMovementSinceEpochMs = start
                    if (now - start >= LOW_MOVEMENT_ENTER_STOP_HOLD_MS) {
                        enterStopHold(settings)
                    }
                }
            }

            CollectorLifecycleState.STOP_HOLD -> {
                if (movementResumedFromHold(settings, loc)) {
                    cancelStopHoldTimeout()
                    lifecycleState = CollectorLifecycleState.RECORDING
                    lowMovementSinceEpochMs = null
                    val sid = activeSessionId
                    if (sid != null) {
                        foregroundServiceController.startCollectorService(sid)
                    }
                }
            }

            CollectorLifecycleState.PAUSED_POLICY,
            CollectorLifecycleState.DEGRADED,
            -> Unit
        }

        publishUi(settings, snapAfter, perm, updatesActive, passive, arOk)
    }

    private fun movementConfident(settings: AppSettings, loc: LocationRecordingUiState): Boolean =
        when (debugDrivingOverride) {
            true -> true
            false -> false
            null -> movementConfidentWhenNotOverridden(settings, loc)
        }

    private fun movementConfidentWhenNotOverridden(
        settings: AppSettings,
        loc: LocationRecordingUiState,
    ): Boolean {
        if (activeSessionId == null) return false
        if (!loc.recordingLocation || loc.activeSessionId != activeSessionId) return false
        val sp = loc.lastSpeedMps ?: return false
        return sp >= settings.captureStopSpeedMps
    }

    private fun movementResumedFromHold(settings: AppSettings, loc: LocationRecordingUiState): Boolean =
        when (debugDrivingOverride) {
            true -> true
            false -> false
            null -> movementResumedFromHoldWhenNotOverridden(settings, loc)
        }

    private fun movementResumedFromHoldWhenNotOverridden(
        settings: AppSettings,
        loc: LocationRecordingUiState,
    ): Boolean {
        if (activeSessionId == null) return false
        if (!loc.recordingLocation || loc.activeSessionId != activeSessionId) return false
        val sp = loc.lastSpeedMps ?: return false
        return sp >= settings.captureStartSpeedMps
    }

    private fun enterStopHold(settings: AppSettings) {
        if (lifecycleState != CollectorLifecycleState.RECORDING) return
        lifecycleState = CollectorLifecycleState.STOP_HOLD
        lowMovementSinceEpochMs = null
        cancelStopHoldTimeout()
        val holdMs = settings.captureStopHoldSeconds * 1000L
        stopHoldTimeoutJob =
            applicationScope.launch {
                delay(holdMs)
                finalizeCompletedSession()
            }
    }

    private fun cancelStopHoldTimeout() {
        stopHoldTimeoutJob?.cancel()
        stopHoldTimeoutJob = null
    }

    private fun armingDebounceMs(settings: AppSettings, snap: ActivityRecognitionSnapshot): Long {
        if (debugDrivingOverride == true) return FAST_ARMING_DEBOUNCE_MS
        val loc = locationRecordingController.uiState.value
        val vehicle = snap.likelyInVehicle && snap.updatesActive
        if (settings.captureFastArmingEnabled &&
            vehicle &&
            loc.lastSpeedMps != null &&
            loc.lastSpeedMps >= settings.captureImmediateStartSpeedMps
        ) {
            return FAST_ARMING_DEBOUNCE_MS
        }
        return NORMAL_ARMING_DEBOUNCE_MS
    }

    private fun startArmingWindow(settings: AppSettings, snap: ActivityRecognitionSnapshot) {
        if (lifecycleState != CollectorLifecycleState.IDLE) return
        lifecycleState = CollectorLifecycleState.ARMING
        armingStartedAtEpochMs = System.currentTimeMillis()
        armingJob?.cancel()
        val debounce = armingDebounceMs(settings, snap)
        armingJob =
            applicationScope.launch {
                delay(debounce)
                onArmingWindowComplete()
            }
    }

    private suspend fun onArmingWindowComplete() {
        val settings = lastSettings ?: return
        if (lifecycleState != CollectorLifecycleState.ARMING) return
        val snap = activityRecognitionGateway.snapshot.value
        val perm = permissionChecker.isGranted()
        val passive = settings.passiveCollectionEffective
        val arOk = snap.playServicesAvailable
        val updatesActive = snap.updatesActive
        val likelyDriving =
            when (debugDrivingOverride) {
                true -> true
                false -> false
                null -> updatesActive && snap.likelyInVehicle && passive && perm && arOk
            }
        if (!likelyDriving) {
            lifecycleState = CollectorLifecycleState.IDLE
            publishUi()
            return
        }

        val gateOk =
            when (debugDrivingOverride) {
                true -> true
                else -> armingDrivingGate.passesLikelyDrivingGate(settings.captureStartSpeedMps)
            }
        if (!gateOk) {
            lifecycleState = CollectorLifecycleState.IDLE
            publishUi()
            return
        }
        enterRecordingFromArming(settings)
    }

    private suspend fun enterRecordingFromArming(settings: AppSettings) {
        val now = System.currentTimeMillis()
        val armedAt = armingStartedAtEpochMs ?: now
        armingJob = null
        val snap = activityRecognitionGateway.snapshot.value
        val json = buildCollectorSnapshotJson(CollectorLifecycleState.RECORDING, snap)
        val params =
            AutoRecordingSessionStartParams(
                armedAtEpochMs = armedAt,
                recordingStartedAtEpochMs = now,
                recordingSource = RecordingSource.AUTO,
                collectorStateSnapshotJson = json,
                capturePassiveCollectionEnabled = settings.passiveCollectionEffective,
                uploadPolicyWifiOnly = settings.uploadWifiOnly,
                uploadPolicyAllowCellular = settings.uploadAllowCellular,
                uploadPolicyOnlyWhileCharging = settings.uploadOnlyWhileCharging,
                uploadPolicyPauseOnLowBattery = settings.uploadPauseOnLowBatteryEnabled,
                uploadPolicyLowBatteryThresholdPercent = settings.uploadLowBatteryThresholdPercent,
            )
        val id = recordingSessionRepository.beginAutoRecordingSession(params)
        activeSessionId = id
        lifecycleState = CollectorLifecycleState.RECORDING
        lowMovementSinceEpochMs = null
        armingStartedAtEpochMs = null
        foregroundServiceController.startCollectorService(id)
        recordingEventNotifier.notifyRecordingStarted(id)
        appSettingsRepository.recordRecordingStartedAt(now)
        publishUi(settings, snap, permissionChecker.isGranted(), snap.updatesActive, true, true)
    }

    private suspend fun enterRecordingBypassArming(settings: AppSettings) {
        if (lifecycleState == CollectorLifecycleState.RECORDING ||
            lifecycleState == CollectorLifecycleState.STOP_HOLD
        ) {
            return
        }
        cancelDeferredJobs()
        val now = System.currentTimeMillis()
        armingStartedAtEpochMs = null
        val snap = activityRecognitionGateway.snapshot.value
        val json =
            buildCollectorSnapshotJson(
                CollectorLifecycleState.RECORDING,
                snap,
                debugNote = "manual_debug_force",
            )
        val params =
            AutoRecordingSessionStartParams(
                armedAtEpochMs = now,
                recordingStartedAtEpochMs = now,
                recordingSource = RecordingSource.MANUAL_DEBUG,
                collectorStateSnapshotJson = json,
                capturePassiveCollectionEnabled = settings.passiveCollectionEffective,
                uploadPolicyWifiOnly = settings.uploadWifiOnly,
                uploadPolicyAllowCellular = settings.uploadAllowCellular,
                uploadPolicyOnlyWhileCharging = settings.uploadOnlyWhileCharging,
                uploadPolicyPauseOnLowBattery = settings.uploadPauseOnLowBatteryEnabled,
                uploadPolicyLowBatteryThresholdPercent = settings.uploadLowBatteryThresholdPercent,
            )
        val id = recordingSessionRepository.beginAutoRecordingSession(params)
        activeSessionId = id
        lifecycleState = CollectorLifecycleState.RECORDING
        lowMovementSinceEpochMs = null
        foregroundServiceController.startCollectorService(id)
        recordingEventNotifier.notifyRecordingStarted(id)
        appSettingsRepository.recordRecordingStartedAt(now)
        publishUi()
    }

    private suspend fun finalizeOrphanOpenSessionInDb(
        settings: AppSettings,
        orphanId: Long,
    ) {
        Timber.i("Finalizing orphan open session %s (process restart before in-memory finalize)", orphanId)
        cancelDeferredJobs()
        locationRecordingController.stopAndFlush()
        sensorRecordingController.stopAndFlush()
        val endedAt = System.currentTimeMillis()
        recordingSessionRepository.endRecordingSession(
            sessionId = orphanId,
            endedAtEpochMs = endedAt,
            endState = SessionState.COMPLETED,
        )
        sessionCalibrationHook.onRecordingSessionEnded(
            sessionId = orphanId,
            endState = SessionState.COMPLETED,
            endedAtEpochMs = endedAt,
            calibrationWorkflowEnabled = settings.calibrationWorkflowEnabled,
        )
        if (settings.processingLiveAfterSessionEnabled) {
            sessionProcessingScheduler.onSessionRecordingCompleted(orphanId)
        }
        sessionUploadScheduling.scheduleAfterSessionCompleted(orphanId, settings)
        recordingEventNotifier.notifyRecordingStopped(orphanId, SessionState.COMPLETED.name)
        appSettingsRepository.recordRecordingStoppedAt(endedAt)
        foregroundServiceController.stopCollectorService()
        publishUi()
    }

    private suspend fun finalizeCompletedSession() {
        if (lifecycleState != CollectorLifecycleState.STOP_HOLD) return
        if (lastSettings == null) return
        val sid = activeSessionId
        locationRecordingController.stopAndFlush()
        sensorRecordingController.stopAndFlush()
        activeSessionId = null
        lifecycleState = CollectorLifecycleState.IDLE
        cancelStopHoldTimeout()
        lowMovementSinceEpochMs = null
        if (sid != null) {
            val endedAt = System.currentTimeMillis()
            recordingSessionRepository.endRecordingSession(
                sessionId = sid,
                endedAtEpochMs = endedAt,
                endState = SessionState.COMPLETED,
            )
            sessionCalibrationHook.onRecordingSessionEnded(
                sessionId = sid,
                endState = SessionState.COMPLETED,
                endedAtEpochMs = endedAt,
                calibrationWorkflowEnabled = lastSettings!!.calibrationWorkflowEnabled,
            )
            if (lastSettings!!.processingLiveAfterSessionEnabled) {
                sessionProcessingScheduler.onSessionRecordingCompleted(sid)
            }
            sessionUploadScheduling.scheduleAfterSessionCompleted(sid, lastSettings!!)
            recordingEventNotifier.notifyRecordingStopped(sid, SessionState.COMPLETED.name)
            appSettingsRepository.recordRecordingStoppedAt(endedAt)
        }
        foregroundServiceController.stopCollectorService()
        publishUi()
    }

    private suspend fun endActiveSessionIfNeeded(
        settings: AppSettings,
        endState: SessionState,
    ) {
        val sid = activeSessionId
        locationRecordingController.stopAndFlush()
        sensorRecordingController.stopAndFlush()
        cancelStopHoldTimeout()
        lowMovementSinceEpochMs = null
        if (sid == null) {
            foregroundServiceController.stopCollectorService()
            lifecycleState = CollectorLifecycleState.IDLE
            return
        }
        activeSessionId = null
        lifecycleState = CollectorLifecycleState.IDLE
        val endedAt = System.currentTimeMillis()
        recordingSessionRepository.endRecordingSession(
            sessionId = sid,
            endedAtEpochMs = endedAt,
            endState = endState,
        )
        sessionCalibrationHook.onRecordingSessionEnded(
            sessionId = sid,
            endState = endState,
            endedAtEpochMs = endedAt,
            calibrationWorkflowEnabled = settings.calibrationWorkflowEnabled,
        )
        recordingEventNotifier.notifyRecordingStopped(sid, endState.name)
        appSettingsRepository.recordRecordingStoppedAt(endedAt)
        foregroundServiceController.stopCollectorService()
    }

    private fun cancelDeferredJobs() {
        armingJob?.cancel()
        armingJob = null
        cancelStopHoldTimeout()
        armingStartedAtEpochMs = null
        lowMovementSinceEpochMs = null
    }

    private fun publishUi() {
        val settings = lastSettings ?: return
        val snap = activityRecognitionGateway.snapshot.value
        publishUi(
            settings = settings,
            snap = snap,
            perm = permissionChecker.isGranted(),
            updatesActive = snap.updatesActive,
            passive = settings.passiveCollectionEffective,
            arOk = snap.playServicesAvailable,
        )
    }

    private fun publishUi(
        settings: AppSettings,
        snap: ActivityRecognitionSnapshot,
        perm: Boolean,
        updatesActive: Boolean,
        passive: Boolean,
        arOk: Boolean,
    ) {
        if (previousPublishedLifecycle != lifecycleState) {
            previousPublishedLifecycle = lifecycleState
            lastLifecycleTransitionEpochMs = System.currentTimeMillis()
            lastLifecycleTransitionLabel = lifecycleState.name
        }

        val fg = serviceStateRegistry.foregroundRunning.value
        val fineLoc = fineLocationPermissionChecker.isGranted()
        val recording = lifecycleState == CollectorLifecycleState.RECORDING
        val likely =
            when (debugDrivingOverride) {
                true -> true
                false -> false
                null -> updatesActive && snap.likelyInVehicle && passive && perm && arOk
            }
        val label = ActivityRecognitionLabels.forType(snap.lastDetectedActivityType)
        val sid = activeSessionId
        val pres =
            when (lifecycleState) {
                CollectorLifecycleState.RECORDING,
                CollectorLifecycleState.STOP_HOLD,
                -> {
                    if (sid == null) null
                    else {
                        CollectorForegroundPresentation(
                            sessionId = sid,
                            sessionShortLabel = "#$sid",
                            lifecycleState = lifecycleState,
                            degradedOrPolicy = false,
                        )
                    }
                }
                CollectorLifecycleState.ARMING ->
                    CollectorForegroundPresentation(
                        sessionId = 0L,
                        sessionShortLabel = "arming",
                        lifecycleState = lifecycleState,
                        degradedOrPolicy = false,
                    )
                else -> null
            }
        foregroundPresentationRegistry.publish(pres)

        _uiState.value =
            PassiveCollectionUiModel(
                lifecycleState = lifecycleState,
                passiveCollectionRuntimeDesired = passive,
                likelyInVehicle = likely,
                activityRecognitionSupported = arOk,
                activityRecognitionUpdatesActive = updatesActive,
                activityRecognitionPermissionGranted = perm,
                fineLocationPermissionGranted = fineLoc,
                lastActivityType = snap.lastDetectedActivityType,
                lastActivityConfidence = snap.lastConfidence,
                lastActivityLabel = label,
                recordingActive =
                    recording ||
                        lifecycleState == CollectorLifecycleState.STOP_HOLD,
                activeSessionId = activeSessionId,
                foregroundServiceRunning = fg,
                lastLifecycleTransitionEpochMs = lastLifecycleTransitionEpochMs,
                lastLifecycleTransitionLabel = lastLifecycleTransitionLabel,
            )
    }

    private fun buildCollectorSnapshotJson(
        state: CollectorLifecycleState,
        snap: ActivityRecognitionSnapshot,
        debugNote: String? = null,
    ): String {
        val o =
            JSONObject().apply {
                put("collectorLifecycle", state.name)
                put("activityType", snap.lastDetectedActivityType)
                put("confidence", snap.lastConfidence)
                put("updatesActive", snap.updatesActive)
                if (debugNote != null) {
                    put("note", debugNote)
                }
            }
        return o.toString()
    }

    private companion object {
        const val FAST_ARMING_DEBOUNCE_MS: Long = 500L
        const val NORMAL_ARMING_DEBOUNCE_MS: Long = 1_600L
        const val LOW_MOVEMENT_ENTER_STOP_HOLD_MS: Long = 12_000L
    }
}

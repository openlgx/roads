package org.openlgx.roads.collector

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.openlgx.roads.activityrecognition.ActivityRecognitionGateway
import org.openlgx.roads.activityrecognition.ActivityRecognitionLabels
import org.openlgx.roads.activityrecognition.ActivityRecognitionSnapshot
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.repo.AutoRecordingSessionStartParams
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.di.ApplicationScope
import org.openlgx.roads.permission.ActivityRecognitionPermissionChecker
import org.openlgx.roads.service.CollectorForegroundServiceController
import org.openlgx.roads.service.CollectorServiceStateRegistry
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
    private val serviceStateRegistry: CollectorServiceStateRegistry,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : PassiveCollectionHandle {
    private val mailbox = Channel<Unit>(capacity = Channel.CONFLATED)

    private var lifecycleState: CollectorLifecycleState = CollectorLifecycleState.IDLE
    private var armingStartedAtEpochMs: Long? = null
    private var activeSessionId: Long? = null
    private var armingJob: Job? = null
    private var cooldownJob: Job? = null
    private var debugDrivingOverride: Boolean? = null

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
                lifecycleState == CollectorLifecycleState.COOLDOWN
            ) {
                return
            }
            enterRecordingBypassArming(settings)
            return
        }

        val settings = lastSettings ?: return
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
                lifecycleState == CollectorLifecycleState.COOLDOWN
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
                lifecycleState == CollectorLifecycleState.COOLDOWN
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
                lifecycleState == CollectorLifecycleState.COOLDOWN
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

        when (lifecycleState) {
            CollectorLifecycleState.IDLE -> {
                if (likelyDriving) {
                    startArmingWindow()
                }
            }

            CollectorLifecycleState.ARMING -> {
                if (!likelyDriving) {
                    cancelDeferredJobs()
                    lifecycleState = CollectorLifecycleState.IDLE
                }
            }

            CollectorLifecycleState.RECORDING -> {
                if (!likelyDriving) {
                    startCooldownWindow()
                }
            }

            CollectorLifecycleState.COOLDOWN -> {
                if (likelyDriving) {
                    cancelDeferredJobs()
                    lifecycleState = CollectorLifecycleState.RECORDING
                    foregroundServiceController.startCollectorService()
                }
            }

            CollectorLifecycleState.PAUSED_POLICY,
            CollectorLifecycleState.DEGRADED,
            -> Unit
        }

        publishUi(settings, snapAfter, perm, updatesActive, passive, arOk)
    }

    private fun startArmingWindow() {
        if (lifecycleState != CollectorLifecycleState.IDLE) return
        lifecycleState = CollectorLifecycleState.ARMING
        armingStartedAtEpochMs = System.currentTimeMillis()
        armingJob?.cancel()
        armingJob =
            applicationScope.launch {
                delay(ARMING_DEBOUNCE_MS)
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
        armingStartedAtEpochMs = null
        foregroundServiceController.startCollectorService()
        publishUi(settings, snap, permissionChecker.isGranted(), snap.updatesActive, true, true)
    }

    private suspend fun enterRecordingBypassArming(settings: AppSettings) {
        if (lifecycleState == CollectorLifecycleState.RECORDING ||
            lifecycleState == CollectorLifecycleState.COOLDOWN
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
        foregroundServiceController.startCollectorService()
        publishUi()
    }

    private fun startCooldownWindow() {
        if (lifecycleState != CollectorLifecycleState.RECORDING) return
        lifecycleState = CollectorLifecycleState.COOLDOWN
        cooldownJob?.cancel()
        cooldownJob =
            applicationScope.launch {
                delay(COOLDOWN_MS)
                onCooldownComplete()
            }
    }

    private suspend fun onCooldownComplete() {
        if (lifecycleState != CollectorLifecycleState.COOLDOWN) return
        val settings = lastSettings ?: return
        val sid = activeSessionId
        activeSessionId = null
        lifecycleState = CollectorLifecycleState.IDLE
        cooldownJob = null
        if (sid != null) {
            recordingSessionRepository.endRecordingSession(
                sessionId = sid,
                endedAtEpochMs = System.currentTimeMillis(),
                endState = SessionState.COMPLETED,
            )
        }
        foregroundServiceController.stopCollectorService()
        publishUi()
    }

    private suspend fun endActiveSessionIfNeeded(settings: AppSettings, endState: SessionState) {
        val sid = activeSessionId
        if (sid == null) {
            foregroundServiceController.stopCollectorService()
            return
        }
        activeSessionId = null
        recordingSessionRepository.endRecordingSession(
            sessionId = sid,
            endedAtEpochMs = System.currentTimeMillis(),
            endState = endState,
        )
        foregroundServiceController.stopCollectorService()
    }

    private fun cancelDeferredJobs() {
        armingJob?.cancel()
        armingJob = null
        cooldownJob?.cancel()
        cooldownJob = null
        armingStartedAtEpochMs = null
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
        val recording = lifecycleState == CollectorLifecycleState.RECORDING
        val likely =
            when (debugDrivingOverride) {
                true -> true
                false -> false
                null -> updatesActive && snap.likelyInVehicle && passive && perm && arOk
            }
        val label = ActivityRecognitionLabels.forType(snap.lastDetectedActivityType)
        _uiState.value =
            PassiveCollectionUiModel(
                lifecycleState = lifecycleState,
                passiveCollectionRuntimeDesired = passive,
                likelyInVehicle = likely,
                activityRecognitionSupported = arOk,
                activityRecognitionUpdatesActive = updatesActive,
                activityRecognitionPermissionGranted = perm,
                lastActivityType = snap.lastDetectedActivityType,
                lastActivityConfidence = snap.lastConfidence,
                lastActivityLabel = label,
                recordingActive = recording || lifecycleState == CollectorLifecycleState.COOLDOWN,
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
        const val ARMING_DEBOUNCE_MS: Long = 3_500L
        const val COOLDOWN_MS: Long = 4_000L
    }
}

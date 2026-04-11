package org.openlgx.roads.collector

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlgx.roads.FakeAppSettingsRepository
import org.openlgx.roads.MainDispatcherRule
import org.openlgx.roads.activityrecognition.ActivityRecognitionGateway
import org.openlgx.roads.activityrecognition.ActivityRecognitionSnapshot
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.repo.AutoRecordingSessionStartParams
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.location.ArmingDrivingGate
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.location.LocationRecordingUiState
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.sensor.SensorRecordingUiState
import org.openlgx.roads.permission.ActivityRecognitionPermissionChecker
import org.openlgx.roads.permission.FineLocationPermissionChecker
import org.openlgx.roads.processing.calibration.SessionCalibrationHook
import org.openlgx.roads.processing.ondevice.SessionProcessingScheduler
import org.openlgx.roads.service.CollectorForegroundPresentationRegistry
import org.openlgx.roads.service.CollectorForegroundServiceController
import org.openlgx.roads.service.CollectorServiceStateRegistry
import org.openlgx.roads.service.RecordingEventNotifier
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.upload.SessionUploadScheduling
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PassiveCollectionCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `activity recognition unavailable degrades when passive is effective`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)

            val settingsRepo =
                FakeAppSettingsRepository(effectiveOnboardingPassiveOn())
            val gateway =
                FakeActivityGateway(
                    MutableStateFlow(ActivityRecognitionSnapshot.unavailable("test")),
                )
            val coordinator =
                PassiveCollectionCoordinator(
                    appSettingsRepository = settingsRepo,
                    activityRecognitionGateway = gateway,
                    recordingSessionRepository = FakeRecordingSessionRepository(),
                    foregroundServiceController = FakeForegroundServiceController(),
                    permissionChecker =
                        FakeArPermissionChecker(
                            context = ApplicationProvider.getApplicationContext(),
                            granted = true,
                        ),
                    fineLocationPermissionChecker =
                        AlwaysFineLocation(ApplicationProvider.getApplicationContext()),
                    armingDrivingGate = ArmingDrivingGate { _: Float -> true },
                    locationRecordingController = FakeLocationRecordingController(),
                    sensorRecordingController = FakeSensorRecordingController(),
                    serviceStateRegistry = CollectorServiceStateRegistry(),
                    sessionCalibrationHook = NoOpSessionCalibrationHook,
                    sessionProcessingScheduler = NoOpSessionProcessingScheduler,
                    sessionUploadScheduling =
                        object : SessionUploadScheduling {
                            override suspend fun scheduleAfterSessionCompleted(
                                sessionId: Long,
                                settings: AppSettings,
                            ) = Unit
                        },
                    foregroundPresentationRegistry = CollectorForegroundPresentationRegistry(),
                    recordingEventNotifier =
                        RecordingEventNotifier(ApplicationProvider.getApplicationContext()),
                    applicationScope = scope,
                )

            coordinator.start()
            advanceUntilIdle()

            assertEquals(CollectorLifecycleState.DEGRADED, coordinator.uiState.value.lifecycleState)
        }

    @Test
    fun `arming completes into recording when driving stays likely`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)

            val settingsRepo = FakeAppSettingsRepository(effectiveOnboardingPassiveOn())
            val drivingSnap =
                ActivityRecognitionSnapshot(
                    playServicesAvailable = true,
                    updatesActive = true,
                    lastErrorMessage = null,
                    likelyInVehicle = true,
                    lastDetectedActivityType = DetectedActivity.IN_VEHICLE,
                    lastActivityTransitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    lastUpdateEpochMs = 1L,
                )
            val gateway = FakeActivityGateway(MutableStateFlow(drivingSnap))
            val repo = FakeRecordingSessionRepository()
            val fg = FakeForegroundServiceController()

            val coordinator =
                PassiveCollectionCoordinator(
                    appSettingsRepository = settingsRepo,
                    activityRecognitionGateway = gateway,
                    recordingSessionRepository = repo,
                    foregroundServiceController = fg,
                    permissionChecker =
                        FakeArPermissionChecker(
                            context = ApplicationProvider.getApplicationContext(),
                            granted = true,
                        ),
                    fineLocationPermissionChecker =
                        AlwaysFineLocation(ApplicationProvider.getApplicationContext()),
                    armingDrivingGate = ArmingDrivingGate { _: Float -> true },
                    locationRecordingController = FakeLocationRecordingController(),
                    sensorRecordingController = FakeSensorRecordingController(),
                    serviceStateRegistry = CollectorServiceStateRegistry(),
                    sessionCalibrationHook = NoOpSessionCalibrationHook,
                    sessionProcessingScheduler = NoOpSessionProcessingScheduler,
                    sessionUploadScheduling =
                        object : SessionUploadScheduling {
                            override suspend fun scheduleAfterSessionCompleted(
                                sessionId: Long,
                                settings: AppSettings,
                            ) = Unit
                        },
                    foregroundPresentationRegistry = CollectorForegroundPresentationRegistry(),
                    recordingEventNotifier =
                        RecordingEventNotifier(ApplicationProvider.getApplicationContext()),
                    applicationScope = scope,
                )

            coordinator.start()
            runCurrent()

            assertEquals(CollectorLifecycleState.ARMING, coordinator.uiState.value.lifecycleState)

            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(CollectorLifecycleState.RECORDING, coordinator.uiState.value.lifecycleState)
            assertEquals(1L, coordinator.uiState.value.activeSessionId)
            assertEquals(1, fg.starts)
            assertEquals(1L, fg.lastSessionId)
        }

    @Test
    fun `orphan db open session is completed when coordinator is idle`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)

            val settingsRepo = FakeAppSettingsRepository(effectiveOnboardingPassiveOn())
            val drivingSnap =
                ActivityRecognitionSnapshot(
                    playServicesAvailable = true,
                    updatesActive = true,
                    lastErrorMessage = null,
                    likelyInVehicle = true,
                    lastDetectedActivityType = DetectedActivity.IN_VEHICLE,
                    lastActivityTransitionType = ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    lastUpdateEpochMs = 1L,
                )
            val gateway = FakeActivityGateway(MutableStateFlow(drivingSnap))
            val repo = FakeRecordingSessionRepositoryWithOrphan(orphanId = 42L)
            val scheduled = mutableListOf<Long>()
            val scheduler =
                object : SessionProcessingScheduler {
                    override fun onSessionRecordingCompleted(sessionId: Long) {
                        scheduled.add(sessionId)
                    }

                    override fun requestReprocess(sessionId: Long) = Unit

                    override fun requestBackfillPendingProcessing() = Unit
                }

            val coordinator =
                PassiveCollectionCoordinator(
                    appSettingsRepository = settingsRepo,
                    activityRecognitionGateway = gateway,
                    recordingSessionRepository = repo,
                    foregroundServiceController = FakeForegroundServiceController(),
                    permissionChecker =
                        FakeArPermissionChecker(
                            context = ApplicationProvider.getApplicationContext(),
                            granted = true,
                        ),
                    fineLocationPermissionChecker =
                        AlwaysFineLocation(ApplicationProvider.getApplicationContext()),
                    armingDrivingGate = ArmingDrivingGate { _: Float -> true },
                    locationRecordingController = FakeLocationRecordingController(),
                    sensorRecordingController = FakeSensorRecordingController(),
                    serviceStateRegistry = CollectorServiceStateRegistry(),
                    sessionCalibrationHook = NoOpSessionCalibrationHook,
                    sessionProcessingScheduler = scheduler,
                    sessionUploadScheduling =
                        object : SessionUploadScheduling {
                            override suspend fun scheduleAfterSessionCompleted(
                                sessionId: Long,
                                settings: AppSettings,
                            ) = Unit
                        },
                    foregroundPresentationRegistry = CollectorForegroundPresentationRegistry(),
                    recordingEventNotifier =
                        RecordingEventNotifier(ApplicationProvider.getApplicationContext()),
                    applicationScope = scope,
                )

            coordinator.start()
            advanceUntilIdle()

            assertEquals(42L, repo.lastEndedSessionId)
            assertEquals(SessionState.COMPLETED, repo.lastEndedState)
            assertEquals(listOf(42L), scheduled)
            assertEquals(true, repo.orphanClosedInDb)
        }

    private class FakeRecordingSessionRepositoryWithOrphan(
        private val orphanId: Long,
    ) : RecordingSessionRepository {
        private var id = 0L
        var lastEndedSessionId: Long? = null
        var lastEndedState: SessionState? = null
        var orphanClosedInDb: Boolean = false

        override suspend fun beginAutoRecordingSession(params: AutoRecordingSessionStartParams): Long {
            id += 1
            return id
        }

        override suspend fun updateSensorCaptureSnapshot(
            sessionId: Long,
            json: String?,
        ) = Unit

        override suspend fun endRecordingSession(
            sessionId: Long,
            endedAtEpochMs: Long,
            endState: SessionState,
        ) {
            lastEndedSessionId = sessionId
            lastEndedState = endState
            orphanClosedInDb = true
        }

        override suspend fun countSessions(): Long = 0L

        override suspend fun findOpenSessionId(): Long? = if (orphanClosedInDb) null else orphanId

        override fun observeRecordingSessionCount() = flowOf(0L)
    }

    private class FakeActivityGateway(
        private val backing: MutableStateFlow<ActivityRecognitionSnapshot>,
    ) : ActivityRecognitionGateway {
        override val snapshot: StateFlow<ActivityRecognitionSnapshot> = backing.asStateFlow()

        override fun ingestActivityRecognitionIntent(intent: Intent): Boolean = false

        override suspend fun startUpdates() {
            backing.value = backing.value.copy(updatesActive = true)
        }

        override suspend fun stopUpdates() {
            backing.value = backing.value.copy(updatesActive = false)
        }
    }

    private class FakeRecordingSessionRepository : RecordingSessionRepository {
        private var id = 0L
        override suspend fun beginAutoRecordingSession(params: AutoRecordingSessionStartParams): Long {
            id += 1
            return id
        }

        override suspend fun updateSensorCaptureSnapshot(
            sessionId: Long,
            json: String?,
        ) = Unit

        override suspend fun endRecordingSession(
            sessionId: Long,
            endedAtEpochMs: Long,
            endState: SessionState,
        ) = Unit

        override suspend fun countSessions(): Long = 0L

        override suspend fun findOpenSessionId(): Long? = null

        override fun observeRecordingSessionCount() = flowOf(0L)
    }

    private class FakeForegroundServiceController : CollectorForegroundServiceController {
        var starts: Int = 0
        var stops: Int = 0
        var lastSessionId: Long = -1L

        override fun startCollectorService(sessionId: Long) {
            starts++
            lastSessionId = sessionId
        }

        override fun stopCollectorService() {
            stops++
        }
    }

    private class FakeLocationRecordingController : LocationRecordingController {
        override val uiState = MutableStateFlow(LocationRecordingUiState())

        override fun startRecording(sessionId: Long) = Unit

        override suspend fun stopAndFlush() = Unit
    }

    private object NoOpSessionProcessingScheduler : SessionProcessingScheduler {
        override fun onSessionRecordingCompleted(sessionId: Long) = Unit

        override fun requestReprocess(sessionId: Long) = Unit

        override fun requestBackfillPendingProcessing() = Unit
    }

    private object NoOpSessionCalibrationHook : SessionCalibrationHook {
        override suspend fun onRecordingSessionEnded(
            sessionId: Long,
            endState: SessionState,
            endedAtEpochMs: Long,
            calibrationWorkflowEnabled: Boolean,
        ) = Unit
    }

    private class FakeSensorRecordingController : SensorRecordingController {
        override val uiState = MutableStateFlow(SensorRecordingUiState())

        override fun startRecording(sessionId: Long) = Unit

        override suspend fun stopAndFlush() = Unit
    }

    private class FakeArPermissionChecker(
        context: Context,
        private val granted: Boolean,
    ) : ActivityRecognitionPermissionChecker(context) {
        override fun isGranted(): Boolean = granted
    }

    private class AlwaysFineLocation(
        context: Context,
    ) : FineLocationPermissionChecker(context) {
        override fun isGranted(): Boolean = true
    }
}

private fun effectiveOnboardingPassiveOn(): org.openlgx.roads.data.local.settings.AppSettings =
    org.openlgx.roads.data.local.settings.testAppSettings()

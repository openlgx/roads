package org.openlgx.roads.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.openlgx.roads.FakeAppSettingsRepository
import org.openlgx.roads.FakePassiveCollectionHandle
import org.openlgx.roads.MainDispatcherRule
import org.openlgx.roads.collector.PassiveCollectionUiModel
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.data.repo.FakeRecordingSessionRepositoryFlow
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.location.LocationRecordingUiState
import org.openlgx.roads.sensor.SensorAvailabilitySnapshot
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.sensor.SensorRecordingUiState

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeLocationRecordingHome(
        initial: LocationRecordingUiState = LocationRecordingUiState(),
    ) : LocationRecordingController {
        private val state = MutableStateFlow(initial)
        override val uiState = state.asStateFlow()
        override fun startRecording(sessionId: Long) = Unit
        override suspend fun stopAndFlush() = Unit
    }

    private class FakeSensorRecordingHome(
        initial: SensorRecordingUiState = SensorRecordingUiState(),
    ) : SensorRecordingController {
        private val state = MutableStateFlow(initial)
        override val uiState = state.asStateFlow()
        override fun startRecording(sessionId: Long) = Unit
        override suspend fun stopAndFlush() = Unit
    }

    @Test
    fun `passive effective is false before onboarding even if user toggle is true`() =
        runTest {
            val repo =
                FakeAppSettingsRepository(
                    AppSettings(
                        onboardingCompleted = false,
                        passiveCollectionUserEnabled = true,
                        passiveCollectionEffective = false,
                        uploadWifiOnly = true,
                        uploadAllowCellular = false,
                        uploadOnlyWhileCharging = false,
                        uploadPauseOnLowBatteryEnabled = true,
                        uploadLowBatteryThresholdPercent = 20,
                        retentionDays = 30,
                        maxLocalStorageMb = 0,
                        localCompactionEnabled = false,
                        captureMinSpeedMps = 4.5f,
                        debugModeEnabled = false,
                        calibrationWorkflowEnabled = false,
                    ),
                )

            val collector = FakePassiveCollectionHandle()
            val location = FakeLocationRecordingHome()
            val sensor = FakeSensorRecordingHome()
            val sessions = FakeRecordingSessionRepositoryFlow()
            val vm = HomeViewModel(repo, collector, location, sensor, sessions)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.passiveCollectionEffective)

            repo.overrideSettings(
                AppSettings(
                    onboardingCompleted = true,
                    passiveCollectionUserEnabled = true,
                    passiveCollectionEffective = true,
                    uploadWifiOnly = true,
                    uploadAllowCellular = false,
                    uploadOnlyWhileCharging = false,
                    uploadPauseOnLowBatteryEnabled = true,
                    uploadLowBatteryThresholdPercent = 20,
                    retentionDays = 30,
                    maxLocalStorageMb = 0,
                    localCompactionEnabled = false,
                    captureMinSpeedMps = 4.5f,
                    debugModeEnabled = false,
                    calibrationWorkflowEnabled = false,
                ),
            )
            advanceUntilIdle()

            assertTrue(vm.uiState.value.passiveCollectionEffective)
            assertEquals(true, vm.uiState.value.onboardingCompleted)
        }

    @Test
    fun `collector model is surfaced into HomeUiState`() =
        runTest {
            val repo =
                FakeAppSettingsRepository(
                    AppSettings(
                        onboardingCompleted = true,
                        passiveCollectionUserEnabled = true,
                        passiveCollectionEffective = true,
                        uploadWifiOnly = true,
                        uploadAllowCellular = false,
                        uploadOnlyWhileCharging = false,
                        uploadPauseOnLowBatteryEnabled = true,
                        uploadLowBatteryThresholdPercent = 20,
                        retentionDays = 30,
                        maxLocalStorageMb = 0,
                        localCompactionEnabled = false,
                        captureMinSpeedMps = 4.5f,
                        debugModeEnabled = false,
                        calibrationWorkflowEnabled = false,
                    ),
                )

            val collector =
                FakePassiveCollectionHandle(
                    PassiveCollectionUiModel(
                        lifecycleState = CollectorLifecycleState.ARMING,
                        passiveCollectionRuntimeDesired = true,
                        likelyInVehicle = true,
                        activityRecognitionSupported = true,
                        activityRecognitionUpdatesActive = true,
                        activityRecognitionPermissionGranted = true,
                        recordingActive = false,
                    ),
                )
            val location = FakeLocationRecordingHome()
            val sensor = FakeSensorRecordingHome()
            val sessions = FakeRecordingSessionRepositoryFlow()
            val vm = HomeViewModel(repo, collector, location, sensor, sessions)
            advanceUntilIdle()

            assertEquals(CollectorLifecycleState.ARMING, vm.uiState.value.collector.lifecycleState)
            assertEquals(true, vm.uiState.value.collector.likelyInVehicle)
        }

    @Test
    fun `location recording and session count are surfaced into HomeUiState`() =
        runTest {
            val repo =
                FakeAppSettingsRepository(
                    AppSettings(
                        onboardingCompleted = true,
                        passiveCollectionUserEnabled = true,
                        passiveCollectionEffective = true,
                        uploadWifiOnly = true,
                        uploadAllowCellular = false,
                        uploadOnlyWhileCharging = false,
                        uploadPauseOnLowBatteryEnabled = true,
                        uploadLowBatteryThresholdPercent = 20,
                        retentionDays = 30,
                        maxLocalStorageMb = 0,
                        localCompactionEnabled = false,
                        captureMinSpeedMps = 4.5f,
                        debugModeEnabled = false,
                        calibrationWorkflowEnabled = false,
                    ),
                )
            val collector = FakePassiveCollectionHandle()
            val location =
                FakeLocationRecordingHome(
                    LocationRecordingUiState(
                        activeSessionId = 9L,
                        publishedSampleCount = 42L,
                        lastWallClockEpochMs = 1_700_000_000_000L,
                        lastSpeedMps = 7.5f,
                        recordingLocation = true,
                    ),
                )
            val sensor =
                FakeSensorRecordingHome(
                    SensorRecordingUiState(
                        activeSessionId = 9L,
                        recordingSensors = true,
                        hardwareAvailable =
                            SensorAvailabilitySnapshot(
                                accelerometer = true,
                                gyroscope = false,
                                gravity = true,
                                linearAcceleration = false,
                                rotationVector = false,
                            ),
                        enabledSubscribed =
                            SensorAvailabilitySnapshot(
                                accelerometer = true,
                                gyroscope = false,
                                gravity = true,
                                linearAcceleration = false,
                                rotationVector = false,
                            ),
                        publishedSampleCount = 180L,
                        bufferedSampleCount = 3,
                        lastWallClockEpochMs = 1_700_000_000_001L,
                        estimatedCallbacksPerSecond = 55f,
                        degradedRecording = true,
                        degradedReason = "gyroscope unavailable",
                    ),
                )
            val sessions = FakeRecordingSessionRepositoryFlow(initialSessionCount = 11L)
            val vm = HomeViewModel(repo, collector, location, sensor, sessions)
            advanceUntilIdle()

            assertEquals(11L, vm.uiState.value.recordingSessionCount)
            assertEquals(42L, vm.uiState.value.locationRecording.publishedSampleCount)
            assertEquals(7.5f, vm.uiState.value.locationRecording.lastSpeedMps!!, 0.001f)

            assertEquals(180L, vm.uiState.value.sensorRecording.publishedSampleCount)
            assertEquals(true, vm.uiState.value.sensorRecording.degradedRecording)
            assertEquals("gyroscope unavailable", vm.uiState.value.sensorRecording.degradedReason)
            assertEquals(false, vm.uiState.value.sensorRecording.hardwareAvailable.gyroscope)

            sessions.setSessionCount(12L)
            advanceUntilIdle()
            assertEquals(12L, vm.uiState.value.recordingSessionCount)
        }
}

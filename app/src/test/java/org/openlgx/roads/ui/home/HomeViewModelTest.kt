package org.openlgx.roads.ui.home

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

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
                    ),
                )

            val collector = FakePassiveCollectionHandle()
            val vm = HomeViewModel(repo, collector)
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

            val vm = HomeViewModel(repo, collector)
            advanceUntilIdle()

            assertEquals(CollectorLifecycleState.ARMING, vm.uiState.value.collector.lifecycleState)
            assertEquals(true, vm.uiState.value.collector.likelyInVehicle)
        }
}

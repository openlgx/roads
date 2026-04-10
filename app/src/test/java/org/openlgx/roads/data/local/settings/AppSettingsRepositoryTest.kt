package org.openlgx.roads.data.local.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsRepositoryTest {

    private fun createRepository(): AppSettingsRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = {
                    context.preferencesDataStoreFile("test_settings_${UUID.randomUUID()}")
                },
            )

        return AppSettingsRepositoryImpl(dataStore)
    }

    @Test
    fun uploadWifiOnlyDefaultsToTrue() =
        runBlocking {
            val repo = createRepository()
            assertThat(repo.settings.first().uploadWifiOnly).isTrue()
        }

    @Test
    fun passiveEffectiveFalseUntilOnboarding() =
        runBlocking {
            val repo = createRepository()
            val initial = repo.settings.first()
            assertThat(initial.onboardingCompleted).isFalse()
            assertThat(initial.passiveCollectionEffective).isFalse()

            repo.completeOnboarding()
            val after = repo.settings.first()
            assertThat(after.onboardingCompleted).isTrue()
            assertThat(after.passiveCollectionEffective).isTrue()
        }

    @Test
    fun cellularUploadRespectsToggle() =
        runBlocking {
            val repo = createRepository()
            assertThat(repo.settings.first().uploadAllowCellular).isFalse()
            repo.setUploadAllowCellular(true)
            assertThat(repo.settings.first().uploadAllowCellular).isTrue()
        }

    @Test
    fun calibrationWorkflowDefaultsOffAndCanEnable() =
        runBlocking {
            val repo = createRepository()
            assertThat(repo.settings.first().calibrationWorkflowEnabled).isFalse()
            repo.setCalibrationWorkflowEnabled(true)
            assertThat(repo.settings.first().calibrationWorkflowEnabled).isTrue()
        }

    @Test
    fun pilotBootstrapIsOneShotPerContentVersion() =
        runBlocking {
            val repo = createRepository()
            val first =
                repo.applyPilotBootstrapIfNeverApplied(
                    label = "t1",
                    baseUrl = "https://x.supabase.co",
                    councilSlug = "c",
                    projectSlug = "p",
                    projectId = "proj",
                    deviceId = "dev",
                    uploadApiKey = "",
                )
            assertThat(first).isTrue()
            val s = repo.settings.first()
            assertThat(s.pilotBootstrapApplied).isTrue()
            assertThat(s.uploadCouncilSlug).isEqualTo("c")
            assertThat(s.uploadBaseUrl).endsWith("/functions/v1")
            assertThat(s.uploadWifiOnly).isFalse()
            assertThat(s.uploadAllowCellular).isTrue()
            assertThat(s.uploadRoadPackRequiredForAutoUpload).isFalse()
            assertThat(s.uploadRoadFilterEnabled).isFalse()
            assertThat(s.uploadPauseOnLowBatteryEnabled).isFalse()

            val second =
                repo.applyPilotBootstrapIfNeverApplied(
                    label = "t2",
                    baseUrl = "https://other.supabase.co",
                    councilSlug = "c2",
                    projectSlug = "p2",
                    projectId = "p2",
                    deviceId = "d2",
                    uploadApiKey = "",
                )
            assertThat(second).isFalse()
            assertThat(repo.settings.first().uploadCouncilSlug).isEqualTo("c")
        }

    @Test
    fun pilotBootstrapEnablesUploadWhenKeyPresent() =
        runBlocking {
            val repo = createRepository()
            repo.applyPilotBootstrapIfNeverApplied(
                label = "t",
                baseUrl = "https://x.supabase.co",
                councilSlug = "c",
                projectSlug = "p",
                projectId = "proj",
                deviceId = "dev",
                uploadApiKey = "olgx_du_testkey",
            )
            val s = repo.settings.first()
            assertThat(s.uploadEnabled).isTrue()
            assertThat(s.uploadApiKey).isEqualTo("olgx_du_testkey")
        }
}

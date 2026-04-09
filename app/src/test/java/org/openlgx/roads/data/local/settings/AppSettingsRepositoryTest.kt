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
}

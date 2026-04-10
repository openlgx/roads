package org.openlgx.roads.bootstrap

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.di.ApplicationScope
import timber.log.Timber

/**
 * Seeds pilot DataStore values once on debug/alpha builds. See [AppSettingsRepository.applyPilotBootstrapIfNeverApplied].
 */
@Singleton
class PilotBootstrapCoordinator
@Inject
constructor(
    private val appSettingsRepository: AppSettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    fun scheduleInitialRun() {
        if (!BuildConfig.PILOT_BOOTSTRAP_ENABLED) return
        applicationScope.launch {
            runCatching {
                val uploadKey =
                    BuildConfig.PILOT_UPLOAD_API_KEY.trim().ifEmpty {
                        PilotBootstrapConfig.HARDCODED_DEVICE_UPLOAD_API_KEY.trim()
                    }
                val applied =
                    appSettingsRepository.applyPilotBootstrapIfNeverApplied(
                        label = BuildConfig.PILOT_BOOTSTRAP_LABEL,
                        baseUrl = PilotBootstrapConfig.UPLOAD_BASE_URL,
                        councilSlug = PilotBootstrapConfig.COUNCIL_SLUG,
                        projectSlug = PilotBootstrapConfig.PROJECT_SLUG,
                        projectId = PilotBootstrapConfig.PROJECT_ID,
                        deviceId = PilotBootstrapConfig.DEVICE_ID,
                        uploadApiKey = uploadKey,
                    )
                if (applied) {
                    Timber.i("Pilot bootstrap applied (%s)", BuildConfig.PILOT_BOOTSTRAP_LABEL)
                }
            }.onFailure {
                Timber.w(it, "Pilot bootstrap failed")
            }
        }
    }
}

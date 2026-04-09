package org.openlgx.roads.export

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.openlgx.roads.di.ExportDiagnosticsDataStore

@Singleton
class ExportDiagnosticsRepositoryImpl
@Inject
constructor(
    @ExportDiagnosticsDataStore private val dataStore: DataStore<Preferences>,
) : ExportDiagnosticsRepository {

    private object Keys {
        val dir = stringPreferencesKey("last_export_dir")
        val zip = stringPreferencesKey("last_export_zip")
        val time = longPreferencesKey("last_export_epoch_ms")
        val success = booleanPreferencesKey("last_export_success")
        val error = stringPreferencesKey("last_export_error")
        val sessionId = longPreferencesKey("last_export_session_id")
    }

    override val state: Flow<ExportDiagnosticsState> =
        dataStore.data.map { prefs ->
            ExportDiagnosticsState(
                lastExportDirectoryPath = prefs[Keys.dir],
                lastExportZipPath = prefs[Keys.zip],
                lastExportEpochMs = prefs[Keys.time],
                lastExportSuccess = prefs[Keys.success] ?: false,
                lastExportError = prefs[Keys.error],
                lastExportedSessionId = prefs[Keys.sessionId],
            )
        }

    override suspend fun recordSuccess(
        directoryAbsolutePath: String,
        zipAbsolutePath: String?,
        sessionId: Long,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.dir] = directoryAbsolutePath
            if (zipAbsolutePath != null) {
                prefs[Keys.zip] = zipAbsolutePath
            } else {
                prefs.remove(Keys.zip)
            }
            prefs[Keys.time] = System.currentTimeMillis()
            prefs[Keys.success] = true
            prefs.remove(Keys.error)
            prefs[Keys.sessionId] = sessionId
        }
    }

    override suspend fun recordFailure(message: String) {
        dataStore.edit { prefs ->
            prefs[Keys.time] = System.currentTimeMillis()
            prefs[Keys.success] = false
            prefs[Keys.error] = message
        }
    }
}

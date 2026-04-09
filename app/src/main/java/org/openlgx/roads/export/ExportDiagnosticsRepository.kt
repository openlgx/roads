package org.openlgx.roads.export

import kotlinx.coroutines.flow.Flow

data class ExportDiagnosticsState(
    val lastExportDirectoryPath: String?,
    val lastExportZipPath: String?,
    val lastExportEpochMs: Long?,
    val lastExportSuccess: Boolean,
    val lastExportError: String?,
    val lastExportedSessionId: Long?,
)

interface ExportDiagnosticsRepository {
    val state: Flow<ExportDiagnosticsState>

    suspend fun recordSuccess(
        directoryAbsolutePath: String,
        zipAbsolutePath: String?,
        sessionId: Long,
    )

    suspend fun recordFailure(message: String)
}

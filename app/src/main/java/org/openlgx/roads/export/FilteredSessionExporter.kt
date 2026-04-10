package org.openlgx.roads.export

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.roadpack.RoadPackManager

/**
 * Produces an upload-oriented bundle. **Does not modify Room.**
 * Alpha: when road filter is off, uses the same zip as full export; when on, still uses full export
 * but attaches a road-filter summary JSON for future trimming (see docs/road-filtering.md).
 */
@Singleton
class FilteredSessionExporter
@Inject
constructor(
    private val sessionExporter: SessionExporter,
    private val roadPackManager: RoadPackManager,
) {

    data class PreparedBundle(
        val zipFile: File,
        val roadFilterSummaryJson: String,
        val artifactKind: String,
    )

    suspend fun prepareForUpload(
        sessionId: Long,
        settings: AppSettings,
    ): Result<PreparedBundle> =
        withContext(Dispatchers.IO) {
            val export = sessionExporter.exportSession(sessionId).getOrElse { return@withContext Result.failure(it) }
            val summary =
                if (settings.uploadRoadFilterEnabled &&
                    roadPackManager.hasPackForCouncil(settings.uploadCouncilSlug)
                ) {
                    """{"mode":"alpha","filterEnabled":true,"note":"Traceability only — trimming pipeline extends here."}"""
                } else {
                    """{"mode":"alpha","filterEnabled":false}"""
                }
            val kind =
                if (settings.uploadRoadFilterEnabled) "FILTERED_UPLOAD" else "RAW_UPLOAD"
            Result.success(
                PreparedBundle(
                    zipFile = export.zipFile,
                    roadFilterSummaryJson = summary,
                    artifactKind = kind,
                ),
            )
        }
}

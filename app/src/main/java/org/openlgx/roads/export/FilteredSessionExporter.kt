package org.openlgx.roads.export

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.settings.AppSettings
import org.openlgx.roads.roadpack.RoadPackFilterEngine
import org.openlgx.roads.roadpack.RoadPackManager

/**
 * Produces an upload-oriented bundle. **Does not modify Room.**
 */
@Singleton
class FilteredSessionExporter
@Inject
constructor(
    private val sessionExporter: SessionExporter,
    private val roadPackManager: RoadPackManager,
    private val database: RoadsDatabase,
) {

    data class PreparedBundle(
        val zipFile: File,
        val roadFilterSummaryJson: String,
        val artifactKind: String,
        val payloadManifestJson: String,
        val localRawArtifactUri: String?,
        val localFilteredArtifactUri: String,
        val filterChangedPayload: Boolean,
    )

    sealed class UploadPreparation {
        data class Uploadable(val bundle: PreparedBundle) : UploadPreparation()

        data class Skipped(
            val roadFilterSummaryJson: String,
            val uploadSkipReason: String,
        ) : UploadPreparation()
    }

    suspend fun prepareForUpload(
        sessionId: Long,
        settings: AppSettings,
        onPrepareProgress: (suspend (rowsDone: Long, rowsTotal: Long) -> Unit)? = null,
    ): Result<UploadPreparation> =
        withContext(Dispatchers.IO) {
            val slug = settings.uploadCouncilSlug.trim()
            val filterOn =
                settings.uploadRoadFilterEnabled && roadPackManager.hasPackForCouncil(slug)
            val diag = roadPackManager.getDiagnostics(slug)
            val geoReady =
                filterOn && diag.loadError == null && diag.segmentCount > 0

            if (!filterOn || !geoReady) {
                val export =
                    sessionExporter.exportSession(sessionId, onPrepareProgress).getOrElse {
                        return@withContext Result.failure(it)
                    }
                val manifestText = File(export.directory, "manifest.json").readText()
                val summary =
                    JSONObject().apply {
                        put("mode", if (filterOn) "raw_pack_unusable" else "raw")
                        put("filterEnabled", filterOn)
                        if (filterOn && !geoReady) {
                            put(
                                "packNote",
                                roadPackManager.getDiagnostics(slug).loadError ?: "empty_geometry",
                            )
                        }
                    }.toString()
                return@withContext Result.success(
                    UploadPreparation.Uploadable(
                        PreparedBundle(
                            zipFile = export.zipFile,
                            roadFilterSummaryJson = summary,
                            artifactKind = "RAW_UPLOAD",
                            payloadManifestJson = manifestText,
                            localRawArtifactUri = export.zipFile.absolutePath,
                            localFilteredArtifactUri = export.zipFile.absolutePath,
                            filterChangedPayload = false,
                        ),
                    ),
                )
            }

            val index = roadPackManager.getLocalIndex(slug)
            val locations = database.locationSampleDao().listAllForSessionOrdered(sessionId)
            val (keptIds, stats) =
                RoadPackFilterEngine.computeKeptLocations(
                    locations = locations,
                    index = index,
                    bufferMeters = settings.uploadRoadFilterDistanceMeters,
                    unknownPolicy = settings.uploadRoadFilterUnknownPolicy,
                )
            val summaryJson = RoadPackFilterEngine.statsToSummaryJson(stats, diag.packVersion)

            if (stats.lowValueSkip) {
                return@withContext Result.success(
                    UploadPreparation.Skipped(
                        roadFilterSummaryJson = summaryJson,
                        uploadSkipReason = stats.uploadSkipReason ?: "low_value",
                    ),
                )
            }

            val keptLocs =
                locations.filter { keptIds.contains(it.id) }
                    .sortedBy { it.wallClockUtcEpochMs }

            val filtered =
                sessionExporter.exportSessionFilteredForUpload(
                    sessionId = sessionId,
                    keptLocations = keptLocs,
                    roadPackVersion = diag.packVersion,
                    roadFilterSummaryJson = summaryJson,
                    onExportProgress = onPrepareProgress,
                ).getOrElse { return@withContext Result.failure(it) }

            val manifestText = File(filtered.directory, "manifest.json").readText()
            val filterChanged = keptIds.size < locations.size

            Result.success(
                UploadPreparation.Uploadable(
                    PreparedBundle(
                        zipFile = filtered.zipFile,
                        roadFilterSummaryJson = summaryJson,
                        artifactKind = "FILTERED_UPLOAD",
                        payloadManifestJson = manifestText,
                        localRawArtifactUri = null,
                        localFilteredArtifactUri = filtered.zipFile.absolutePath,
                        filterChangedPayload = filterChanged,
                    ),
                ),
            )
        }
}

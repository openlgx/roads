package org.openlgx.roads.roadpack

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local road-pack install (GeoJSON). Packs live under `filesDir/road_packs/{councilSlug}/{version}/`
 * with `public-roads.geojson` (matching operator / [backend/roadpack-build/build_road_pack.py] keys).
 */
@Singleton
class RoadPackManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private var cachedSlug: String? = null
    private var cachedIndex: LocalRoadIndex = LocalRoadIndex.fromGeoJsonFileOrEmpty(File(""))
    private var cachedDiagnostics: RoadPackDiagnostics =
        RoadPackDiagnostics(0, 0, null, null, null, null, null, null)

    fun baseDir(councilSlug: String): File =
        File(File(context.filesDir, "road_packs"), councilSlug.trim().lowercase())

    /** True if any version directory exists with at least one file. */
    fun hasPackForCouncil(councilSlug: String): Boolean {
        val slug = councilSlug.trim()
        if (slug.isEmpty()) return false
        val root = baseDir(slug)
        if (!root.isDirectory) return false
        return root.listFiles()?.any { it.isDirectory && it.list()?.isNotEmpty() == true } == true
    }

    /** Resolved GeoJSON (sideloaded copy), e.g. `.../1.0.0/public-roads.geojson`. */
    fun activePublicRoadsFile(councilSlug: String): File? =
        resolveVersionDir(councilSlug)?.let { File(it, "public-roads.geojson") }
            .takeIf { it?.isFile == true }

    /**
     * Absolute path hint for operators (app-private storage): `.../road_packs/<slug>/<version>/public-roads.geojson`.
     * [councilSlug] may be empty — placeholder segment is shown.
     */
    fun expectedPublicRoadsPathHint(councilSlug: String): String {
        val slug = councilSlug.trim().lowercase().ifEmpty { "<council_slug>" }
        return File(baseDir(slug), "<version>/public-roads.geojson").absolutePath
    }

    fun getLocalIndex(councilSlug: String): LocalRoadIndex {
        ensureLoaded(councilSlug)
        return cachedIndex
    }

    fun getDiagnostics(councilSlug: String): RoadPackDiagnostics {
        ensureLoaded(councilSlug)
        return cachedDiagnostics
    }

    /** Call when pack files on disk may have changed. */
    fun invalidateCache() {
        synchronized(lock) {
            cachedSlug = null
            cachedIndex = LocalRoadIndex.fromGeoJsonFileOrEmpty(File(""))
            cachedDiagnostics =
                RoadPackDiagnostics(0, 0, null, null, null, null, null, null)
        }
    }

    private fun ensureLoaded(councilSlug: String) {
        val slug = councilSlug.trim().lowercase()
        synchronized(lock) {
            if (slug == cachedSlug) return
            if (slug.isEmpty()) {
                cachedSlug = ""
                cachedIndex = LocalRoadIndex.fromGeoJsonFileOrEmpty(File(""))
                cachedDiagnostics =
                    RoadPackDiagnostics(0, 0, null, null, null, null, "empty_slug", null)
                return
            }
            val geo = activePublicRoadsFile(slug)
            if (geo == null) {
                cachedSlug = slug
                cachedIndex = LocalRoadIndex.fromGeoJsonFileOrEmpty(File(""))
                cachedDiagnostics =
                    RoadPackDiagnostics(
                        0, 0, null, null, null, null, "no_public_roads_geojson", null,
                    )
                return
            }
            val (idx, diag) = LocalRoadIndex.parseGeoJsonRoadPack(geo)
            cachedSlug = slug
            cachedIndex = idx
            cachedDiagnostics = diag
        }
    }

    private fun resolveVersionDir(councilSlug: String): File? {
        val root = baseDir(councilSlug)
        if (!root.isDirectory) return null
        val versions =
            root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return null
        if (versions.isEmpty()) return null
        return versions.last()
    }
}

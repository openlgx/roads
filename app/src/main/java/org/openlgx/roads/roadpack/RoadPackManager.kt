package org.openlgx.roads.roadpack

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local road-pack install (GeoJSON / future FGB). No live OSM on device.
 * Packs live under `filesDir/road_packs/{councilSlug}/{version}/` with optional `pack.json` manifest.
 */
@Singleton
class RoadPackManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
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
}

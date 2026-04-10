package org.openlgx.roads.roadpack

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalRoadIndexTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun parseLineString_builds_segments_and_grid() {
        val f = folder.newFile("roads.geojson")
        f.writeText(
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {},
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [[151.0, -33.0], [151.01, -33.01], [151.02, -33.02]]
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val (idx, diag) = LocalRoadIndex.parseGeoJsonRoadPack(f)
        assertThat(diag.loadError).isNull()
        assertThat(diag.featureCount).isEqualTo(1)
        assertThat(diag.segmentCount).isEqualTo(2)
        assertThat(idx.nearestDistanceMeters(-33.015, 151.005)).isNotNull()
    }

    @Test
    fun parse_missing_file_returns_error_diagnostics() {
        val f = File(folder.root, "nope.geojson")
        val (idx, diag) = LocalRoadIndex.parseGeoJsonRoadPack(f)
        assertThat(diag.loadError).isEqualTo("missing_file")
        assertThat(idx.nearestDistanceMeters(0.0, 0.0)).isNull()
    }
}

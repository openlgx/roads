package org.openlgx.roads.roadpack

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.model.RoadEligibilityDisposition
import org.openlgx.roads.data.local.settings.UploadRoadFilterUnknownPolicy

class RoadPackFilterEngineTest {

    @Test
    fun merges_sensor_margin_intervals() {
        val locs =
            listOf(
                loc(1, 1000L, -33.0, 151.0),
                loc(2, 5000L, -33.001, 151.001),
            )
        val ranges =
            RoadPackFilterEngine.mergeIntervalsForSensors(locs, marginMs = 1000L)
        // Expanded intervals [0,2000] and [4000,6000] do not overlap — two ranges.
        assertThat(ranges).hasSize(2)
        assertThat(ranges[0].first).isEqualTo(0L)
        assertThat(ranges[0].last).isEqualTo(2000L)
        assertThat(ranges[1].first).isEqualTo(4000L)
        assertThat(ranges[1].last).isEqualTo(6000L)
    }

    @Test
    fun low_value_skips_upload_when_few_locations() {
        val index = LocalRoadIndex.fromGeoJsonFileOrEmpty(java.io.File(""))
        val locs =
            (1L..5L).map { i ->
                loc(
                    id = i,
                    t = i * 5000L,
                    lat = -27.0 + i * 0.0001,
                    lon = 153.0,
                )
            }
        val (kept, stats) =
            RoadPackFilterEngine.computeKeptLocations(
                locations = locs,
                index = index,
                bufferMeters = 40f,
                unknownPolicy = UploadRoadFilterUnknownPolicy.UPLOAD,
            )
        assertThat(stats.lowValueSkip).isTrue()
        assertThat(stats.keptLocationCount).isEqualTo(5)
        assertThat(kept).isEmpty()
    }

    private fun loc(
        id: Long,
        t: Long,
        lat: Double,
        lon: Double,
    ) = LocationSampleEntity(
        id = id,
        sessionId = 1L,
        elapsedRealtimeNanos = t * 1_000_000,
        wallClockUtcEpochMs = t,
        latitude = lat,
        longitude = lon,
        roadEligibilityDisposition = RoadEligibilityDisposition.UNKNOWN,
    )
}

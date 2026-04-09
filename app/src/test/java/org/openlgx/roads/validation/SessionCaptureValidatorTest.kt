package org.openlgx.roads.validation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState

class SessionCaptureValidatorTest {

    @Test
    fun `monotonic and no gaps when single sample`() {
        val session =
            fakeSession(
                started = 1000L,
                ended = 2000L,
            )
        val s =
            SessionCaptureValidator.summarize(
                session = session,
                locationSampleCount = 1,
                sensorSampleCount = 1,
                locationWallClocksAsc = listOf(1500L),
                sensorWallClocksAsc = listOf(1500L),
                distinctSensorTypes = listOf(1, 4),
            )
        assertThat(s.locationWallClockMonotonic).isTrue()
        assertThat(s.sensorWallClockMonotonic).isTrue()
        assertThat(s.locationSuspectGaps).isFalse()
        assertThat(s.sensorSuspectGaps).isFalse()
    }

    @Test
    fun `detects non monotonic location`() {
        val session = fakeSession(started = 0L, ended = 10_000L)
        val s =
            SessionCaptureValidator.summarize(
                session = session,
                locationSampleCount = 3,
                sensorSampleCount = 0,
                locationWallClocksAsc = listOf(100L, 90L, 200L),
                sensorWallClocksAsc = emptyList(),
                distinctSensorTypes = emptyList(),
            )
        assertThat(s.locationWallClockMonotonic).isFalse()
        assertThat(s.notes.any { it.contains("monotonic") }).isTrue()
    }

    @Test
    fun `detects location gap`() {
        val session = fakeSession(started = 0L, ended = 120_000L)
        val s =
            SessionCaptureValidator.summarize(
                session = session,
                locationSampleCount = 2,
                sensorSampleCount = 0,
                locationWallClocksAsc = listOf(0L, 120_000L),
                sensorWallClocksAsc = emptyList(),
                distinctSensorTypes = emptyList(),
            )
        assertThat(s.locationSuspectGaps).isTrue()
    }

    @Test
    fun `flags missing accel gyro when sensor rows exist`() {
        val session = fakeSession(started = 0L, ended = 200_000L)
        val s =
            SessionCaptureValidator.summarize(
                session = session,
                locationSampleCount = 10,
                sensorSampleCount = 50,
                locationWallClocksAsc = (1L..10L).toList(),
                sensorWallClocksAsc = (1L..50L).map { it * 10 },
                distinctSensorTypes = listOf(5),
            )
        assertThat(s.hasSensorData).isTrue()
        assertThat(s.hasAccelerometerSamples).isFalse()
        assertThat(s.hasGyroscopeSamples).isFalse()
    }

    private fun fakeSession(
        started: Long,
        ended: Long?,
    ): RecordingSessionEntity =
        RecordingSessionEntity(
            uuid = "test-uuid",
            startedAtEpochMs = started,
            endedAtEpochMs = ended,
            state = SessionState.COMPLETED,
            recordingSource = RecordingSource.AUTO,
            sessionUploadState = SessionUploadState.NOT_QUEUED,
        )
}

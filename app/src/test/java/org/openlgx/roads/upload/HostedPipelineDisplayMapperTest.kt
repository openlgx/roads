package org.openlgx.roads.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionHostedPipelineState
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState

class HostedPipelineDisplayMapperTest {

    @Test
    fun `maps skip to SKIPPED_LOW_VALUE`() {
        val s =
            session(
                hosted = SessionHostedPipelineState.UPLOAD_SKIPPED,
                state = SessionState.COMPLETED,
            )
        val d =
            HostedPipelineDisplayMapper.forSession(
                s,
                null,
                uploadEnabled = true,
                uploadAutoAfterSessionEnabled = true,
            )
        assertThat(d).isEqualTo(HostedPipelineDisplayState.SKIPPED_LOW_VALUE)
    }

    @Test
    fun `completed session not started shows READY_TO_UPLOAD when auto upload on`() {
        val s =
            session(
                hosted = SessionHostedPipelineState.NOT_STARTED,
                state = SessionState.COMPLETED,
            )
        val d =
            HostedPipelineDisplayMapper.forSession(
                s,
                null,
                uploadEnabled = true,
                uploadAutoAfterSessionEnabled = true,
            )
        assertThat(d).isEqualTo(HostedPipelineDisplayState.READY_TO_UPLOAD)
    }

    @Test
    fun `road pack missing failure still FAILED`() {
        val s =
            session(
                hosted = SessionHostedPipelineState.FAILED,
                state = SessionState.COMPLETED,
            )
        val d =
            HostedPipelineDisplayMapper.forSession(
                s,
                null,
                uploadEnabled = true,
                uploadAutoAfterSessionEnabled = true,
            )
        assertThat(d).isEqualTo(HostedPipelineDisplayState.FAILED)
    }

    @Test
    fun `maps COMPLETED internal to PUBLISHED display`() {
        val s =
            session(
                hosted = SessionHostedPipelineState.COMPLETED,
                state = SessionState.COMPLETED,
            )
        val d =
            HostedPipelineDisplayMapper.forSession(
                s,
                null,
                uploadEnabled = true,
                uploadAutoAfterSessionEnabled = true,
            )
        assertThat(d).isEqualTo(HostedPipelineDisplayState.PUBLISHED)
    }

    private fun session(
        hosted: SessionHostedPipelineState,
        state: SessionState,
    ): RecordingSessionEntity =
        RecordingSessionEntity(
            uuid = "00000000-0000-4000-8000-000000000001",
            startedAtEpochMs = 1L,
            endedAtEpochMs = 2L,
            state = state,
            recordingSource = RecordingSource.AUTO,
            sessionUploadState = SessionUploadState.NOT_QUEUED,
            hostedPipelineState = hosted,
        )
}

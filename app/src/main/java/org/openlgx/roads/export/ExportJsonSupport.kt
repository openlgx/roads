package org.openlgx.roads.export

import org.json.JSONObject
import org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity
import org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity

internal fun RecordingSessionEntity.toExportJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("uuid", uuid)
        put("armedAtEpochMs", armedAtEpochMs)
        put("startedAtEpochMs", startedAtEpochMs)
        put("endedAtEpochMs", endedAtEpochMs)
        put("state", state.name)
        put("recordingSource", recordingSource.name)
        put("qualityFlags", qualityFlags)
        put("sessionUploadState", sessionUploadState.name)
        put("autoDetectionSnapshotJson", autoDetectionSnapshotJson)
        put("collectorStateSnapshotJson", collectorStateSnapshotJson)
        put("sensorCaptureSnapshotJson", sensorCaptureSnapshotJson)
        put("roadEligibilitySummaryJson", roadEligibilitySummaryJson)
        put("capturePassiveCollectionEnabled", capturePassiveCollectionEnabled)
        put("capturePolicySnapshotJson", capturePolicySnapshotJson)
        put("uploadPolicyWifiOnly", uploadPolicyWifiOnly)
        put("uploadPolicyAllowCellular", uploadPolicyAllowCellular)
        put("uploadPolicyOnlyWhileCharging", uploadPolicyOnlyWhileCharging)
        put("uploadPolicyPauseOnLowBattery", uploadPolicyPauseOnLowBattery)
        put("uploadPolicyLowBatteryThresholdPercent", uploadPolicyLowBatteryThresholdPercent)
        put("roughnessProxyScore", roughnessProxyScore)
        put("roughnessMethodVersion", roughnessMethodVersion)
        put("roadResponseScore", roadResponseScore)
        put("roadResponseMethodVersion", roadResponseMethodVersion)
        put("processingState", processingState.name)
        put("processingStartedAtEpochMs", processingStartedAtEpochMs)
        put("processingCompletedAtEpochMs", processingCompletedAtEpochMs)
        put("processingLastError", processingLastError)
        put("processingSummaryJson", processingSummaryJson)
    }

internal fun DerivedWindowFeatureEntity.toExportJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("windowStartElapsedNanos", windowStartElapsedNanos)
        put("windowEndElapsedNanos", windowEndElapsedNanos)
        put("windowLengthMetersApprox", windowLengthMetersApprox)
        put("methodVersion", methodVersion)
        put("isExperimental", isExperimental)
        put("roughnessProxyScore", roughnessProxyScore)
        put("roadResponseScore", roadResponseScore)
        put("qualityFlags", qualityFlags)
        put("roadEligibilityDisposition", roadEligibilityDisposition.name)
        put("eligibilityConfidence", eligibilityConfidence)
        put("featureBundleJson", featureBundleJson)
        put("windowStartWallClockUtcEpochMs", windowStartWallClockUtcEpochMs)
        put("windowEndWallClockUtcEpochMs", windowEndWallClockUtcEpochMs)
        put("midLatitude", midLatitude)
        put("midLongitude", midLongitude)
        put("meanSpeedMps", meanSpeedMps)
        put("headingMeanDeg", headingMeanDeg)
        put("predPrimaryLabel", predPrimaryLabel)
        put("scoreCornering", scoreCornering)
        put("scoreVerticalShock", scoreVerticalShock)
        put("scoreStableCruise", scoreStableCruise)
        put("windowIndex", windowIndex)
    }

internal fun AnomalyCandidateEntity.toExportJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("timeElapsedRealtimeNanos", timeElapsedRealtimeNanos)
        put("anomalyType", anomalyType.name)
        put("score", score)
        put("confidence", confidence)
        put("methodVersion", methodVersion)
        put("qualityFlags", qualityFlags)
        put("detailsJson", detailsJson)
        put("wallClockUtcEpochMs", wallClockUtcEpochMs)
        put("latitude", latitude)
        put("longitude", longitude)
        put("speedMps", speedMps)
        put("headingDeg", headingDeg)
        put("severityBucket", severityBucket)
        put("derivedWindowId", derivedWindowId)
    }

internal fun LocationSampleEntity.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        put("wallClockUtcEpochMs", wallClockUtcEpochMs)
        put("latitude", latitude)
        put("longitude", longitude)
        put("speedMps", speedMps)
        put("bearingDegrees", bearingDegrees)
        put("horizontalAccuracyMeters", horizontalAccuracyMeters)
        put("altitudeMeters", altitudeMeters)
        put("roadEligibilityDisposition", roadEligibilityDisposition.name)
        put("eligibilityConfidence", eligibilityConfidence)
        put("retainedRegardlessOfEligibility", retainedRegardlessOfEligibility)
    }

internal fun SensorSampleEntity.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("sessionId", sessionId)
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        put("wallClockUtcEpochMs", wallClockUtcEpochMs)
        put("sensorType", sensorType)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("z", z.toDouble())
        put("w", w?.toDouble())
        put("accuracy", accuracy)
        put("roadEligibilityDisposition", roadEligibilityDisposition.name)
        put("eligibilityConfidence", eligibilityConfidence)
    }

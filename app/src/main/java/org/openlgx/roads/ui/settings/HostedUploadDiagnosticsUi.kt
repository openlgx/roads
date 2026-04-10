package org.openlgx.roads.ui.settings

/**
 * Read-only snapshot for Settings "Hosted upload diagnostics" (no secrets).
 */
data class HostedUploadDiagnosticsUi(
    val uploadEnabled: Boolean,
    val uploadBaseUrlHost: String,
    val uploadApiKeyConfigured: Boolean,
    val uploadCouncilSlug: String,
    val uploadProjectSlug: String,
    val projectIdConfigured: Boolean,
    val deviceIdConfigured: Boolean,
    val roadPackPresent: Boolean,
    val roadPackVersionLabel: String?,
    val roadPackFeatureCount: Int,
    val roadPackLoadNote: String?,
    val uploadQueuePendingOrRetryable: Long,
    val uploadLastAttemptAtEpochMs: Long?,
    val uploadLastSuccessAtEpochMs: Long?,
    val uploadLastError: String?,
    val wifiOnly: Boolean,
    val cellularAllowed: Boolean,
    val chargingRequiredHard: Boolean,
    val chargingPreferredSoft: Boolean,
    val uploadRetryLimit: Int,
    val uploadAutoAfterSession: Boolean,
    val roadFilterEnabled: Boolean,
    val roadPackRequiredForAutoUpload: Boolean,
)

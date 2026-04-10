package org.openlgx.roads.bootstrap

/**
 * Non-secret pilot council defaults for debug/alpha bootstrap (IDs + URLs are not service keys).
 * See README “Debug pilot bootstrap”.
 */
object PilotBootstrapConfig {
    const val COUNCIL_SLUG: String = "yass-valley"
    const val PROJECT_SLUG: String = "alpha-pilot"
    const val PROJECT_ID: String = "218a7453-ce83-429b-b42a-fb188abb0bb0"
    const val DEVICE_ID: String = "63b3ac61-8f1e-40bf-b377-195565e9f886"
    const val UPLOAD_BASE_URL: String =
        "https://zwbjzyysplghkylryaby.supabase.co/functions/v1"
}

package org.openlgx.roads.export

object ExportConstants {
    const val EXPORT_SCHEMA_VERSION: Int = 2
    const val EXPORT_METHOD_VERSION: String = "2c-2"
    const val CALIBRATION_LITERATURE_POINTER: String = "docs/calibration-notes.md"
    const val ROUGHNESS_METHOD_VERSION_PLACEHOLDER: String = "not_implemented"
    const val DISCLAIMER: String =
        "Experimental OLGX Roads export for engineering validation only. " +
            "Not calibrated for safety, regulation, or asset-management decisions."
}

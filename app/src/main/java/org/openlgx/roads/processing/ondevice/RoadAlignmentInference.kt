package org.openlgx.roads.processing.ondevice

/**
 * Lane / side-of-road alignment relative to mapped centerline.
 *
 * Unavailable without on-device road geometry or map-matching; do not fabricate outputs.
 */
interface RoadAlignmentInference {
    fun availabilityNote(): String
}

/** Placeholder until a map-matched implementation exists. */
class NoOpRoadAlignmentInference : RoadAlignmentInference {
    override fun availabilityNote(): String =
        "Road alignment inference unavailable without on-device road geometry / map matching."
}

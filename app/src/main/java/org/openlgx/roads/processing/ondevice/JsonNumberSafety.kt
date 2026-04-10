package org.openlgx.roads.processing.ondevice

import org.json.JSONObject

/** Android [JSONObject] rejects NaN / Infinity for numeric puts — use these helpers. */
internal fun JSONObject.putFiniteOrNull(
    name: String,
    value: Double,
): JSONObject = if (value.isFinite()) put(name, value) else put(name, JSONObject.NULL)

internal fun Double?.toJsonNumber(): Any =
    when {
        this == null -> JSONObject.NULL
        !this.isFinite() -> JSONObject.NULL
        else -> this
    }

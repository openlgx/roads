package org.openlgx.roads.sensor

import android.hardware.SensorEventListener
import android.os.Handler

interface SensorGateway {
    fun queryAvailability(): SensorAvailabilitySnapshot

    /**
     * Registers one [SensorEventListener] for all resolved default sensors.
     * Callbacks are delivered on [callbackHandler]'s Looper (use a [android.os.HandlerThread]'s handler).
     * @return handle to unregister; idempotent.
     */
    fun registerEnabledSensors(
        delayMicros: Int,
        callbackHandler: Handler,
        listener: SensorEventListener,
        enabledTypes: SensorEnabledTypes,
    ): SensorRegistration
}

data class SensorEnabledTypes(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
    val gravity: Boolean,
    val linearAcceleration: Boolean,
    val rotationVector: Boolean,
)

fun interface SensorRegistration {
    fun unregister()
}

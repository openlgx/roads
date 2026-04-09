package org.openlgx.roads.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemSensorGateway
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : SensorGateway {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override fun queryAvailability(): SensorAvailabilitySnapshot =
        SensorAvailabilitySnapshot(
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null,
            linearAcceleration =
                sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null,
            rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null,
        )

    override fun registerEnabledSensors(
        delayMicros: Int,
        callbackHandler: Handler,
        listener: SensorEventListener,
        enabledTypes: SensorEnabledTypes,
    ): SensorRegistration {
        fun registerIf(type: Int, want: Boolean) {
            if (!want) return
            val s = sensorManager.getDefaultSensor(type) ?: return
            sensorManager.registerListener(listener, s, delayMicros, callbackHandler)
        }

        registerIf(Sensor.TYPE_ACCELEROMETER, enabledTypes.accelerometer)
        registerIf(Sensor.TYPE_GYROSCOPE, enabledTypes.gyroscope)
        registerIf(Sensor.TYPE_GRAVITY, enabledTypes.gravity)
        registerIf(Sensor.TYPE_LINEAR_ACCELERATION, enabledTypes.linearAcceleration)
        registerIf(Sensor.TYPE_ROTATION_VECTOR, enabledTypes.rotationVector)

        return SensorRegistration { sensorManager.unregisterListener(listener) }
    }
}

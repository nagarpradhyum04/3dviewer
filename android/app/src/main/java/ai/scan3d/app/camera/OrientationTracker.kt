package ai.scan3d.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.asin
import kotlin.math.atan2

/** Device heading/tilt used as a proxy for the viewpoint around the object (see CoverageTracker). */
data class Orientation(val azimuthDeg: Float, val pitchDeg: Float, val available: Boolean)

class OrientationTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    val state = mutableStateOf(Orientation(0f, 0f, sensor != null))
    private val rot = FloatArray(9)

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        // rot maps device coordinates to world (x = east, y = north, z = up).
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        // The rear camera looks along the device's -Z axis.
        val east = -rot[2]
        val north = -rot[5]
        val up = (-rot[8]).coerceIn(-1f, 1f)
        state.value = Orientation(
            azimuthDeg = Math.toDegrees(atan2(east, north).toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(asin(up).toDouble()).toFloat(),   // < 0: camera points downwards
            available = true,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
fun rememberOrientation(): State<Orientation> {
    val context = LocalContext.current
    val tracker = remember { OrientationTracker(context) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    return tracker.state
}

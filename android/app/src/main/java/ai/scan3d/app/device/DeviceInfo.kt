package ai.scan3d.app.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.StatFs

/** Facts about this phone that matter for scanning. Nothing here is sent anywhere. */
data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val backCameraLevel: String,
    val hasBackCamera: Boolean,
    val hasDepthCamera: Boolean,
    val hasRotationSensor: Boolean,
    val totalRamMb: Long,
    val freeStorageMb: Long,
) {
    /** Checks that make scanning possible or degrade it. */
    fun issues(): List<String> = buildList {
        if (!hasBackCamera) add("No rear camera was found. Scanning requires a rear camera.")
        if (!hasRotationSensor) add("No orientation sensor: the coverage guide is unavailable, capture still works.")
        if (freeStorageMb < 300) add("Low free storage (${freeStorageMb} MB). A scan needs roughly 100–300 MB.")
        if (totalRamMb in 1..2047) add("Low memory device: very large models may fail to display.")
    }

    companion object {
        fun read(context: Context): DeviceInfo {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var level = "none"
            var back = false
            var depth = false
            runCatching {
                for (id in cm.cameraIdList) {
                    val ch = cm.getCameraCharacteristics(id)
                    val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps) depth = true
                    if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK && !back) {
                        back = true
                        level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
                            else -> "Unknown"
                        }
                    }
                }
            }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val stat = StatFs(context.filesDir.path)
            return DeviceInfo(
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                backCameraLevel = level,
                hasBackCamera = back,
                hasDepthCamera = depth,
                hasRotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                    sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null,
                totalRamMb = mem.totalMem / (1024 * 1024),
                freeStorageMb = stat.availableBytes / (1024 * 1024),
            )
        }
    }
}

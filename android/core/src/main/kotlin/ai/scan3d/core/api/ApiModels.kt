package ai.scan3d.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON wire format of the Scan3D reconstruction API (backend/app/main.py). Unknown keys are ignored. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class JobOptions(
    @SerialName("marker_size_mm") val markerSizeMm: Double? = null,
    @SerialName("marker_id") val markerId: Int = 0,
    @SerialName("object_name") val objectName: String? = null,
)

@Serializable
data class CreateJobRequest(
    @SerialName("expected_images") val expectedImages: Int,
    val options: JobOptions,
)

@Serializable
data class CreateJobResponse(
    val id: String,
    val state: String,
    @SerialName("max_images") val maxImages: Int? = null,
    @SerialName("max_image_bytes") val maxImageBytes: Long? = null,
)

@Serializable
data class StageInfo(val key: String, val label: String, val explanation: String)

@Serializable
data class InspectionModule(val key: String, val family: String, val status: String, val requires: String = "")

@Serializable
data class Health(
    val status: String,
    val version: String,
    val cuda: Boolean = false,
    @SerialName("dense_method") val denseMethod: String = "cpu",
    @SerialName("min_images") val minImages: Int = 8,
    @SerialName("max_images") val maxImages: Int = 150,
    @SerialName("auth_required") val authRequired: Boolean = false,
    val stages: List<StageInfo> = emptyList(),
    @SerialName("output_formats") val outputFormats: List<String> = emptyList(),
    @SerialName("inspection_modules") val inspectionModules: List<InspectionModule> = emptyList(),
)

@Serializable
data class Progress(
    val stage: String? = null,
    @SerialName("stage_label") val stageLabel: String? = null,
    @SerialName("stage_explanation") val stageExplanation: String? = null,
    @SerialName("stage_fraction") val stageFraction: Double? = null,
    val indeterminate: Boolean = false,
    @SerialName("overall_fraction") val overallFraction: Double = 0.0,
    val message: String = "",
    @SerialName("eta_seconds") val etaSeconds: Int? = null,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int = 0,
    @SerialName("completed_stages") val completedStages: List<String> = emptyList(),
    @SerialName("skipped_stages") val skippedStages: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class OutputFile(val name: String, val format: String, val description: String = "", val bytes: Long = 0)

@Serializable
data class Calibration(
    val status: String,                       // "calibrated" | "uncalibrated"
    val method: String? = null,               // "aruco_marker" | "manual_reference" | null
    val units: String = "arbitrary",          // "m" | "arbitrary"
    @SerialName("scale_factor") val scaleFactor: Double = 1.0,
    val note: String = "",
) {
    val isCalibrated: Boolean get() = status == "calibrated"
}

@Serializable
data class Dimensions(
    val length: Double,
    val width: Double,
    val height: Double,
    val units: String,                        // "m" | "arbitrary"
    val kind: String,                         // "estimated" | "relative_only"
    val method: String = "",
    val note: String = "",
    val reliable: Boolean = true,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SparseStats(
    @SerialName("images_used") val imagesUsed: Int = 0,
    @SerialName("images_registered") val imagesRegistered: Int = 0,
    @SerialName("sparse_points") val sparsePoints: Int = 0,
    @SerialName("mean_reprojection_error_px") val meanReprojectionErrorPx: Double = 0.0,
)

@Serializable
data class DenseStats(
    @SerialName("dense_points") val densePoints: Int = 0,
    @SerialName("dense_method") val denseMethod: String? = null,
)

@Serializable
data class MeshStats(
    val triangles: Int = 0,
    @SerialName("viewer_triangles") val viewerTriangles: Int = 0,
    val colour: String? = null,
)

@Serializable
data class Coverage(
    @SerialName("largest_azimuth_gap_deg") val largestGapDeg: Double = 0.0,
    @SerialName("elevation_min_deg") val elevationMinDeg: Double = 0.0,
    @SerialName("elevation_max_deg") val elevationMaxDeg: Double = 0.0,
    @SerialName("complete_ring") val completeRing: Boolean = false,
)

@Serializable
data class QualityInfo(
    val sparse: SparseStats = SparseStats(),
    val dense: DenseStats = DenseStats(),
    val mesh: MeshStats = MeshStats(),
    @SerialName("object_points") val objectPoints: Int = 0,
    val segmentation: String = "",
    val coverage: Coverage? = null,
    @SerialName("processing_seconds") val processingSeconds: Double = 0.0,
    val grade: String = "",
    @SerialName("grade_reasons") val gradeReasons: List<String> = emptyList(),
)

@Serializable
data class JobResult(
    val status: String,
    val files: List<OutputFile> = emptyList(),
    val calibration: Calibration? = null,
    val dimensions: Dimensions? = null,
    val quality: QualityInfo? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val remedy: String? = null,
)

@Serializable
data class JobStatus(
    val id: String,
    val state: String,
    @SerialName("images_received") val imagesReceived: Int = 0,
    @SerialName("expected_images") val expectedImages: Int? = null,
    @SerialName("queue_position") val queuePosition: Int? = null,
    val progress: Progress? = null,
    val result: JobResult? = null,
) {
    val isTerminal: Boolean get() = state in setOf("succeeded", "failed", "cancelled")
}

@Serializable
data class CalibrateRequest(
    @SerialName("point_a") val pointA: List<Double>,
    @SerialName("point_b") val pointB: List<Double>,
    @SerialName("distance_m") val distanceM: Double,
)

@Serializable
data class UploadResponse(val name: String, val bytes: Long, val received: Int)

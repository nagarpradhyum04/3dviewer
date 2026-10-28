package ai.scan3d.app.ui.screens

import ai.scan3d.app.camera.LiveQualityAnalyzer
import ai.scan3d.app.camera.PhotoQuality
import ai.scan3d.app.camera.rememberOrientation
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.rememberBitmap
import ai.scan3d.app.ui.components.rememberScan
import ai.scan3d.app.ui.theme.Blue
import ai.scan3d.app.ui.theme.Danger
import ai.scan3d.app.ui.theme.Success
import ai.scan3d.app.ui.theme.Warning
import ai.scan3d.core.capture.CoverageTracker
import ai.scan3d.core.quality.ImageQuality
import ai.scan3d.core.storage.PhotoRecord
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG = "Scan3DCapture"
private const val MIN_FREE_BYTES = 80L * 1024 * 1024
private const val MIN_PHOTOS = 8   // server default (SCAN3D_MIN_IMAGES)

@Composable
fun CaptureScreen(nav: NavController, scanId: String) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        askedOnce = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        val activity = context as? Activity
        val permanentlyDenied = askedOnce && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        PermissionNeeded(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)))
            },
            onBack = { nav.popBackStack() },
        )
        // re-check when returning from system settings
        LaunchedEffect(Unit) {
            while (!granted) {
                delay(1000)
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        return
    }
    CameraCapture(nav, scanId)
}

@Composable
private fun PermissionNeeded(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.NoPhotography, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Camera access needed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scan3D AI takes photos of your object to reconstruct it in 3D. Photos stay on your phone " +
                "until you choose to send them for reconstruction.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (permanentlyDenied) {
            Text("Camera permission was denied. Enable it in the system settings under Permissions › Camera.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) { Text("Open app settings") }
        } else {
            Button(onClick = onRequest) { Text("Allow camera") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun CameraCapture(nav: NavController, scanId: String) {
    val app = app()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scan = rememberScan(scanId)
    val orientation by rememberOrientation()
    val liveHints = app.settings.current.liveQualityHints

    val tracker = remember { CoverageTracker() }
    var trackerVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(scan?.photos) {
        scan?.let { s ->
            tracker.restore(s.photos.mapNotNull { p ->
                if (p.azimuthDeg != null && p.pitchDeg != null) CoverageTracker.Shot(p.azimuthDeg!!, p.pitchDeg!!) else null
            })
            trackerVersion++
        }
    }

    val ringPct = remember(trackerVersion) { (tracker.ringCoverage() * 100).roundToInt() }
    val hint = remember(trackerVersion) { tracker.nextHint() }
    val readiness = remember(trackerVersion) { tracker.readiness(MIN_PHOTOS) }

    var live by remember { mutableStateOf<ImageQuality.Metrics?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var lastIssue by remember { mutableStateOf<String?>(null) }   // name of last photo if it looked bad
    var cameraError by remember { mutableStateOf<String?>(null) }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build())
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .build()
    }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                if (liveHints) analysis.setAnalyzer(analysisExecutor, LiveQualityAnalyzer { m -> live = m })
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, analysis)
                cameraControl = camera.cameraControl
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                cameraError = "The camera could not be started (${e.message}). Close other camera apps and try again."
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            analysis.clearAnalyzer()
        }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    fun takePhoto() {
        val s = scan ?: return
        if (capturing) return
        val free = StatFs(context.filesDir.path).availableBytes
        if (free < MIN_FREE_BYTES) {
            feedback = "Phone storage is full. Free up space to keep scanning." to Danger
            return
        }
        capturing = true
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val name = app.store.nextPhotoName(s.id)
        // the tracker is rebuilt from the saved records whenever the photo list changes
        val file = app.store.photoFile(s.id, name)
        file.parentFile?.mkdirs()
        val az = orientation.azimuthDeg
        val pitch = orientation.pitchDeg
        val hasOrientation = orientation.available
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val metrics = withContext(Dispatchers.Default) { runCatching { PhotoQuality.analyse(file) }.getOrNull() }
                        val issues = metrics?.issues() ?: emptyList()
                        app.store.addPhoto(s.id, PhotoRecord(
                            name, System.currentTimeMillis(),
                            if (hasOrientation) az else null, if (hasOrientation) pitch else null,
                            metrics?.sharpness, metrics?.brightness, issues.map { it.name },
                        ))
                        val step = if (hasOrientation) tracker.stepFrom(az) else null
                        lastIssue = if (ImageQuality.Issue.BLURRY in issues) name else null
                        feedback = when {
                            ImageQuality.Issue.BLURRY in issues -> ImageQuality.Issue.BLURRY.message to Danger
                            issues.isNotEmpty() -> issues.first().message to Warning
                            step != null && step > CoverageTracker.MAX_STEP_DEG ->
                                "You moved ${step.roundToInt()}° since the last photo. Take one in between so photos overlap." to Warning
                            else -> "Photo ${s.photos.size + 1} saved" to Success
                        }
                        capturing = false
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exc)
                    file.delete()
                    feedback = "Capture failed: ${exc.message}" to Danger
                    capturing = false
                }
            },
        )
    }

    LaunchedEffect(feedback) { if (feedback != null) { delay(3500); feedback = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        FramingGuide(Modifier.fillMaxSize().pointerInput(cameraControl) {
            detectTapGestures { pos -> focusAt(cameraControl, previewView, pos.x, pos.y) }   // tap to focus
        })

        // top bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(scan?.name ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("${scan?.photos?.size ?: 0} photos · $ringPct% around", color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium)
                }
                CoverageRing(tracker, orientation.azimuthDeg, orientation.available, trackerVersion, Modifier.size(64.dp))
            }
            Spacer(Modifier.height(8.dp))
            OverlayText(if (orientation.available) hint else
                "Move around the object in small steps, keeping it centred. (No orientation sensor: coverage guide unavailable.)")
            if (liveHints) live?.issues()?.filter { it != ImageQuality.Issue.LOW_TEXTURE }?.firstOrNull()?.let {
                Spacer(Modifier.height(6.dp))
                OverlayText(it.message, Warning)
            }
            cameraError?.let { Spacer(Modifier.height(6.dp)); OverlayText(it, Danger) }
        }

        // bottom controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            feedback?.let { (text, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OverlayText(text, color, Modifier.weight(1f, fill = false))
                    if (lastIssue != null && color == Danger) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            lastIssue?.let { n ->
                                app.store.removePhoto(scanId, n)
                            }
                            lastIssue = null
                            feedback = "Removed. Take the photo again." to Blue
                        }) { Text("Retake") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                LastPhoto(scanId, scan?.photos?.lastOrNull()?.name) { nav.navigate(Routes.review(scanId)) }
                ShutterButton(enabled = !capturing && cameraError == null, busy = capturing, onClick = ::takePhoto)
                OutlinedButton(
                    onClick = { nav.navigate(Routes.review(scanId)) },
                    enabled = (scan?.photos?.size ?: 0) > 0,
                ) { Text(if (readiness.canSubmit) "Finish" else "Review", color = Color.White) }
            }
        }
    }
}

@Composable
private fun OverlayText(text: String, accent: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, accent.copy(alpha = if (accent == Color.White) 0f else 0.9f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun FramingGuide(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width * 0.72f
        val h = w
        val tl = Offset((size.width - w) / 2, (size.height - h) / 2)
        drawRoundRect(Color.White.copy(alpha = 0.85f), tl, GSize(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f),
            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 16f))))
        val c = center
        drawLine(Color.White.copy(alpha = 0.7f), c.copy(x = c.x - 18), c.copy(x = c.x + 18), 2f)
        drawLine(Color.White.copy(alpha = 0.7f), c.copy(y = c.y - 18), c.copy(y = c.y + 18), 2f)
    }
}

/** 12 sectors × 3 elevation bands; filled sectors are covered. The needle is the current heading. */
@Composable
private fun CoverageRing(tracker: CoverageTracker, heading: Float, available: Boolean, version: Int, modifier: Modifier) {
    val covered = remember(version) { tracker.covered() }
    Canvas(modifier) {
        val n = tracker.sectors
        val bands = CoverageTracker.Band.entries
        val maxR = size.minDimension / 2
        bands.forEachIndexed { bi, band ->
            val r = maxR * (0.45f + 0.18f * (bands.size - 1 - bi))
            for (s in 0 until n) {
                val start = s * 360f / n - 90f
                drawArc(
                    color = if (s in covered.getValue(band)) Success else Color.White.copy(alpha = 0.25f),
                    startAngle = start + 2, sweepAngle = 360f / n - 4, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = GSize(2 * r, 2 * r),
                    style = Stroke(width = maxR * 0.13f),
                )
            }
        }
        if (available) {
            val a = Math.toRadians((heading - 90).toDouble())
            drawLine(Color.White, center, Offset(center.x + (maxR * cos(a)).toFloat(), center.y + (maxR * sin(a)).toFloat()), 4f)
        }
    }
}

@Composable
private fun LastPhoto(scanId: String, name: String?, onClick: () -> Unit) {
    val store = app().store
    val bmp = rememberBitmap(name?.let { store.photoFile(scanId, it) }, 160)
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.15f))
        .clickable(enabled = name != null, onClick = onClick), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Blue, strokeWidth = 3.dp)
        else Icon(Icons.Outlined.CameraAlt, "Take photo", tint = Blue)
    }
}

private fun focusAt(control: androidx.camera.core.CameraControl?, previewView: PreviewView, x: Float, y: Float) {
    val point = previewView.meteringPointFactory.createPoint(x, y)
    control?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
}

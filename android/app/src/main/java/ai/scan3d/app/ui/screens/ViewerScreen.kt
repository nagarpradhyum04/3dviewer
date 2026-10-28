package ai.scan3d.app.ui.screens

import ai.scan3d.app.Scan3DApp
import ai.scan3d.app.export.ExportHelper
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.Chip
import ai.scan3d.app.ui.components.InfoRow
import ai.scan3d.app.ui.components.Notice
import ai.scan3d.app.ui.components.NoticeKind
import ai.scan3d.app.ui.components.SectionCard
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.formatBytes
import ai.scan3d.app.ui.components.rememberScan
import ai.scan3d.app.ui.theme.Success
import ai.scan3d.app.ui.theme.Warning
import ai.scan3d.app.ui.theme.isDark
import ai.scan3d.app.viewer.ModelViewer
import ai.scan3d.app.viewer.ViewerController
import ai.scan3d.app.viewer.ViewerEvent
import ai.scan3d.app.viewer.viewerUrlFor
import ai.scan3d.core.api.ApiException
import ai.scan3d.core.api.JobResult
import ai.scan3d.core.api.OutputFile
import ai.scan3d.core.storage.ScanRecord
import ai.scan3d.core.units.LengthUnit
import ai.scan3d.core.units.Units
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Measurement(val a: List<Double>, val b: List<Double>, val distance: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(nav: NavController, scanId: String) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scan = rememberScan(scanId) ?: return
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var units by remember { mutableStateOf(settings.units) }
    val dark = isDark(settings.theme)
    val snackbar = remember { SnackbarHostState() }

    val controller = remember { ViewerController() }
    var loaded by remember { mutableStateOf<ViewerEvent.Loaded?>(null) }
    var loadProgress by remember { mutableStateOf<Float?>(null) }
    var viewerError by remember { mutableStateOf<String?>(null) }
    var wireframe by remember { mutableStateOf(false) }
    var measureMode by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var measurement by remember { mutableStateOf<Measurement?>(null) }
    var askDistance by remember { mutableStateOf<Measurement?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }

    val modelFile = app.store.modelFile(scanId, "model.glb")
    val result = scan.result
    val calibrated = result?.calibration?.isCalibrated == true

    fun loadModel() {
        if (!modelFile.isFile) { viewerError = "The model file is missing. Re-download it from the export menu."; return }
        loaded = null; viewerError = null; measurement = null
        controller.load(viewerUrlFor(Scan3DApp.scansDir(app), modelFile) + "?v=${modelFile.lastModified()}", "glb")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(scan.name) },
                navigationIcon = { IconButton(onClick = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showExport = true }) { Icon(Icons.Outlined.Share, "Export and share") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.fillMaxWidth().weight(1.15f)) {
                ModelViewer(controller, Scan3DApp.scansDir(app), dark, onEvent = { e ->
                    when (e) {
                        ViewerEvent.Ready -> loadModel()
                        is ViewerEvent.Progress -> loadProgress = if (e.total > 0) e.loaded / e.total.toFloat() else null
                        is ViewerEvent.Loaded -> {
                            loaded = e; loadProgress = null
                            if (!app.store.thumbnailFile(scanId).isFile) controller.snapshot()
                        }
                        is ViewerEvent.Error -> {
                            loadProgress = null
                            viewerError = when (e.code) {
                                "webgl" -> "This device cannot display 3D graphics (WebGL unavailable). You can still export the model."
                                "context_lost" -> "The model is too large for this device's graphics memory. Export it to view on a computer."
                                else -> "The model could not be displayed: ${e.message}"
                            }
                        }
                        is ViewerEvent.Measure -> {
                            val m = Measurement(e.a, e.b, e.distance)
                            if (calibrating) { askDistance = m; calibrating = false; measureMode = false; controller.setMeasureMode(false) }
                            else measurement = m
                        }
                        ViewerEvent.PickMissed -> scope.launch { snackbar.showSnackbar("Tap directly on the model surface") }
                        is ViewerEvent.Snapshot -> scope.launch(Dispatchers.IO) {
                            runCatching {
                                app.store.thumbnailFile(scanId).writeBytes(e.jpeg)
                                app.store.update(scanId) { it.copy(thumbnail = "thumb.jpg@" + System.currentTimeMillis()) }
                            }
                        }
                        is ViewerEvent.Picked -> {}
                    }
                }, modifier = Modifier.fillMaxSize())

                loadProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) }
                if (loaded == null && viewerError == null && loadProgress == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                viewerError?.let { Notice(it, NoticeKind.ERROR, Modifier.align(Alignment.Center).padding(16.dp)) }
                if (measureMode) {
                    Notice(if (calibrating) "Tap two points whose real distance you know (e.g. the ends of an edge)."
                        else "Tap two points on the model to measure the distance between them.",
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
                // toolbar
                Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { controller.resetView() }) {
                        Icon(Icons.Outlined.CenterFocusStrong, "Reset view")
                    }
                    FilledTonalIconToggleButton(checked = wireframe, onCheckedChange = { wireframe = it; controller.setWireframe(it) },
                        enabled = loaded?.kind == "mesh") { Icon(Icons.Outlined.GridOn, "Wireframe") }
                    FilledTonalIconToggleButton(checked = measureMode && !calibrating, onCheckedChange = {
                        measureMode = it; calibrating = false; measurement = null; controller.setMeasureMode(it)
                    }, enabled = loaded != null) { Icon(Icons.Outlined.Straighten, "Measure") }
                    FilledTonalIconToggleButton(checked = calibrating, onCheckedChange = {
                        calibrating = it; measureMode = it; measurement = null; controller.setMeasureMode(it)
                    }, enabled = loaded != null && result?.status == "succeeded" && !scan.serverCopyDeleted) {
                        Icon(Icons.Outlined.Tune, "Calibrate scale")
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                busy?.let { Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LengthUnit.entries.forEach { u ->
                        FilterChip(selected = units == u, onClick = { units = u; app.settings.update { it.copy(units = u) } },
                            label = { Text(u.symbol) }, enabled = calibrated)
                    }
                }
                measurement?.let { MeasurementCard(it, calibrated, units) {
                    askDistance = it
                } }
                DimensionsCard(result, units, onCalibrate = {
                    calibrating = true; measureMode = true; measurement = null; controller.setMeasureMode(true)
                })
                QualityCard(scan, loaded)
            }
        }
    }

    askDistance?.let { m ->
        CalibrationDialog(m, units, onDismiss = { askDistance = null }) { metres ->
            askDistance = null
            busy = "Applying scale and re-exporting the model…"
            scope.launch {
                try {
                    app.processing.calibrate(scanId, m.a, m.b, metres)
                    loadModel()
                    snackbar.showSnackbar("Scale calibrated from your reference distance")
                } catch (e: ApiException) {
                    snackbar.showSnackbar(e.message ?: "Calibration failed")
                } finally {
                    busy = null
                }
            }
        }
    }

    if (showExport) {
        ExportSheet(scan, onDismiss = { showExport = false }, onMessage = { scope.launch { snackbar.showSnackbar(it) } })
    }
}

@Composable
private fun MeasurementCard(m: Measurement, calibrated: Boolean, units: LengthUnit, onUseAsReference: (Measurement) -> Unit) {
    SectionCard("Measured distance", icon = Icons.Outlined.Straighten) {
        if (calibrated) {
            Text(Units.format(m.distance, units), style = MaterialTheme.typography.headlineSmall)
            Text("Estimated from the calibrated model. Accuracy depends on scan quality and how precisely the points were tapped.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(String.format("%.4f model units", m.distance), style = MaterialTheme.typography.headlineSmall)
            Text("This model has no scale, so this is not a physical length.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onUseAsReference(m) }) { Text("I know the real distance – use it to calibrate") }
        }
    }
}

@Composable
private fun DimensionsCard(result: JobResult?, units: LengthUnit, onCalibrate: () -> Unit) {
    val d = result?.dimensions
    val cal = result?.calibration
    SectionCard("Dimensions") {
        if (d == null || cal == null) {
            Text("No dimensions are available for this scan.", style = MaterialTheme.typography.bodyMedium); return@SectionCard
        }
        if (cal.isCalibrated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Estimated", Warning)
                Chip(if (cal.method == "aruco_marker") "Marker calibrated" else "User calibrated", Success)
            }
            d.warnings.forEach { Notice(it, NoticeKind.WARNING) }
            DimRow("Length", Units.format(d.length, units))
            DimRow("Width", Units.format(d.width, units))
            DimRow("Height", Units.format(d.height, units))
            Text(d.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(cal.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Notice("Absolute dimensions cannot be determined: this scan has no scale reference (no marker was " +
                "detected). The proportions below are reliable; physical sizes are not.", NoticeKind.WARNING)
            val longest = maxOf(d.length, d.width, d.height)
            DimRow("Length", Units.formatRelative(d.length, longest))
            DimRow("Width", Units.formatRelative(d.width, longest))
            DimRow("Height", Units.formatRelative(d.height, longest))
            OutlinedButton(onClick = onCalibrate) { Text("Calibrate with a known distance") }
        }
    }
}

@Composable
private fun DimRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QualityCard(scan: ScanRecord, loaded: ViewerEvent.Loaded?) {
    val q = scan.result?.quality
    SectionCard("Model quality") {
        if (q != null) {
            InfoRow("Overall", q.grade.replaceFirstChar { it.uppercase() }.ifBlank { "–" })
            InfoRow("Photos placed in 3D", "${q.sparse.imagesRegistered} / ${q.sparse.imagesUsed}")
            InfoRow("Reprojection error", String.format("%.2f px", q.sparse.meanReprojectionErrorPx))
            InfoRow("Dense points", "%,d".format(q.dense.densePoints))
            InfoRow("Mesh triangles", "%,d".format(q.mesh.triangles))
            q.coverage?.let { InfoRow("Largest coverage gap", "${it.largestGapDeg.toInt()}°") }
            InfoRow("Surface colour", if (q.mesh.colour == "per-vertex") "Per-vertex colour (no texture map)" else "None")
            InfoRow("Processing time", formatDuration(q.processingSeconds.toInt()))
            q.gradeReasons.forEach { Notice(it, NoticeKind.WARNING) }
        }
        loaded?.let { InfoRow("Displayed", "%,d triangles".format(it.triangles)) }
        scan.result?.warnings?.distinct()?.forEach { Notice(it, NoticeKind.WARNING) }
    }
}

@Composable
private fun CalibrationDialog(m: Measurement, units: LengthUnit, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(units) }
    val metres = Units.parse(text, unit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Real distance between the points") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Measure the same two points on the real object with a ruler or calliper. The whole model " +
                    "will be scaled so this distance matches.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text, { text = it.take(10) }, label = { Text("Distance") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = text.isNotEmpty() && metres == null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LengthUnit.entries.forEach { u -> FilterChip(unit == u, { unit = u }, label = { Text(u.symbol) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { metres?.let(onConfirm) }, enabled = metres != null && m.distance > 0) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(scan: ScanRecord, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingSave
        pendingSave = null
        if (uri != null && f != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ExportHelper.copyTo(context, f, uri) } }
            onMessage(if (r.isSuccess) "Saved ${f.name}" else "Could not save: ${r.exceptionOrNull()?.message}")
        }
    }
    fun withFile(file: OutputFile, action: (File) -> Unit) {
        working = file.name
        scope.launch {
            try {
                action(app.processing.ensureFile(scan.id, file.name))
            } catch (e: ApiException) {
                onMessage(if (e is ApiException.NotFound) "${file.name} is no longer available on the server." else e.message ?: "Download failed")
            } catch (e: java.io.IOException) {
                onMessage("Could not save the file: ${e.message}")
            } finally {
                working = null
            }
        }
    }
    val base = scan.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifBlank { "scan" }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Export & share", style = MaterialTheme.typography.titleLarge)
            val units = if (scan.result?.calibration?.isCalibrated == true) "Files are in metres." else
                "Files have arbitrary units (no scale reference)."
            Text("Only formats the reconstruction actually produced are listed. $units",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (scan.result?.files ?: emptyList()).forEach { f ->
                val local = f.name in scan.downloaded
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.labelLarge)
                        Text("${f.description} · ${formatBytes(f.bytes)}${if (local) " · on phone" else ""}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (working == f.name) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else {
                        IconButton(onClick = { withFile(f) { ExportHelper.share(context, listOf(it), "${scan.name} – ${f.name}") } },
                            enabled = working == null && (local || !scan.serverCopyDeleted)) { Icon(Icons.Outlined.Share, "Share ${f.name}") }
                        IconButton(onClick = { withFile(f) { pendingSave = it; saver.launch("${base}_${f.name}") } },
                            enabled = working == null && (local || !scan.serverCopyDeleted)) { Icon(Icons.Outlined.Download, "Save ${f.name}") }
                    }
                }
            }
            if (scan.serverCopyDeleted) Notice("The server copy was deleted; only files already on the phone can be exported.", NoticeKind.WARNING)
            OutlinedButton(onClick = {
                ExportHelper.share(context, scan.photos.map { app.store.photoFile(scan.id, it.name) }, "${scan.name} – photos")
            }, modifier = Modifier.fillMaxWidth()) { Text("Share original photos (${scan.photos.size})") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

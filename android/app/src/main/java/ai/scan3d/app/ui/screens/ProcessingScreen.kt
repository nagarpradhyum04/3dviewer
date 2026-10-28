package ai.scan3d.app.ui.screens

import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.Notice
import ai.scan3d.app.ui.components.NoticeKind
import ai.scan3d.app.ui.components.SectionCard
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.rememberScan
import ai.scan3d.app.ui.theme.Success
import ai.scan3d.core.storage.ScanState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/** Mirrors backend/app/pipeline/progress.py STAGES (labels are also sent by the server). */
private val STAGES = listOf(
    "validate" to "Validating photos",
    "features" to "Detecting features",
    "matching" to "Matching features",
    "sfm" to "Estimating camera positions",
    "scale" to "Calibrating scale",
    "dense" to "Densifying geometry",
    "mesh" to "Building surface mesh",
    "export" to "Exporting model files",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(nav: NavController, scanId: String) {
    val app = app()
    val scan = rememberScan(scanId) ?: return
    var confirmCancel by remember { mutableStateOf(false) }
    val p = scan.progress

    LaunchedEffect(scan.state) {
        if (scan.state == ScanState.COMPLETED && scan.viewerFile != null) {
            nav.navigate(Routes.viewer(scanId)) { popUpTo(Routes.HOME) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Reconstruction") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack(Routes.HOME, false) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(scan.name) {
                Text(scan.statusMessage ?: "Preparing", style = MaterialTheme.typography.titleMedium)
                when (scan.state) {
                    ScanState.UPLOADING -> {
                        val frac = if (scan.photos.isEmpty()) 0f else scan.uploaded.size / scan.photos.size.toFloat()
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        Text("${scan.uploaded.size} of ${scan.photos.size} photos uploaded",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ScanState.PROCESSING, ScanState.DOWNLOADING -> {
                        if (p != null) {
                            // Overall fraction is computed by the server from finished stages and measured
                            // sub-progress only; it is never extrapolated.
                            LinearProgressIndicator(progress = { p.overallFraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${(p.overallFraction * 100).toInt()}% · ${formatDuration(p.elapsedSeconds)} elapsed",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                p.etaSeconds?.let {
                                    Text("about ${formatDuration(it)} left in this stage", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    else -> {}
                }
                if (scan.state.isActive) {
                    Text("You can leave this screen; processing continues while the app is open and resumes " +
                        "automatically after a restart.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (scan.state == ScanState.FAILED) {
                Notice(scan.error ?: "Reconstruction failed.", NoticeKind.ERROR, title = "Reconstruction failed")
                scan.remedy?.let { Notice(it, title = "What to do") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.processing.start(scanId) }) { Text("Retry") }
                    OutlinedButton(onClick = { nav.navigate(Routes.review(scanId)) }) { Text("Edit photos") }
                    TextButton(onClick = { nav.navigate(Routes.HELP) }) { Text("Help") }
                }
            }
            if (scan.state == ScanState.CANCELLED) {
                Notice("Processing was cancelled. Your photos are still on the phone.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.processing.start(scanId) }) { Text("Start again") }
                    OutlinedButton(onClick = { nav.navigate(Routes.review(scanId)) }) { Text("Edit photos") }
                }
            }
            if (scan.state == ScanState.COMPLETED && scan.viewerFile == null) {
                Notice("The server finished, but it could not build a surface mesh (only point clouds). " +
                    "You can still export the point cloud from the library.", NoticeKind.WARNING)
            }

            if (scan.state == ScanState.PROCESSING || scan.state == ScanState.DOWNLOADING ||
                (scan.state == ScanState.FAILED && p != null)) {
                SectionCard("Stages") {
                    STAGES.forEach { (key, label) ->
                        val status = when {
                            p == null -> 0
                            key in p.completedStages -> 2
                            key in p.skippedStages -> 3
                            key == p.stage -> 1
                            else -> 0
                        }
                        StageRow(label, status, if (p != null && key == p.stage) p.stageFraction else null)
                    }
                    p?.stageExplanation?.let {
                        Spacer(Modifier.height(4.dp))
                        Notice(it, title = "What is happening")
                    }
                    p?.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val warnings = (p?.warnings ?: emptyList()) + (scan.result?.warnings ?: emptyList())
            if (warnings.isNotEmpty()) {
                SectionCard("Warnings from the server") { warnings.distinct().forEach { Notice(it, NoticeKind.WARNING) } }
            }
            if (scan.state.isActive) {
                OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancel processing") }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel reconstruction?") },
            text = { Text("The server stops working on this scan. Your photos stay on the phone and you can start again later.") },
            confirmButton = { TextButton(onClick = { app.processing.cancel(scanId); confirmCancel = false }) { Text("Cancel processing") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun StageRow(label: String, status: Int, fraction: Double?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            2 -> Icon(Icons.Outlined.CheckCircle, "done", tint = Success, modifier = Modifier.size(20.dp))
            1 -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            3 -> Icon(Icons.Outlined.RemoveCircleOutline, "skipped", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Outlined.RadioButtonUnchecked, "pending", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label + if (status == 3) " (skipped)" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (status == 1 && fraction != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
    }
}

fun formatDuration(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

package ai.scan3d.app.ui.screens

import ai.scan3d.app.export.ExportHelper
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.Notice
import ai.scan3d.app.ui.components.NoticeKind
import ai.scan3d.app.ui.components.SectionCard
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.rememberBitmap
import ai.scan3d.app.ui.components.rememberScan
import ai.scan3d.app.ui.theme.Danger
import ai.scan3d.app.ui.theme.Warning
import ai.scan3d.core.capture.CoverageTracker
import ai.scan3d.core.quality.ImageQuality
import ai.scan3d.core.storage.PhotoRecord
import ai.scan3d.core.storage.ScanState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

private const val MIN_PHOTOS = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(nav: NavController, scanId: String) {
    val app = app()
    val context = LocalContext.current
    val scan = rememberScan(scanId) ?: return
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<PhotoRecord?>(null) }
    var askConsent by remember { mutableStateOf(false) }

    val tracker = remember(scan.photos) {
        CoverageTracker().apply {
            restore(scan.photos.mapNotNull { p -> p.azimuthDeg?.let { a -> p.pitchDeg?.let { CoverageTracker.Shot(a, it) } } })
        }
    }
    val hasOrientation = scan.photos.any { it.azimuthDeg != null }
    val readiness = if (hasOrientation) tracker.readiness(MIN_PHOTOS) else
        ai.scan3d.core.capture.Readiness(scan.photos.size >= MIN_PHOTOS,
            if (scan.photos.size >= MIN_PHOTOS) "${scan.photos.size} photos." else "Take at least $MIN_PHOTOS photos.")
    val blurry = scan.photos.count { ImageQuality.Issue.BLURRY.name in it.issues }
    val dark = scan.photos.count { ImageQuality.Issue.TOO_DARK.name in it.issues }
    val shiny = scan.photos.count { ImageQuality.Issue.HIGHLIGHTS.name in it.issues }
    val plain = scan.photos.count { ImageQuality.Issue.LOW_TEXTURE.name in it.issues }

    fun submit() {
        app.processing.start(scanId)
        nav.navigate(Routes.processing(scanId)) { popUpTo(Routes.HOME) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Review photos") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    ExportHelper.share(context, scan.photos.map { app.store.photoFile(scanId, it.name) }, "${scan.name} – photos")
                }, enabled = scan.photos.isNotEmpty()) { Icon(Icons.Outlined.Share, "Share photos") }
            },
        )
    }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionCard(scan.name) {
                    Text("${scan.photos.size} photos · " + (scan.markerSizeMm?.let { "scale marker ${it.toInt()} mm" } ?: "no scale reference"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Notice(readiness.message, if (!readiness.canSubmit) NoticeKind.ERROR else if (readiness.recommendedMore) NoticeKind.WARNING else NoticeKind.INFO)
                    if (blurry > 0) Notice("$blurry photo(s) look blurry. Tap a photo to inspect and delete or retake it.", NoticeKind.WARNING)
                    if (dark > scan.photos.size / 3 && dark > 0) Notice("Many photos are dark. Improve lighting for better detail.", NoticeKind.WARNING)
                    if (shiny > scan.photos.size / 3 && shiny > 0) Notice("Strong highlights detected. Reflective surfaces may not reconstruct.", NoticeKind.WARNING)
                    if (plain > scan.photos.size / 3 && plain > 0) Notice("Little surface texture detected. Plain, transparent or glossy objects often fail; add a textured background.", NoticeKind.WARNING)
                    if (scan.markerSizeMm != null) Notice("Make sure the marker is fully visible and flat in at least 3 photos.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate(Routes.capture(scanId)) }) {
                            Icon(Icons.Outlined.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text("Add photos")
                        }
                        Button(
                            onClick = { if (settings.uploadConsent) submit() else askConsent = true },
                            enabled = readiness.canSubmit && scan.state != ScanState.UPLOADING && scan.state != ScanState.PROCESSING,
                        ) { Text("Create 3D model") }
                    }
                }
            }
            items(scan.photos, key = { it.name }) { p ->
                PhotoTile(scanId, p) { open = p }
            }
        }
    }

    open?.let { p ->
        PhotoDialog(scanId, p, onDismiss = { open = null }, onDelete = {
            app.store.removePhoto(scanId, p.name); open = null
        }, onRetake = {
            app.store.removePhoto(scanId, p.name); open = null; nav.navigate(Routes.capture(scanId))
        })
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Upload photos for reconstruction?") },
            text = {
                Text("3D reconstruction runs on a server, not on this phone. Your ${scan.photos.size} photos and the scan " +
                    "name will be uploaded to:\n\n${settings.serverUrl}\n\nThe server keeps them only to process this scan " +
                    "(by default jobs are deleted after 72 hours; you can also delete them from the library or enable " +
                    "automatic deletion in Settings). Do not scan anything confidential unless you trust this server.")
            },
            confirmButton = {
                TextButton(onClick = {
                    app.settings.update { it.copy(uploadConsent = true) }
                    askConsent = false
                    submit()
                }) { Text("Agree and upload") }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhotoTile(scanId: String, p: PhotoRecord, onClick: () -> Unit) {
    val bmp = rememberBitmap(app().store.photoFile(scanId, p.name), 256)
    Box(Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick)) {
        if (bmp != null) Image(bmp, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (p.issues.isNotEmpty()) {
            val severe = ImageQuality.Issue.BLURRY.name in p.issues
            Icon(Icons.Outlined.WarningAmber, "Quality warning",
                tint = if (severe) Danger else Warning,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp))
        }
    }
}

@Composable
private fun PhotoDialog(scanId: String, p: PhotoRecord, onDismiss: () -> Unit, onDelete: () -> Unit, onRetake: () -> Unit) {
    val bmp = rememberBitmap(app().store.photoFile(scanId, p.name), 1600)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            if (bmp != null) Image(bmp, p.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(380.dp))
            Spacer(Modifier.height(8.dp))
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            p.sharpness?.let {
                Text("Sharpness ${it.toInt()} · brightness ${p.brightness?.toInt() ?: "–"}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            p.issues.mapNotNull { name -> ImageQuality.Issue.entries.firstOrNull { it.name == name } }.forEach {
                Spacer(Modifier.height(6.dp)); Notice(it.message, NoticeKind.WARNING)
            }
            if (p.issues.isEmpty()) { Spacer(Modifier.height(6.dp)); Notice("No quality problems detected.") }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
                TextButton(onClick = onRetake) { Text("Retake") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

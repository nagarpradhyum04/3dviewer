package ai.scan3d.app.ui.screens

import ai.scan3d.app.export.ExportHelper
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.EmptyState
import ai.scan3d.app.ui.components.ScanThumbnail
import ai.scan3d.app.ui.components.StateChip
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.formatBytes
import ai.scan3d.app.ui.components.formatDate
import ai.scan3d.app.ui.theme.Danger
import ai.scan3d.core.storage.ScanRecord
import ai.scan3d.core.storage.ScanState
import ai.scan3d.core.units.Units
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavController) {
    val app = app()
    val context = LocalContext.current
    val scans by app.store.scans.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ScanRecord?>(null) }
    var deleting by remember { mutableStateOf<ScanRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My 3D Models") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.NEW) }, icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("New scan") })
        },
    ) { pad ->
        if (scans.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.ViewInAr, "No scans yet", "Start a new scan to create your first 3D model.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("${scans.size} scans · ${formatBytes(app.store.totalSize())} on this phone",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(scans, key = { it.id }) { s ->
                ScanCard(s, settings.units,
                    onOpen = { nav.navigate(routeFor(s.state, s.id)) },
                    onRename = { renaming = s },
                    onDelete = { deleting = s },
                    onExport = { nav.navigate(Routes.viewer(s.id)) },
                    onSharePhotos = {
                        ExportHelper.share(context, s.photos.map { app.store.photoFile(s.id, it.name) }, "${s.name} – photos")
                    },
                )
            }
        }
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename scan") },
            text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { app.store.rename(s.id, text); renaming = null }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { s ->
        var alsoServer by remember(s.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${s.name}\"?") },
            text = {
                Column {
                    Text("Photos and model files on this phone will be permanently deleted.")
                    if (s.jobId != null && !s.serverCopyDeleted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(alsoServer, { alsoServer = it })
                            Text("Also delete the copy on the reconstruction server")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.processing.deleteScan(s.id, alsoServer); deleting = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScanCard(
    s: ScanRecord, units: ai.scan3d.core.units.LengthUnit,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit, onSharePhotos: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ScanThumbnail(s)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDate(s.createdAt) + " · ${s.photos.size} photos", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val d = s.result?.dimensions
                if (d != null && s.result?.calibration?.isCalibrated == true) {
                    Text("≈ ${Units.format(d.length, units)} × ${Units.format(d.width, units)} × ${Units.format(d.height, units)}",
                        style = MaterialTheme.typography.bodyMedium)
                } else if (s.state == ScanState.COMPLETED) {
                    Text("No scale (proportions only)", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.viewerFile != null) Text("model.glb", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StateChip(s.state)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Open") }, { menu = false; onOpen() })
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                    if (s.state == ScanState.COMPLETED) DropdownMenuItem({ Text("Export model") }, { menu = false; onExport() })
                    if (s.photos.isNotEmpty()) DropdownMenuItem({ Text("Share photos") }, { menu = false; onSharePhotos() })
                    DropdownMenuItem({ Text("Delete", color = Danger) }, { menu = false; onDelete() })
                }
            }
        }
    }
}

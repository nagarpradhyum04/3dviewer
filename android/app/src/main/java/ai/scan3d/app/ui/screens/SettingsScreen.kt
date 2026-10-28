package ai.scan3d.app.ui.screens

import ai.scan3d.app.BuildConfig
import ai.scan3d.app.data.ThemeMode
import ai.scan3d.app.device.DeviceInfo
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.InfoRow
import ai.scan3d.app.ui.components.Notice
import ai.scan3d.app.ui.components.NoticeKind
import ai.scan3d.app.ui.components.SectionCard
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.formatBytes
import ai.scan3d.app.ui.theme.Danger
import ai.scan3d.core.api.ApiException
import ai.scan3d.core.api.Health
import ai.scan3d.core.api.ReconstructionClient
import ai.scan3d.core.storage.ScanState
import ai.scan3d.core.units.LengthUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val scans by app.store.scans.collectAsStateWithLifecycle()
    val device = remember { DeviceInfo.read(context) }
    var url by remember { mutableStateOf(s.serverUrl) }
    var key by remember { mutableStateOf(s.apiKey) }
    var test by remember { mutableStateOf<Result<Health>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var storageVersion by remember { mutableStateOf(0) }
    val used = remember(scans, storageVersion) { app.store.totalSize() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            SectionCard("Measurement units", icon = Icons.Outlined.Straighten) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LengthUnit.entries.forEach { u ->
                        FilterChip(s.units == u, { app.settings.update { it.copy(units = u) } },
                            label = { Text(when (u) { LengthUnit.MM -> "Millimetres"; LengthUnit.CM -> "Centimetres"; LengthUnit.M -> "Metres" }) })
                    }
                }
            }

            SectionCard("Theme", icon = Icons.Outlined.Palette) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(s.theme == m, { app.settings.update { it.copy(theme = m) } },
                            label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }

            SectionCard("Reconstruction server", icon = Icons.Outlined.Cloud) {
                Text("3D reconstruction runs on a Scan3D backend (see backend/README in the project). " +
                    "On the Android emulator, http://10.0.2.2:8000 reaches a server on your computer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Server URL") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it.trim() }, label = { Text("API key (if the server requires one)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        app.settings.update { it.copy(serverUrl = url, apiKey = key) }
                        testing = true; test = null
                        scope.launch {
                            test = runCatching { ReconstructionClient(url, key).health() }
                            testing = false
                        }
                    }, enabled = !testing) { Text(if (testing) "Testing…" else "Save & test connection") }
                }
                if (url.startsWith("http://") && !BuildConfig.DEBUG) {
                    Notice("Release builds only allow plain HTTP for local addresses. Use https:// for remote servers.", NoticeKind.WARNING)
                }
                test?.let { r ->
                    r.onSuccess { h ->
                        Notice("Connected: server ${h.version}, dense reconstruction on ${if (h.cuda) "GPU (CUDA)" else "CPU"}, " +
                            "${h.minImages}–${h.maxImages} photos per scan.")
                        val planned = h.inspectionModules.filter { it.status != "available" }
                        if (planned.isNotEmpty()) Text("Inspection modules (planned, not available): " + planned.joinToString { it.key },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    r.onFailure { e -> Notice((e as? ApiException)?.message ?: e.message ?: "Connection failed", NoticeKind.ERROR) }
                }
            }

            SectionCard("Privacy", icon = Icons.Outlined.PrivacyTip) {
                Text("Photos are stored only on this phone until you start a reconstruction. Then the photos and the scan " +
                    "name are uploaded to the server above. No account, analytics or location data is used. " +
                    "EXIF metadata written by the camera (e.g. focal length, time) is kept because the reconstruction needs it.",
                    style = MaterialTheme.typography.bodyMedium)
                SwitchRow("I agree to upload photos for reconstruction", s.uploadConsent) { v -> app.settings.update { it.copy(uploadConsent = v) } }
                SwitchRow("Delete server copy after the model is downloaded", s.deleteServerCopy) { v -> app.settings.update { it.copy(deleteServerCopy = v) } }
                Text("If enabled, other export formats (OBJ, STL, PLY) can no longer be downloaded later.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Live photo quality hints while capturing", s.liveQualityHints) { v -> app.settings.update { it.copy(liveQualityHints = v) } }
            }

            SectionCard("Storage", icon = Icons.Outlined.Storage) {
                InfoRow("Used by scans", formatBytes(used))
                InfoRow("Free on phone", "${device.freeStorageMb} MB")
                OutlinedButton(onClick = {
                    scans.filter { it.state == ScanState.COMPLETED && it.viewerFile != null }.forEach { sc ->
                        sc.photos.forEach { p -> app.store.photoFile(sc.id, p.name).delete() }
                        app.store.update(sc.id) { it.copy(photos = emptyList()) }
                    }
                    storageVersion++
                }) { Text("Delete photos of completed scans (keep models)") }
                OutlinedButton(onClick = { confirmWipe = true }) { Text("Delete all scans", color = Danger) }
            }

            SectionCard("This device", icon = Icons.Outlined.PhoneAndroid) {
                InfoRow("Model", device.model)
                InfoRow("Android", "${device.androidVersion} (API ${device.sdk})")
                InfoRow("Rear camera", if (device.hasBackCamera) "Yes, hardware level ${device.backCameraLevel}" else "Not found")
                InfoRow("Orientation sensor", if (device.hasRotationSensor) "Yes" else "No")
                InfoRow("Depth camera", if (device.hasDepthCamera) "Reported (not used)" else "No")
                InfoRow("Memory", "${device.totalRamMb} MB")
                device.issues().forEach { Notice(it, NoticeKind.WARNING) }
                Text("Supported: Android 8.0+ phones with a rear camera. Reconstruction quality depends far more on " +
                    "the object and photos than on the phone. Not every object or phone is supported.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("Help", icon = Icons.AutoMirrored.Outlined.HelpOutline) {
                TextButton(onClick = { nav.navigate(Routes.HELP) }) { Text("Help & troubleshooting") }
                Text("Scan3D AI ${BuildConfig.VERSION_NAME}. Uses three.js (MIT), CameraX, OkHttp (Apache-2.0); the server " +
                    "uses COLMAP (BSD), OpenCV (Apache-2.0), Open3D and trimesh (MIT).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all scans?") },
            text = { Text("All photos and models on this phone will be permanently deleted. Server copies are deleted too where possible.") },
            confirmButton = { TextButton(onClick = {
                scans.forEach { app.processing.deleteScan(it.id, alsoOnServer = true) }
                confirmWipe = false
            }) { Text("Delete all", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

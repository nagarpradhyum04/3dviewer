package ai.scan3d.app.ui.screens

import ai.scan3d.app.device.DeviceInfo
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.AppLogo
import ai.scan3d.app.ui.components.Notice
import ai.scan3d.app.ui.components.NoticeKind
import ai.scan3d.app.ui.components.ScanThumbnail
import ai.scan3d.app.ui.components.SectionCard
import ai.scan3d.app.ui.components.StateChip
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.theme.Danger
import ai.scan3d.app.ui.theme.Success
import ai.scan3d.app.ui.theme.Warning
import ai.scan3d.core.storage.ScanState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val app = app()
    val scans by app.store.scans.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val device = remember { DeviceInfo.read(context) }
    val models = scans.count { it.state == ScanState.COMPLETED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLogo(size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Scan3D AI")
                    }
                },
                actions = { IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Settings") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard {
                    Text("Turn real objects into 3D models", style = MaterialTheme.typography.headlineSmall)
                    Text("Walk around an object, take 25–60 overlapping photos and get an interactive, measurable 3D model.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { nav.navigate(Routes.NEW) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Outlined.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start New Scan")
                    }
                }
            }
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.LIBRARY) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ViewInAr, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("My 3D Models", style = MaterialTheme.typography.titleMedium)
                            Text("$models model${if (models == 1) "" else "s"} · ${scans.size} scan${if (scans.size == 1) "" else "s"} in total",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { nav.navigate(Routes.LIBRARY) }) { Text("Open") }
                    }
                }
            }
            if (scans.isNotEmpty()) {
                item {
                    Text("Recent scans", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(scans.take(8), key = { it.id }) { s ->
                            Column(Modifier.width(132.dp).clickable { nav.navigate(routeFor(s.state, s.id)) }) {
                                ScanThumbnail(s, size = 132.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                StateChip(s.state)
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("Scanning methods", icon = Icons.Outlined.Layers) {
                    Method(Icons.Outlined.CheckCircle, Success, "Photogrammetry (server)",
                        "Photos are reconstructed on the Scan3D server with COLMAP Structure-from-Motion and multi-view stereo.")
                    Method(Icons.Outlined.CheckCircle, Success, "Marker-based scale",
                        "Place the printed Scan3D marker next to the object to get real-world dimensions.")
                    Method(Icons.Outlined.Schedule, Warning, "On-device reconstruction",
                        "Not available: full photogrammetry needs more memory and compute than phones reliably offer.")
                    Method(Icons.Outlined.Cancel, Danger, "LiDAR / depth sensor scanning",
                        if (device.hasDepthCamera) "Your phone reports a depth camera, but depth capture is not implemented yet."
                        else "Not supported on this phone and not implemented.")
                }
            }
            item {
                SectionCard("Scanning tips", icon = Icons.Outlined.Lightbulb) {
                    listOf(
                        "Use even, diffuse light. Avoid harsh shadows and flash.",
                        "Keep the object still; move yourself around it in small steps (~15–30°).",
                        "Each photo should overlap the previous one by about two thirds.",
                        "Do a second, higher ring looking down to capture the top.",
                        "Textured, matte objects work best. Shiny, transparent or plain surfaces often fail.",
                        "Put the object on a textured surface (newspaper, patterned cloth) – it helps camera tracking.",
                    ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                SectionCard("This device", icon = Icons.Outlined.PhoneAndroid) {
                    Text("${device.model} · Android ${device.androidVersion} · camera: ${device.backCameraLevel}",
                        style = MaterialTheme.typography.bodyMedium)
                    val issues = device.issues()
                    if (issues.isEmpty()) Notice("This phone meets the requirements for photo capture.")
                    else issues.forEach { Notice(it, NoticeKind.WARNING) }
                    TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Device details & settings") }
                }
            }
        }
    }
}

@Composable
private fun Method(icon: ImageVector, tint: Color, title: String, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun routeFor(state: ScanState, id: String): String = when (state) {
    ScanState.CAPTURING -> Routes.review(id)
    ScanState.COMPLETED -> Routes.viewer(id)
    else -> Routes.processing(id)
}

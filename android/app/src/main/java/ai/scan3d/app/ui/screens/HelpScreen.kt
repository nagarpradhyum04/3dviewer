package ai.scan3d.app.ui.screens

import ai.scan3d.app.ui.components.SectionCard
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private val TOPICS = listOf(
    "Camera permission denied" to "Scan3D AI needs the camera to take photos. Open Android Settings › Apps › Scan3D AI › " +
        "Permissions › Camera and choose Allow. Photos are only uploaded when you start a reconstruction.",
    "\"At least N photos are required\"" to "Reconstruction needs many overlapping views. Take 25–60 photos: one ring " +
        "around the object at eye level and one from higher up, stepping about 15–30° between photos.",
    "Blurry photos" to "Hold the phone still while the shutter fires, tap the object to focus and add light. Blurry " +
        "photos are flagged after capture; delete or retake them in Review. Very blurry photos are excluded by the server.",
    "Poor lighting" to "Use bright, even, indirect light (cloudy daylight or several lamps). Avoid flash, hard shadows " +
        "and backlight. Dark photos increase noise and reduce detail.",
    "\"Could not determine camera positions\"" to "The server could not find enough common features between photos. " +
        "Increase overlap, keep the object still, put it on a textured surface (newspaper, patterned cloth) and avoid " +
        "plain backgrounds.",
    "Reflective, transparent or plain objects" to "Photogrammetry relies on visible surface texture that looks the same " +
        "from different angles. Mirrors, glossy metal, glass, transparent plastic and uniformly coloured surfaces usually " +
        "fail or produce holes. A removable matte spray (scanning spray) or temporary texture (masking tape, powder) helps.",
    "Holes or missing parts" to "Areas that are not visible in at least 3 photos cannot be reconstructed: undersides, deep " +
        "cavities, thin parts and occluded regions. Add photos of those areas, or scan the object a second time turned over.",
    "Dimensions show \"no scale\"" to "A photo-only reconstruction has no absolute size. Print the Scan3D marker (New scan › " +
        "Get printable marker) at 100 %, measure its black square, enter that value and keep the marker flat next to the " +
        "object in many photos. Or, in the viewer, tap Calibrate and enter a known distance between two points.",
    "How accurate are the dimensions?" to "They are estimates. In synthetic tests with a known object the error was below " +
        "1 %, but real accuracy depends on photo quality, marker printing accuracy, object surface and how much of the object " +
        "was captured. Verify critical measurements with a calliper; do not use the app for certified measurement.",
    "Network interruption" to "Your photos are always kept on the phone. Uploads resume where they stopped and a running " +
        "server job continues; reopen the scan and tap Retry if it shows as failed.",
    "Server errors" to "Check the server address and API key in Settings › Reconstruction server and tap \"Save & test " +
        "connection\". If the server reports an internal error, try again; persistent failures should be reported to " +
        "whoever runs the server, with the scan name and time.",
    "Unsupported file format" to "The viewer displays GLB models produced by the server. OBJ, PLY and STL files are for " +
        "export to other apps (Blender, MeshLab, CAD, slicers).",
    "Insufficient storage" to "Each scan uses roughly 100–300 MB (photos plus model). Delete old scans in the library or " +
        "delete photos of completed scans in Settings › Storage.",
    "Model does not display" to "Very large models can exceed the graphics memory of older phones. Export the model and " +
        "open it on a computer. The viewer requires WebGL (Android System WebView kept up to date via Play Store).",
    "Unsupported devices" to "Android 8.0 or newer with a rear camera is required. Depth sensors (ToF/LiDAR) are not used. " +
        "Reconstruction itself does not run on the phone, so phone speed mainly affects capture and viewing.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(nav: NavController) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Help & troubleshooting") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TOPICS) { (q, a) ->
                var open by remember { mutableStateOf(false) }
                SectionCard(modifier = Modifier.clickable { open = !open }) {
                    Text(q, style = MaterialTheme.typography.titleMedium)
                    AnimatedVisibility(open) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

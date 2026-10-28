package ai.scan3d.app.ui.screens

import ai.scan3d.app.export.ExportHelper
import ai.scan3d.app.ui.Routes
import ai.scan3d.app.ui.components.Notice
import ai.scan3d.app.ui.components.NoticeKind
import ai.scan3d.app.ui.components.SectionCard
import ai.scan3d.app.ui.components.app
import ai.scan3d.app.ui.components.rememberBitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date

const val MARKER_ASSET = "marker/scan3d_marker_50mm.png"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewScanScreen(nav: NavController) {
    val app = app()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("Scan " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date())) }
    var useMarker by rememberSaveable { mutableStateOf(true) }
    var markerText by rememberSaveable { mutableStateOf("50") }
    val marker = markerText.replace(',', '.').toDoubleOrNull()
    val markerValid = !useMarker || (marker != null && marker in 10.0..500.0)
    val markerFile = remember { runCatching { ExportHelper.assetToCache(context, MARKER_ASSET) }.getOrNull() }
    val markerBmp = rememberBitmap(markerFile, 256)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("New scan") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard("What are you scanning?") {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            SectionCard("Real-world size", icon = Icons.Outlined.Straighten) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use the printed calibration marker", style = MaterialTheme.typography.labelLarge)
                        Text("Needed for dimensions in mm/cm/m.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(useMarker, { useMarker = it })
                }
                if (useMarker) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (markerBmp != null) {
                            Image(markerBmp, "Calibration marker", Modifier.size(84.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Print the marker at 100 % scale, measure the black square with a ruler and enter " +
                                "the measured size. Lay it flat next to the object so it is visible in many photos.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(markerText, { markerText = it.take(6) },
                        label = { Text("Measured black square size (mm)") }, singleLine = true,
                        isError = !markerValid,
                        supportingText = { if (!markerValid) Text("Enter a size between 10 and 500 mm") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { markerFile?.let { ExportHelper.share(context, listOf(it), "Scan3D marker (print at 100%)") } },
                        enabled = markerFile != null) { Text("Get printable marker") }
                } else {
                    Notice("Without a scale reference the model has correct proportions but unknown size. " +
                        "You can still calibrate later by entering a known distance between two points on the model.",
                        NoticeKind.WARNING)
                }
            }
            SectionCard("Before you start") {
                listOf(
                    "Place the object on a flat, textured surface in a well-lit spot.",
                    "You will circle the object at least once at eye level and once from higher up.",
                    "Keep the whole object inside the frame guide in every photo.",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val scan = app.store.create(name, if (useMarker) marker else null)
                    nav.navigate(Routes.capture(scan.id)) { popUpTo(Routes.HOME) }
                },
                enabled = markerValid && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Open camera") }
        }
    }
}

package ai.scan3d.app.ui.components

import ai.scan3d.app.Scan3DApp
import ai.scan3d.app.ui.theme.Danger
import ai.scan3d.app.ui.theme.Success
import ai.scan3d.app.ui.theme.Warning
import ai.scan3d.core.storage.ScanRecord
import ai.scan3d.core.storage.ScanState
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun app(): Scan3DApp = LocalContext.current.applicationContext as Scan3DApp

/** Live view of one scan record (null once deleted). */
@Composable
fun rememberScan(id: String): ScanRecord? {
    val scans by app().store.scans.collectAsStateWithLifecycle()
    return scans.firstOrNull { it.id == id }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

enum class NoticeKind { INFO, WARNING, ERROR }

@Composable
fun Notice(text: String, kind: NoticeKind = NoticeKind.INFO, modifier: Modifier = Modifier, title: String? = null) {
    val (color, icon) = when (kind) {
        NoticeKind.INFO -> MaterialTheme.colorScheme.primary to Icons.Outlined.Info
        NoticeKind.WARNING -> Warning to Icons.Outlined.WarningAmber
        NoticeKind.ERROR -> Danger to Icons.Outlined.ErrorOutline
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.10f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = color)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
fun StateChip(state: ScanState) {
    val (label, color) = when (state) {
        ScanState.CAPTURING -> "Capturing" to MaterialTheme.colorScheme.secondary
        ScanState.UPLOADING -> "Uploading" to MaterialTheme.colorScheme.primary
        ScanState.PROCESSING -> "Processing" to MaterialTheme.colorScheme.primary
        ScanState.DOWNLOADING -> "Downloading" to MaterialTheme.colorScheme.primary
        ScanState.COMPLETED -> "3D model" to Success
        ScanState.FAILED -> "Failed" to Danger
        ScanState.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.secondary
    }
    Chip(label, color)
}

/** Decodes a downsampled bitmap off the main thread. */
@Composable
fun rememberBitmap(file: File?, maxSide: Int = 512, key: Any? = null): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, file?.path, key, file?.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (file == null || !file.isFile) return@withContext null
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@Composable
fun ScanThumbnail(scan: ScanRecord, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val store = app().store
    val file = store.thumbnailFile(scan.id).takeIf { it.isFile }
        ?: scan.photos.firstOrNull()?.let { store.photoFile(scan.id, it.name) }
    val bmp = rememberBitmap(file, key = scan.thumbnail)
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.ViewInAr, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format("%.1f MB", b / (1024.0 * 1024))
    b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The cube-with-scan-brackets logo, drawn with Canvas so it scales crisply. */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val blue = ai.scan3d.app.ui.theme.Blue
    val light = Color(0xFF4F8BFF)
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 108f
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)
        fun poly(color: Color, vararg pts: androidx.compose.ui.geometry.Offset) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
            }
            drawPath(path, color)
        }
        drawRoundRect(ai.scan3d.app.ui.theme.Navy, cornerRadius = androidx.compose.ui.geometry.CornerRadius(24 * s))
        poly(light, p(54f, 33f), p(72f, 43f), p(54f, 53f), p(36f, 43f))
        poly(blue, p(36f, 43f), p(54f, 53f), p(54f, 75f), p(36f, 65f))
        poly(Color.White, p(54f, 53f), p(72f, 43f), p(72f, 65f), p(54f, 75f))
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 3 * s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        listOf(
            listOf(p(28f, 36f), p(28f, 28f), p(36f, 28f)), listOf(p(80f, 36f), p(80f, 28f), p(72f, 28f)),
            listOf(p(28f, 72f), p(28f, 80f), p(36f, 80f)), listOf(p(80f, 72f), p(80f, 80f), p(72f, 80f)),
        ).forEach { (a, b, c) ->
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y) },
                Color.White, style = stroke)
        }
    }
}

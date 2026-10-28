package ai.scan3d.app.viewer

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Events emitted by assets/viewer/viewer.js. Distances are in model units. */
sealed interface ViewerEvent {
    data object Ready : ViewerEvent
    data class Progress(val loaded: Long, val total: Long) : ViewerEvent
    data class Loaded(val kind: String, val vertices: Int, val triangles: Int, val points: Int,
                      val hasColor: Boolean, val hasTexture: Boolean) : ViewerEvent
    data class Error(val code: String, val message: String) : ViewerEvent
    data class Picked(val count: Int) : ViewerEvent
    data object PickMissed : ViewerEvent
    data class Measure(val a: List<Double>, val b: List<Double>, val distance: Double) : ViewerEvent
    data class Snapshot(val jpeg: ByteArray) : ViewerEvent
}

/** Commands sent to the page. Safe to call before the page is ready (they are queued). */
class ViewerController {
    internal var webView: WebView? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    internal fun onReady() {
        ready = true
        pending.forEach { eval(it) }
        pending.clear()
    }

    private fun run(js: String) {
        if (ready) eval(js) else pending.add(js)
    }

    private fun eval(js: String) {
        webView?.evaluateJavascript(js, null)
    }

    fun load(url: String, format: String? = null) =
        run("viewer.load(${JsonPrimitive(url)}, ${if (format != null) "{format:${JsonPrimitive(format)}}" else "{}"})")
    fun setWireframe(on: Boolean) = run("viewer.setWireframe($on)")
    fun resetView() = run("viewer.resetView()")
    fun setMeasureMode(on: Boolean) = run("viewer.setMeasureMode($on)")
    fun clearMeasurements() = run("viewer.clearMeasurements()")
    fun setTheme(dark: Boolean) = run("viewer.setTheme($dark)")
    fun snapshot() = run("viewer.snapshot(512)")
}

/** Receives JSON messages from the page on a binder thread and forwards them on the main thread. */
class ViewerBridge(private val onEvent: (ViewerEvent) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        val event = runCatching { parse(json) }.getOrNull() ?: return
        main.post { onEvent(event) }
    }

    private fun parse(json: String): ViewerEvent? {
        val o = Json.parseToJsonElement(json) as JsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
        fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
        fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
        fun vec(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.double } ?: emptyList()
        return when (s("type")) {
            "ready" -> ViewerEvent.Ready
            "progress" -> ViewerEvent.Progress(o["loaded"]!!.jsonPrimitive.double.toLong(), o["total"]!!.jsonPrimitive.double.toLong())
            "loaded" -> ViewerEvent.Loaded(s("kind"), i("vertices"), i("triangles"), i("points"), b("hasColor"), b("hasTexture"))
            "error" -> ViewerEvent.Error(s("code"), s("message"))
            "picked" -> ViewerEvent.Picked(i("count"))
            "pickMissed" -> ViewerEvent.PickMissed
            "measure" -> ViewerEvent.Measure(vec("a"), vec("b"), o["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
            "snapshot" -> ViewerEvent.Snapshot(Base64.decode(s("data").substringAfter("base64,"), Base64.DEFAULT))
            else -> null
        }
    }
}

const val VIEWER_HOST = "appassets.androidplatform.net"

/** URL under which a file in the app's scans directory is served to the viewer. */
fun viewerUrlFor(scansDir: File, file: File): String =
    "https://$VIEWER_HOST/scans/" + file.relativeTo(scansDir).invariantSeparatorsPath

/**
 * Interactive 3D viewer: a WebView running the bundled three.js page. Assets and model files are
 * served through WebViewAssetLoader from https://appassets.androidplatform.net, so no file:// access
 * and no network access is needed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModelViewer(
    controller: ViewerController,
    scansDir: File,
    darkTheme: Boolean,
    onEvent: (ViewerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnEvent = rememberUpdatedState(onEvent)
    val loader = remember(scansDir) {
        WebViewAssetLoader.Builder()
            .setDomain(VIEWER_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/scans/", WebViewAssetLoader.InternalStoragePathHandler(context, scansDir))
            .build()
    }
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(if (darkTheme) Color.rgb(14, 20, 32) else Color.rgb(243, 246, 251))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            addJavascriptInterface(ViewerBridge { e ->
                if (e is ViewerEvent.Ready) {
                    controller.setTheme(darkTheme)
                    controller.onReady()
                }
                currentOnEvent.value(e)
            }, "Scan3DBridge")
            controller.webView = this
            loadUrl("https://$VIEWER_HOST/assets/viewer/index.html")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            controller.webView = null
            webView.removeJavascriptInterface("Scan3DBridge")
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

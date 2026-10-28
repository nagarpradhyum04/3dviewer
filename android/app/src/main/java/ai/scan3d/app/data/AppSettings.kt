package ai.scan3d.app.data

import ai.scan3d.app.BuildConfig
import ai.scan3d.core.units.LengthUnit
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val serverUrl: String,
    val apiKey: String,
    val units: LengthUnit,
    val theme: ThemeMode,
    /** The user agreed that photos are uploaded to [serverUrl] for reconstruction. */
    val uploadConsent: Boolean,
    /** Delete the job (photos + results) from the server once the model is downloaded. */
    val deleteServerCopy: Boolean,
    val liveQualityHints: Boolean,
    val onboardingDone: Boolean,
)

/** SharedPreferences-backed settings exposed as a StateFlow. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun read() = Settings(
        serverUrl = prefs.getString("server_url", null) ?: BuildConfig.DEFAULT_SERVER_URL,
        apiKey = prefs.getString("api_key", "") ?: "",
        units = LengthUnit.parse(prefs.getString("units", "MM")),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        uploadConsent = prefs.getBoolean("upload_consent", false),
        deleteServerCopy = prefs.getBoolean("delete_server_copy", false),
        liveQualityHints = prefs.getBoolean("live_hints", true),
        onboardingDone = prefs.getBoolean("onboarding_done", false),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_state.value)
        prefs.edit()
            .putString("server_url", s.serverUrl.trim())
            .putString("api_key", s.apiKey.trim())
            .putString("units", s.units.name)
            .putString("theme", s.theme.name)
            .putBoolean("upload_consent", s.uploadConsent)
            .putBoolean("delete_server_copy", s.deleteServerCopy)
            .putBoolean("live_hints", s.liveQualityHints)
            .putBoolean("onboarding_done", s.onboardingDone)
            .apply()
        _state.value = s
    }
}

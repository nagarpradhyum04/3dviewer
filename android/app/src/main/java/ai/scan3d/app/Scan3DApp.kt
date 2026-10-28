package ai.scan3d.app

import ai.scan3d.app.data.AppSettings
import ai.scan3d.app.data.ProcessingManager
import ai.scan3d.core.storage.ScanStore
import android.app.Application
import java.io.File

/** Holds the app-wide singletons. Kept deliberately simple (no DI framework). */
class Scan3DApp : Application() {
    lateinit var store: ScanStore
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var processing: ProcessingManager
        private set

    override fun onCreate() {
        super.onCreate()
        store = ScanStore(scansDir(this))
        settings = AppSettings(this)
        processing = ProcessingManager(store, settings)
        processing.resumeInterrupted()
    }

    companion object {
        fun scansDir(app: Application) = File(app.filesDir, "scans")
    }
}

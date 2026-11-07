package com.folio.pdf

import android.app.Application
import android.content.Context
import com.folio.pdf.data.BookmarksRepository
import com.folio.pdf.data.DocumentRepository
import com.folio.pdf.data.RecentsRepository
import com.folio.pdf.data.SettingsRepository
import com.folio.pdf.data.db.AppDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch

class FolioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PdfBox needs its fonts/resources wired up once per process.
        PDFBoxResourceLoader.init(applicationContext)
        com.folio.pdf.util.DeviceProfile.init(this)
        container = AppContainer(this)
        // Keep Lite mode in step with the setting (Automatic follows the phone's memory).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            container.settings.settings.collect { com.folio.pdf.util.DeviceProfile.apply(it.liteMode) }
        }
        if (BuildConfig.DEBUG) enableStrictMode()
    }

    /** Android asks apps to free memory; page images are the big item, and they're re-rendered on demand. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.folio.pdf.pdf.PdfFile.trimAll()
            com.folio.pdf.pdf.Thumbnails.trim()
            com.folio.pdf.ui.editor.AnnotationImages.trim()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** Debug builds only: log files left open and network on the main thread. */
    private fun enableStrictMode() {
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyLog().build(),
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build(),
        )
    }
}

/**
 * Tiny manual dependency container. Swap for Hilt later if the graph grows;
 * for a single-module app this keeps things readable.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.build(appContext)

    val recents = RecentsRepository(database.recentDocumentDao())
    val bookmarks = BookmarksRepository(database.bookmarkDao())
    val settings = SettingsRepository(appContext)
    val documents = DocumentRepository(appContext)
    val batch = com.folio.pdf.batch.BatchRunner(appContext, documents)
}

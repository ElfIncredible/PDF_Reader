package com.folio.pdf

import android.app.Application
import android.content.Context
import com.folio.pdf.data.BookmarksRepository
import com.folio.pdf.data.DocumentRepository
import com.folio.pdf.data.RecentsRepository
import com.folio.pdf.data.SettingsRepository
import com.folio.pdf.data.db.AppDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class FolioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PdfBox needs its fonts/resources wired up once per process.
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
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
}

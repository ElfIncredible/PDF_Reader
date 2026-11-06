package com.folio.pdf.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class DocumentInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * All file access goes through the Storage Access Framework, so the app needs
 * no storage permissions and works the same on Android 8 through 15+.
 */
class DocumentRepository(private val context: Context) {

    private val resolver = context.contentResolver

    /**
     * Keep access to a picked file across app restarts (needed for Recents).
     * Asks for write access too when the provider granted it, so edits can be saved
     * back into the original file; falls back to read-only.
     */
    fun persistReadPermission(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { resolver.takePersistableUriPermission(uri, readWrite) }.isSuccess
        if (!ok) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Overwrites [target] with the bytes of [source]. Throws if Folio may not write there. */
    suspend fun overwrite(target: Uri, source: java.io.File) = withContext(Dispatchers.IO) {
        val out = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target, "w")
            ?: throw java.io.FileNotFoundException("Cannot write $target")
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
    }

    fun persistTreePermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    suspend fun describe(uri: Uri): DocumentInfo = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        DocumentInfo(uri, name, size, 0L)
    }

    suspend fun folderName(treeUri: Uri): String = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Folder"
    }

    /** Recursively lists PDFs inside a granted folder. */
    suspend fun listPdfsInTree(treeUri: Uri, maxDepth: Int = 4): List<DocumentInfo> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext emptyList()
            if (!root.canRead()) throw SecurityException("Folder access was revoked")
            val out = mutableListOf<DocumentInfo>()
            suspend fun walk(dir: DocumentFile, depth: Int) {
                for (file in dir.listFiles()) {
                    coroutineContext.ensureActive()
                    if (file.isDirectory) {
                        if (depth < maxDepth) walk(file, depth + 1)
                    } else if (file.isPdf()) {
                        out += DocumentInfo(
                            uri = file.uri,
                            name = file.name ?: "Untitled.pdf",
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                        )
                    }
                }
            }
            walk(root, 0)
            out
        }

    private fun DocumentFile.isPdf(): Boolean =
        type == "application/pdf" || name?.endsWith(".pdf", ignoreCase = true) == true
}

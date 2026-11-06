package com.folio.pdf.batch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.folio.pdf.data.DocumentRepository
import com.folio.pdf.pdf.CompressionLevel
import com.folio.pdf.pdf.Metadata
import com.folio.pdf.pdf.OcrPdf
import com.folio.pdf.pdf.PdfSecurity
import com.folio.pdf.pdf.PdfTools
import com.folio.pdf.pdf.Permissions
import com.folio.pdf.pdf.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class BatchOp(val label: String, val suffix: String) {
    COMPRESS("Compress", "compressed"),
    OCR("Make searchable (OCR)", "searchable"),
    PROTECT("Add a password", "protected"),
    CLEAN("Remove hidden data", "clean"),
}

data class BatchOptions(
    val level: CompressionLevel = CompressionLevel.MEDIUM,
    val password: String = "",
)

sealed interface ItemStatus {
    data object Waiting : ItemStatus
    data class Working(val progress: Float) : ItemStatus
    data class Done(val output: Uri, val detail: String) : ItemStatus
    data class Failed(val message: String) : ItemStatus
}

data class BatchItem(val source: Uri, val name: String, val sizeBytes: Long, val status: ItemStatus = ItemStatus.Waiting)

data class BatchState(
    val op: BatchOp? = null,
    val items: List<BatchItem> = emptyList(),
    val running: Boolean = false,
    val folderName: String = "",
) {
    val finished: Int get() = items.count { it.status is ItemStatus.Done || it.status is ItemStatus.Failed }
    val failed: Int get() = items.count { it.status is ItemStatus.Failed }
}

/**
 * Runs one batch at a time for the whole app, so it keeps going when you leave the screen.
 * Each file is processed in turn and written next to the others in the chosen folder;
 * originals are never changed. A notification says when it's done.
 */
class BatchRunner(private val context: Context, private val documents: DocumentRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    fun start(op: BatchOp, files: List<Uri>, folder: Uri, options: BatchOptions) {
        if (_state.value.running || files.isEmpty()) return
        job = scope.launch {
            val dir = DocumentFile.fromTreeUri(context, folder)
            val items = files.map { uri ->
                val info = documents.describe(uri)
                BatchItem(uri, info.name, info.sizeBytes)
            }
            _state.value = BatchState(op, items, running = true, folderName = dir?.name.orEmpty())
            if (dir == null) {
                _state.update { s -> s.copy(running = false, items = s.items.map { it.copy(status = ItemStatus.Failed("Can't open the output folder")) }) }
                return@launch
            }
            items.forEachIndexed { index, item ->
                if (!isActive) return@forEachIndexed
                setStatus(index, ItemStatus.Working(0f))
                val result = runCatching { process(op, options, item, dir) { p -> setStatus(index, ItemStatus.Working(p)) } }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                setStatus(
                    index,
                    result.fold(
                        onSuccess = { it },
                        onFailure = { e -> ItemStatus.Failed((e as? ToolException)?.message ?: e.message ?: "Failed") },
                    ),
                )
            }
            _state.update { it.copy(running = false) }
            notifyDone()
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { s ->
            s.copy(running = false, items = s.items.map { if (it.status is ItemStatus.Done) it else it.copy(status = ItemStatus.Failed("Cancelled")) })
        }
    }

    fun clear() {
        if (!_state.value.running) _state.value = BatchState()
    }

    private fun setStatus(index: Int, status: ItemStatus) = _state.update { s ->
        s.copy(items = s.items.mapIndexed { i, it -> if (i == index) it.copy(status = status) else it })
    }

    private suspend fun process(
        op: BatchOp,
        options: BatchOptions,
        item: BatchItem,
        dir: DocumentFile,
        progress: (Float) -> Unit,
    ): ItemStatus.Done {
        val temp = File.createTempFile("folio-batch", ".pdf", context.cacheDir)
        try {
            var detail = ""
            when (op) {
                BatchOp.COMPRESS -> {
                    PdfTools.compress(context, item.source, options.level, temp, progress)
                    val saved = if (item.sizeBytes > 0) ((1 - temp.length().toDouble() / item.sizeBytes) * 100).toInt() else 0
                    detail = if (saved > 0) "$saved% smaller" else "already compact"
                }
                BatchOp.OCR -> {
                    val n = OcrPdf.makeSearchable(context, item.source, temp, progress)
                    detail = if (n == 0) "already had text" else "$n pages recognised"
                }
                BatchOp.PROTECT -> {
                    if (options.password.isBlank()) throw ToolException("No password set")
                    PdfSecurity.protect(context, item.source, options.password, "", Permissions(), temp)
                    detail = "protected"
                }
                BatchOp.CLEAN -> {
                    Metadata.removeHiddenData(context, item.source, temp)
                    detail = "cleaned"
                }
            }
            val base = item.name.substringBeforeLast('.').ifBlank { "Document" }
            val out = dir.createFile("application/pdf", "$base (${op.suffix}).pdf")
                ?: throw ToolException("Can't create files in that folder")
            documents.overwrite(out.uri, temp)
            return ItemStatus.Done(out.uri, detail)
        } finally {
            temp.delete()
        }
    }

    private fun notifyDone() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Batch jobs", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val s = _state.value
        val text = if (s.failed == 0) "All ${s.items.size} files done" else "${s.items.size - s.failed} done, ${s.failed} failed"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${s.op?.label ?: "Batch"} finished")
            .setContentText(text + if (s.folderName.isNotEmpty()) " · saved in ${s.folderName}" else "")
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL = "batch"
        const val NOTIFICATION_ID = 4201
    }
}

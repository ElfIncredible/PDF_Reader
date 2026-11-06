package com.folio.pdf.ui.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.AppContainer
import com.folio.pdf.data.DocumentInfo
import com.folio.pdf.pdf.CompressionLevel
import com.folio.pdf.pdf.ImageFormat
import com.folio.pdf.pdf.PaperSize
import com.folio.pdf.pdf.PdfTools
import com.folio.pdf.pdf.ToolException
import com.folio.pdf.util.formatFileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file chosen as input, with its page count once known (PDFs only). */
data class ToolInput(
    val info: DocumentInfo,
    val pages: Int? = null,
    val error: String? = null,
)

sealed interface JobState {
    data object Idle : JobState
    data class Running(val progress: Float?, val label: String) : JobState
    data class Done(
        val summary: String,
        val outputs: List<Uri>,
        /** Single PDF result that can be opened in the reader. */
        val openable: Uri? = null,
        val mime: String = "application/pdf",
    ) : JobState
    data class Failed(val message: String) : JobState
}

/** Runs one tool: holds its inputs and the state of the job. */
class ToolViewModel(
    private val kind: ToolKind,
    initial: Uri?,
    private val context: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val _inputs = MutableStateFlow<List<ToolInput>>(emptyList())
    val inputs: StateFlow<List<ToolInput>> = _inputs.asStateFlow()

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job.asStateFlow()

    private var running: Job? = null

    private val takesImages = kind == ToolKind.IMAGES_TO_PDF || kind == ToolKind.SCAN
    private val takesPdfs = !takesImages && kind != ToolKind.TEXT_TO_PDF

    init {
        initial?.let { add(listOf(it)) }
    }

    // ---- Inputs ------------------------------------------------------------------

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _job.value = JobState.Idle
        viewModelScope.launch {
            val single = kind != ToolKind.MERGE && !takesImages
            uris.forEach { container.documents.persistReadPermission(it) }
            val described = uris.map { ToolInput(container.documents.describe(it)) }
            _inputs.value = if (single) listOf(described.first()) else _inputs.value + described
            if (takesPdfs) described.forEach { loadPageCount(it.info.uri) }
        }
    }

    private suspend fun loadPageCount(uri: Uri) {
        val result = withContext(Dispatchers.IO) { runCatching { PdfTools.pageCount(context, uri) } }
        _inputs.update { list ->
            list.map {
                if (it.info.uri != uri) it
                else result.fold(
                    onSuccess = { n -> it.copy(pages = n) },
                    onFailure = { e -> it.copy(error = friendly(e)) },
                )
            }
        }
    }

    fun remove(index: Int) = _inputs.update { it.filterIndexed { i, _ -> i != index } }

    fun move(index: Int, delta: Int) = _inputs.update { list ->
        val target = index + delta
        if (index !in list.indices || target !in list.indices) list
        else list.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun reset() {
        running?.cancel()
        _job.value = JobState.Idle
    }

    fun cancel() {
        running?.cancel()
        _job.value = JobState.Idle
    }

    private val first: ToolInput? get() = _inputs.value.firstOrNull()

    /** Name without ".pdf" for building output names. */
    fun baseName(): String = first?.info?.name
        ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Document"

    // ---- Jobs --------------------------------------------------------------------

    fun merge(output: Uri) = runToFile(output, "Merging…") { temp, progress ->
        val list = _inputs.value
        PdfTools.merge(context, list.map { it.info.uri }, temp, progress)
        val pages = list.sumOf { it.pages ?: 0 }
        "Merged ${list.size} files · $pages pages · ${formatFileSize(temp.length())}"
    }

    fun split(ranges: List<IntRange>, folder: Uri) = launchJob("Splitting…") { progress ->
        val input = first ?: throw ToolException("Choose a PDF first")
        val dir = DocumentFile.fromTreeUri(context, folder) ?: throw ToolException("Can't open that folder")
        val base = baseName()
        val created = ArrayList<Uri>()
        PdfTools.split(
            context, input.info.uri, ranges,
            open = { i, range ->
                val name = "$base (pages ${com.folio.pdf.pdf.PageRanges.label(range)}).pdf"
                val file = dir.createFile("application/pdf", name) ?: throw ToolException("Can't create files in that folder")
                created += file.uri
                context.contentResolver.openOutputStream(file.uri)
            },
            progress = progress,
        )
        JobState.Done("Created ${created.size} PDFs in ${dir.name ?: "the folder"}", created)
    }

    fun compress(level: CompressionLevel, output: Uri) = runToFile(output, "Compressing…") { temp, progress ->
        val input = first ?: throw ToolException("Choose a PDF first")
        PdfTools.compress(context, input.info.uri, level, temp, progress)
        val before = input.info.sizeBytes
        val after = temp.length()
        if (before > 0) {
            val saved = ((1 - after.toDouble() / before) * 100).toInt()
            if (saved > 0) "${formatFileSize(before)} → ${formatFileSize(after)} ($saved% smaller)"
            else "${formatFileSize(after)}. This PDF was already compact; there wasn't much to shrink."
        } else {
            "Compressed to ${formatFileSize(after)}"
        }
    }

    fun imagesToPdf(paper: PaperSize, marginPt: Float, output: Uri) = runToFile(output, "Building PDF…") { temp, progress ->
        val list = _inputs.value
        PdfTools.imagesToPdf(context, list.map { it.info.uri }, paper, marginPt, temp, progress)
        "${list.size} images → ${list.size}-page PDF · ${formatFileSize(temp.length())}"
    }

    fun pdfToImages(format: ImageFormat, dpi: Int, folder: Uri) = launchJob("Saving pages…") { progress ->
        val input = first ?: throw ToolException("Choose a PDF first")
        val files = PdfTools.pdfToImages(context, input.info.uri, format, dpi, folder, baseName(), progress)
        JobState.Done("Saved ${files.size} ${format.label} images", files, mime = format.mime)
    }

    fun scanToPdf(recognise: Boolean, output: Uri) =
        runToFile(output, if (recognise) "Recognising text…" else "Building PDF…") { temp, progress ->
            val pages = _inputs.value
            com.folio.pdf.pdf.OcrPdf.fromImages(context, pages.map { it.info.uri }, recognise, temp, progress)
            "${pages.size}-page PDF" + (if (recognise) " · searchable" else "") + " · ${formatFileSize(temp.length())}"
        }

    fun ocr(output: Uri) = runToFile(output, "Recognising text…") { temp, progress ->
        val input = first ?: throw ToolException("Choose a PDF first")
        val count = com.folio.pdf.pdf.OcrPdf.makeSearchable(context, input.info.uri, temp, progress)
        if (count == 0) "No scanned pages found: every page already has text. Saved an unchanged copy."
        else "Made $count ${if (count == 1) "page" else "pages"} searchable"
    }

    fun textToPdf(fontSize: Float, output: Uri) = runToFile(output, "Converting…") { temp, _ ->
        val input = first ?: throw ToolException("Choose a text file first")
        PdfTools.textToPdf(context, input.info.uri, fontSize, temp)
        "Created a PDF · ${formatFileSize(temp.length())}"
    }

    /** Writes to a temp file, then copies it to the chosen [output]. */
    private fun runToFile(
        output: Uri,
        label: String,
        work: suspend (temp: File, progress: (Float) -> Unit) -> String,
    ) = launchJob(label) { progress ->
        val temp = File.createTempFile("folio-tool", ".pdf", context.cacheDir)
        try {
            val summary = work(temp, progress)
            container.documents.overwrite(output, temp)
            container.documents.persistReadPermission(output)
            JobState.Done(summary, listOf(output), openable = output)
        } finally {
            temp.delete()
        }
    }

    private fun launchJob(label: String, work: suspend (progress: (Float) -> Unit) -> JobState.Done) {
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(null, label)
        running = viewModelScope.launch {
            _job.value = try {
                work { p -> _job.value = JobState.Running(p.coerceIn(0f, 1f), label) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                JobState.Failed(friendly(e))
            }
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is ToolException -> e.message ?: "Something went wrong"
        is OutOfMemoryError -> "This file is too large for your phone's memory."
        is SecurityException -> "Folio isn't allowed to read or write that file. Try choosing it again."
        is java.io.FileNotFoundException -> "The file couldn't be found. It may have been moved or deleted."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }
}

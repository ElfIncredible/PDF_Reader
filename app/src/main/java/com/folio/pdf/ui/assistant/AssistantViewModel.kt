package com.folio.pdf.ui.assistant

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.AppContainer
import com.folio.pdf.data.AppSettings
import com.folio.pdf.net.Citation
import com.folio.pdf.net.ExtractedTable
import com.folio.pdf.net.FolioServer
import com.folio.pdf.net.ServerException
import com.folio.pdf.net.Summary
import com.folio.pdf.pdf.PdfTextExtractor
import com.folio.pdf.pdf.PdfTools
import com.folio.pdf.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The state of one AI request. */
sealed interface Ai<out T> {
    data object Idle : Ai<Nothing>
    data class Running(val progress: Float? = null, val label: String = "") : Ai<Nothing>
    data class Done<T>(val value: T) : Ai<T>
    data class Failed(val message: String) : Ai<Nothing>
}

data class ChatTurn(
    val question: String,
    val answer: String? = null,
    val citations: List<Citation> = emptyList(),
    val error: String? = null,
)

data class Translation(val text: String, val from: String, val to: String, val pages: String)

class AssistantViewModel(
    private val uri: Uri,
    private val context: Context,
    private val container: AppContainer,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Page texts (null while reading). */
    private val _pages = MutableStateFlow<List<String>?>(null)
    val pages: StateFlow<List<String>?> = _pages.asStateFlow()

    var title: String = "Document"
        private set

    private val _summary = MutableStateFlow<Ai<Summary>>(Ai.Idle)
    val summary: StateFlow<Ai<Summary>> = _summary.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatTurn>>(emptyList())
    val chat: StateFlow<List<ChatTurn>> = _chat.asStateFlow()
    private val _asking = MutableStateFlow(false)
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    private val _tables = MutableStateFlow<Ai<List<ExtractedTable>>>(Ai.Idle)
    val tables: StateFlow<Ai<List<ExtractedTable>>> = _tables.asStateFlow()

    private val _translation = MutableStateFlow<Ai<Translation>>(Ai.Idle)
    val translation: StateFlow<Ai<Translation>> = _translation.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            title = runCatching { container.documents.describe(uri).name.substringBeforeLast('.') }.getOrDefault("Document")
            val collected = ArrayList<Pair<Int, String>>()
            runCatching {
                PdfTextExtractor.extract(context, uri, onOutline = {}) { i, t -> collected += i to t.text }
            }
            val count = (collected.maxOfOrNull { it.first } ?: -1) + 1
            val list = MutableList(count) { "" }
            collected.forEach { (i, t) -> list[i] = t }
            _pages.value = list
        }
    }

    val hasText: Boolean get() = _pages.value?.any { it.isNotBlank() } == true

    private fun server(): FolioServer =
        FolioServer.from(context, settings.value) ?: throw ServerException("Set up your Folio server in Settings first")

    fun allowServer() = viewModelScope.launch { container.settings.setServerConsent(true) }

    fun consumeMessage() { _message.value = null }

    private fun friendly(e: Throwable) = when (e) {
        is ServerException -> e.message ?: "Server problem"
        else -> e.message ?: "Something went wrong"
    }

    // ---- Summary -------------------------------------------------------------------

    fun summarize(length: String) {
        val pages = _pages.value ?: return
        if (_summary.value is Ai.Running) return
        _summary.value = Ai.Running(label = "Reading and summarising… this can take a minute")
        viewModelScope.launch {
            _summary.value = try {
                Ai.Done(server().summarize(pages, title, length))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Ai.Failed(friendly(e))
            }
        }
    }

    // ---- Ask -------------------------------------------------------------------------

    fun ask(question: String) {
        val pages = _pages.value ?: return
        val q = question.trim()
        if (q.isEmpty() || _asking.value) return
        val history = _chat.value.mapNotNull { t -> t.answer?.let { t.question to it } }
        _chat.update { it + ChatTurn(q) }
        _asking.value = true
        viewModelScope.launch {
            val turn = try {
                val a = server().ask(pages, q, history)
                ChatTurn(q, a.answer, a.citations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChatTurn(q, error = friendly(e))
            }
            _chat.update { list -> list.dropLast(1) + turn }
            _asking.value = false
        }
    }

    fun clearChat() { _chat.value = emptyList() }

    // ---- Tables ----------------------------------------------------------------------

    fun findTables() {
        if (_tables.value is Ai.Running) return
        _tables.value = Ai.Running(label = "Looking for tables…")
        viewModelScope.launch {
            _tables.value = try {
                Ai.Done(server().tables(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Ai.Failed(friendly(e))
            }
        }
    }

    fun saveCsv(table: ExtractedTable, target: Uri) = viewModelScope.launch {
        val csv = table.rows.joinToString("\r\n") { row ->
            row.joinToString(",") { cell ->
                if (cell.any { it == ',' || it == '"' || it == '\n' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
            }
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                // UTF-8 byte-order mark so Excel opens accented characters correctly.
                context.contentResolver.openOutputStream(target, "wt")?.use { it.write("﻿$csv".toByteArray(Charsets.UTF_8)) }
            }.isSuccess
        }
        _message.value = if (ok) "Saved the table as CSV" else "Couldn't save the CSV"
    }

    fun saveXlsx(target: Uri) = viewModelScope.launch {
        _message.value = try {
            val bytes = server().tablesXlsx(uri)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } }
            "Saved all tables as an Excel workbook"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            friendly(e)
        }
    }

    // ---- Translate (on the phone) ---------------------------------------------------------

    /** [page] is 0-based, or null for the whole document (first 30 pages). */
    fun translate(target: String, page: Int?, wifiOnly: Boolean) {
        val pages = _pages.value ?: return
        if (_translation.value is Ai.Running) return
        val chosen = if (page != null) listOf(pages.getOrElse(page) { "" }) else pages.take(30)
        val text = chosen.joinToString("\n\n").trim()
        if (text.isBlank()) {
            _translation.value = Ai.Failed("There's no text to translate there. If it's a scan, make it searchable first.")
            return
        }
        _translation.value = Ai.Running(null, "Preparing…")
        viewModelScope.launch {
            _translation.value = try {
                val source = Translator.detect(text) ?: throw IllegalStateException("Couldn't tell which language this is")
                _translation.value = Ai.Running(null, "Downloading the language pack if needed…")
                val out = Translator.translate(text, source, target, wifiOnly) { p ->
                    _translation.value = Ai.Running(p, "Translating…")
                }
                Ai.Done(
                    Translation(out, source, target, if (page != null) "page ${page + 1}" else "pages 1–${chosen.size}"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Ai.Failed(
                    if (wifiOnly && e.message?.contains("wifi", ignoreCase = true) == true) {
                        "The language pack downloads over Wi-Fi. Connect to Wi-Fi, or allow mobile data."
                    } else {
                        "Couldn't translate: ${e.message ?: "unknown error"}"
                    },
                )
            }
        }
    }

    /** Saves the translation as a simple PDF (Text → PDF from Phase 4). */
    fun saveTranslation(target: Uri) = viewModelScope.launch {
        val t = (_translation.value as? Ai.Done)?.value ?: return@launch
        _message.value = try {
            val txt = File.createTempFile("folio-translation", ".txt", context.cacheDir)
            val pdf = File.createTempFile("folio-translation", ".pdf", context.cacheDir)
            try {
                txt.writeText(t.text)
                PdfTools.textToPdf(context, Uri.fromFile(txt), 12f, pdf)
                container.documents.overwrite(target, pdf)
            } finally {
                txt.delete(); pdf.delete()
            }
            "Saved the translation as a PDF"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't save: ${e.message}"
        }
    }
}

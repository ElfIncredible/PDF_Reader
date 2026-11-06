package com.folio.pdf.ui.tools

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.AppContainer
import com.folio.pdf.pdf.PageOp
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.pdf.PdfTools
import com.folio.pdf.pdf.Thumbnails
import com.folio.pdf.pdf.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A page in the organiser. [key] stays the same as the page moves around. */
data class PageItem(val key: Int, val source: Int, val rotation: Int = 0)

data class OrganizeState(
    val loading: Boolean = true,
    val error: String? = null,
    val document: PdfFile? = null,
    val title: String = "",
    val pages: List<PageItem> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val canUndo: Boolean = false,
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = canUndo
    val selectionCount: Int get() = selected.size
}

sealed interface OrganizeEvent {
    data class Message(val text: String) : OrganizeEvent
    data object NeedCopy : OrganizeEvent
    data class Open(val uri: Uri) : OrganizeEvent
}

class OrganizeViewModel(
    private val uri: Uri,
    private val context: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(OrganizeState())
    val state: StateFlow<OrganizeState> = _state.asStateFlow()

    private val _events = Channel<OrganizeEvent>(Channel.BUFFERED)
    val events: Flow<OrganizeEvent> = _events.receiveAsFlow()

    private val history = ArrayDeque<List<PageItem>>()

    init { load() }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            container.documents.persistReadPermission(uri)
            val info = container.documents.describe(uri)
            val doc = PdfFile.open(context, uri)
            history.clear()
            _state.value = OrganizeState(
                loading = false,
                document = doc,
                title = info.name.substringBeforeLast('.'),
                pages = List(doc.pageCount) { PageItem(key = it, source = it) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = OrganizeState(loading = false, error = "Couldn't open this PDF. It may be password-protected or damaged.")
        }
    }

    // ---- Selection ---------------------------------------------------------------

    fun toggle(key: Int) = _state.update {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    fun selectAll() = _state.update { it.copy(selected = it.pages.map { p -> p.key }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    // ---- Edits (each one undoable) ----------------------------------------------

    private fun edit(transform: (List<PageItem>, Set<Int>) -> List<PageItem>) {
        val s = _state.value
        val next = transform(s.pages, s.selected)
        if (next == s.pages) return
        history.addLast(s.pages)
        if (history.size > 50) history.removeFirst()
        _state.value = s.copy(pages = next, canUndo = true, selected = s.selected.filter { k -> next.any { it.key == k } }.toSet())
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        _state.update { it.copy(pages = previous, canUndo = history.isNotEmpty()) }
    }

    fun rotateSelected(degrees: Int) = edit { pages, sel ->
        pages.map { if (it.key in sel) it.copy(rotation = ((it.rotation + degrees) % 360 + 360) % 360) else it }
    }

    fun deleteSelected() {
        val s = _state.value
        if (s.selected.size >= s.pages.size) {
            viewModelScope.launch { _events.send(OrganizeEvent.Message("A PDF needs at least one page. Keep at least one.")) }
            return
        }
        edit { pages, sel -> pages.filterNot { it.key in sel } }
    }

    /** Moves the selected pages one step towards the start (-1) or end (+1). */
    fun moveSelected(delta: Int) = edit { pages, sel ->
        val list = pages.toMutableList()
        if (delta < 0) {
            for (i in 1 until list.size) {
                if (list[i].key in sel && list[i - 1].key !in sel) list[i] = list[i - 1].also { list[i - 1] = list[i] }
            }
        } else {
            for (i in list.size - 2 downTo 0) {
                if (list[i].key in sel && list[i + 1].key !in sel) list[i] = list[i + 1].also { list[i + 1] = list[i] }
            }
        }
        list
    }

    /** Moves the selected pages, in their current order, to start at [position] (1-based). */
    fun moveSelectedTo(position: Int) = edit { pages, sel ->
        val moving = pages.filter { it.key in sel }
        val rest = pages.filterNot { it.key in sel }.toMutableList()
        rest.addAll((position - 1).coerceIn(0, rest.size), moving)
        rest
    }

    // ---- Saving ------------------------------------------------------------------

    private fun ops(items: List<PageItem>) = items.map { PageOp(it.source, it.rotation) }

    /** [copyTarget] null overwrites the original file. */
    fun save(copyTarget: Uri?) = viewModelScope.launch {
        val s = _state.value
        if (s.saving) return@launch
        _state.update { it.copy(saving = true) }
        val temp = File.createTempFile("folio-organize", ".pdf", context.cacheDir)
        try {
            PdfTools.organize(context, uri, ops(s.pages), temp)
            if (copyTarget != null) {
                container.documents.overwrite(copyTarget, temp)
                container.documents.persistReadPermission(copyTarget)
                history.clear()
                _state.update { it.copy(canUndo = false) }
                _events.send(OrganizeEvent.Open(copyTarget))
            } else {
                // Release our own copy of the file before replacing it.
                s.document?.close()
                _state.update { it.copy(document = null) }
                try {
                    container.documents.overwrite(uri, temp)
                } finally {
                    Thumbnails.invalidate(uri.toString())
                    load()
                }
                _events.send(OrganizeEvent.Message("Saved"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _events.send(OrganizeEvent.NeedCopy)
        } catch (e: java.io.FileNotFoundException) {
            _events.send(OrganizeEvent.NeedCopy)
        } catch (e: ToolException) {
            _events.send(OrganizeEvent.Message(e.message ?: "Couldn't save"))
        } catch (e: Exception) {
            _events.send(OrganizeEvent.Message("Couldn't save: ${e.message ?: "unknown error"}"))
        } finally {
            temp.delete()
            _state.update { it.copy(saving = false) }
        }
    }

    /** Saves just the selected pages (in their current order and rotation) as a new PDF. */
    fun extractSelected(target: Uri) = viewModelScope.launch {
        val s = _state.value
        val chosen = s.pages.filter { it.key in s.selected }
        if (chosen.isEmpty()) return@launch
        _state.update { it.copy(saving = true) }
        val temp = File.createTempFile("folio-extract", ".pdf", context.cacheDir)
        try {
            PdfTools.extract(context, uri, ops(chosen), temp)
            container.documents.overwrite(target, temp)
            container.documents.persistReadPermission(target)
            _events.send(OrganizeEvent.Message("Saved ${chosen.size} pages as a new PDF"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(OrganizeEvent.Message("Couldn't extract: ${e.message ?: "unknown error"}"))
        } finally {
            temp.delete()
            _state.update { it.copy(saving = false) }
        }
    }

    override fun onCleared() {
        _state.value.document?.close()
    }
}

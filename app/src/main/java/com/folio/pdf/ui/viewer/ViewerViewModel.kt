package com.folio.pdf.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.data.DocumentRepository
import com.folio.pdf.data.RecentsRepository
import com.folio.pdf.pdf.PdfFile
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data class Error(val title: String, val message: String) : ViewerUiState
    data class Ready(
        val document: PdfFile,
        val title: String,
        val initialPage: Int,
        /** height / width of page 1, used as a placeholder until each page is measured. */
        val placeholderAspect: Float,
    ) : ViewerUiState
}

class ViewerViewModel(
    private val uri: Uri,
    private val appContext: Context,
    private val documents: DocumentRepository,
    private val recents: RecentsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val currentPage = MutableStateFlow<Int?>(null)
    private var document: PdfFile? = null

    init {
        load()
        saveProgressAsYouRead()
    }

    private fun load() = viewModelScope.launch {
        _state.value = try {
            val info = documents.describe(uri)
            val doc = PdfFile.open(appContext, uri).also { document = it }
            if (doc.pageCount == 0) {
                ViewerUiState.Error("Empty document", "This PDF has no pages.")
            } else {
                val record = recents.recordOpened(info, doc.pageCount)
                ViewerUiState.Ready(
                    document = doc,
                    title = info.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                    initialPage = record.lastPage,
                    placeholderAspect = doc.pageSize(0)?.aspect ?: 1.414f,
                )
            }
        } catch (e: SecurityException) {
            ViewerUiState.Error(
                "Can't open this PDF",
                "It may be password-protected (unlocking arrives in Phase 6), or Folio's access to it " +
                    "has expired. Try opening it again from your files.",
            )
        } catch (e: FileNotFoundException) {
            ViewerUiState.Error("File not found", "It may have been moved, renamed or deleted.")
        } catch (e: Exception) {
            ViewerUiState.Error("Can't open this PDF", "The file looks damaged or isn't a valid PDF.")
        }
    }

    @OptIn(FlowPreview::class)
    private fun saveProgressAsYouRead() = viewModelScope.launch {
        currentPage.filterNotNull()
            .distinctUntilChanged()
            .debounce(600)
            .collect { recents.saveProgress(uri.toString(), it) }
    }

    fun onPageChanged(page: Int) { currentPage.value = page }

    override fun onCleared() {
        document?.close()
    }
}

package com.folio.pdf.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.data.DocumentInfo
import com.folio.pdf.data.DocumentRepository
import com.folio.pdf.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

enum class SortOrder(val label: String) { RECENT("Last modified"), NAME("Name"), SIZE("Size") }

sealed interface BrowserContent {
    data object Loading : BrowserContent
    data object NoFolder : BrowserContent
    data class Error(val message: String) : BrowserContent
    data class Files(val folderName: String, val files: List<DocumentInfo>, val total: Int) : BrowserContent
}

data class BrowserUiState(
    val content: BrowserContent = BrowserContent.Loading,
    val query: String = "",
    val sort: SortOrder = SortOrder.RECENT,
)

class BrowserViewModel(
    private val settings: SettingsRepository,
    private val documents: DocumentRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(SortOrder.RECENT)
    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderContent = combine(
        settings.settings.map { it.libraryTreeUri }.distinctUntilChanged(),
        refreshTick,
    ) { tree, _ -> tree }
        .transformLatest { tree ->
            if (tree == null) {
                emit(BrowserContent.NoFolder)
                return@transformLatest
            }
            emit(BrowserContent.Loading)
            val content = try {
                val uri = Uri.parse(tree)
                val files = documents.listPdfsInTree(uri)
                BrowserContent.Files(documents.folderName(uri), files, files.size)
            } catch (e: SecurityException) {
                BrowserContent.Error("Folio no longer has access to this folder. Please choose it again.")
            } catch (e: Exception) {
                BrowserContent.Error("Couldn't read this folder (${e.message ?: "unknown error"}).")
            }
            emit(content)
        }

    val uiState: StateFlow<BrowserUiState> = combine(folderContent, query, sort) { content, q, s ->
        val shaped = if (content is BrowserContent.Files) {
            val filtered = content.files.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
            content.copy(
                files = when (s) {
                    SortOrder.RECENT -> filtered.sortedByDescending { it.lastModified }
                    SortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
                    SortOrder.SIZE -> filtered.sortedByDescending { it.sizeBytes }
                },
            )
        } else content
        BrowserUiState(shaped, q, s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    fun setQuery(value: String) { query.value = value }
    fun setSort(value: SortOrder) { sort.value = value }
    fun refresh() { refreshTick.value++ }

    fun onFolderPicked(uri: Uri) {
        documents.persistTreePermission(uri)
        viewModelScope.launch { settings.setLibraryTreeUri(uri.toString()) }
    }

    fun onFilePicked(uri: Uri) = documents.persistReadPermission(uri)

    companion object {
        fun factory(c: AppContainer) = viewModelFactory {
            initializer { BrowserViewModel(c.settings, c.documents) }
        }
    }
}

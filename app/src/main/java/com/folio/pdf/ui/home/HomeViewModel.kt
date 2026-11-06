package com.folio.pdf.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.data.DocumentRepository
import com.folio.pdf.data.RecentsRepository
import com.folio.pdf.data.db.RecentDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RecentFilter { ALL, FAVORITES }

data class HomeUiState(
    val documents: List<RecentDocument> = emptyList(),
    val filter: RecentFilter = RecentFilter.ALL,
    val loading: Boolean = true,
)

class HomeViewModel(
    private val recents: RecentsRepository,
    private val documents: DocumentRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(RecentFilter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = filter
        .flatMapLatest { f ->
            val source = if (f == RecentFilter.FAVORITES) recents.observeFavorites() else recents.observeRecent()
            source.map { HomeUiState(documents = it, filter = f, loading = false) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(value: RecentFilter) { filter.value = value }

    fun onDocumentPicked(uri: Uri) = documents.persistReadPermission(uri)

    fun toggleFavorite(uri: String) = viewModelScope.launch { recents.toggleFavorite(uri) }

    fun remove(uri: String) = viewModelScope.launch { recents.remove(uri) }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(c.recents, c.documents) }
        }
    }
}

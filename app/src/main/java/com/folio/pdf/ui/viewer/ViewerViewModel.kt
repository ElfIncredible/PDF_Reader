package com.folio.pdf.ui.viewer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.AppContainer
import com.folio.pdf.data.ColorMode
import com.folio.pdf.data.ScrollMode
import com.folio.pdf.data.db.Bookmark
import com.folio.pdf.pdf.OutlineEntry
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.pdf.PdfTextExtractor
import com.folio.pdf.pdf.SearchHit
import com.folio.pdf.pdf.TextSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicReferenceArray

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

/** Progress of reading the document's text in the background. */
data class IndexProgress(
    val done: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val characters: Long = 0,
) {
    val hasText: Boolean get() = characters > 0
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val current: Int = -1,
    val searching: Boolean = false,
) {
    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

data class ReaderPrefs(
    val scrollMode: ScrollMode = ScrollMode.VERTICAL,
    val colorMode: ColorMode = ColorMode.NORMAL,
    val keepScreenOn: Boolean = false,
)

class ViewerViewModel(
    private val uri: Uri,
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val key = uri.toString()

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var document: PdfFile? = null
    @Volatile private var texts: AtomicReferenceArray<PageText?>? = null

    private val _index = MutableStateFlow(IndexProgress())
    val index: StateFlow<IndexProgress> = _index.asStateFlow()

    /** null while loading; empty if the PDF has no table of contents. */
    private val _outline = MutableStateFlow<List<OutlineEntry>?>(null)
    val outline: StateFlow<List<OutlineEntry>?> = _outline.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = container.bookmarks.observe(key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isFavorite: StateFlow<Boolean> = container.recents.observe(key)
        .map { it?.isFavorite == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val prefs: StateFlow<ReaderPrefs> = container.settings.settings
        .map { ReaderPrefs(it.scrollMode, it.colorMode, it.keepScreenOn) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    private val query = MutableStateFlow("")
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val currentPage = MutableStateFlow<Int?>(null)

    init {
        load()
        saveProgressAsYouRead()
        observeSearch()
    }

    // ---- Opening -----------------------------------------------------------------

    private fun load() = viewModelScope.launch {
        _state.value = try {
            val info = container.documents.describe(uri)
            val doc = PdfFile.open(appContext, uri).also { document = it }
            if (doc.pageCount == 0) {
                ViewerUiState.Error("Empty document", "This PDF has no pages.")
            } else {
                val record = container.recents.recordOpened(info, doc.pageCount)
                texts = AtomicReferenceArray(doc.pageCount)
                startIndexing(doc.pageCount)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ViewerUiState.Error("Can't open this PDF", "The file looks damaged or isn't a valid PDF.")
        }
    }

    private fun startIndexing(total: Int) = viewModelScope.launch {
        _index.value = IndexProgress(total = total)
        var done = 0
        var characters = 0L
        var lastEmit = 0L
        try {
            PdfTextExtractor.extract(
                context = appContext,
                uri = uri,
                onOutline = { _outline.value = it },
            ) { pageIndex, text ->
                if (pageIndex in 0 until total) texts?.set(pageIndex, text)
                done++
                characters += text.text.count { !it.isWhitespace() }
                val now = SystemClock.uptimeMillis()
                if (now - lastEmit > 300) {
                    lastEmit = now
                    _index.value = IndexProgress(done, total, characters = characters)
                }
            }
            _index.value = IndexProgress(done, total, finished = true, characters = characters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Reading still works; only search/selection/contents are unavailable.
            if (_outline.value == null) _outline.value = emptyList()
            _index.value = _index.value.copy(finished = true, failed = true)
        }
    }

    fun pageText(page: Int): PageText? = texts?.let { if (page in 0 until it.length()) it.get(page) else null }

    // ---- Reading progress --------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun saveProgressAsYouRead() = viewModelScope.launch {
        currentPage.filterNotNull()
            .distinctUntilChanged()
            .debounce(600)
            .collect { container.recents.saveProgress(key, it) }
    }

    fun onPageChanged(page: Int) { currentPage.value = page }

    // ---- Search ------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun observeSearch() = viewModelScope.launch {
        combine(
            query.debounce(250),
            // Re-run as more pages are read, but not more than about once a second.
            _index.sample(800).onStart { emit(_index.value) },
        ) { q, idx -> q to idx }
            .collectLatest { (q, idx) ->
                if (q.isBlank()) {
                    _search.value = SearchState()
                    return@collectLatest
                }
                _search.update {
                    if (it.query == q) it.copy(searching = true) else SearchState(query = q, searching = true)
                }
                val pages = texts ?: return@collectLatest
                val hits = withContext(Dispatchers.Default) { TextSearch.find(pages, q) }
                _search.update { prev ->
                    val keep = when {
                        hits.isEmpty() -> -1
                        prev.query == q && prev.current in hits.indices -> prev.current
                        else -> 0
                    }
                    SearchState(q, hits, keep, searching = !idx.finished)
                }
            }
    }

    fun setQuery(value: String) {
        query.value = value
        if (value.isBlank()) _search.value = SearchState()
    }

    fun nextHit() = _search.update {
        if (it.hits.isEmpty()) it else it.copy(current = (it.current + 1) % it.hits.size)
    }

    fun previousHit() = _search.update {
        if (it.hits.isEmpty()) it else it.copy(current = (it.current - 1 + it.hits.size) % it.hits.size)
    }

    fun selectHit(index: Int) = _search.update {
        if (index in it.hits.indices) it.copy(current = index) else it
    }

    // ---- Bookmarks, favourites, preferences --------------------------------------

    fun toggleBookmark(page: Int) = viewModelScope.launch {
        val existing = bookmarks.value.firstOrNull { it.page == page }
        if (existing != null) {
            container.bookmarks.remove(existing)
        } else {
            val label = pageText(page)?.text
                ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.length > 2 }
                ?.take(80).orEmpty()
            container.bookmarks.add(key, page, label)
        }
    }

    fun removeBookmark(bookmark: Bookmark) = viewModelScope.launch { container.bookmarks.remove(bookmark) }

    fun toggleFavorite() = viewModelScope.launch { container.recents.toggleFavorite(key) }

    fun setScrollMode(mode: ScrollMode) = viewModelScope.launch { container.settings.setScrollMode(mode) }

    fun setColorMode(mode: ColorMode) = viewModelScope.launch { container.settings.setColorMode(mode) }

    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { container.settings.setKeepScreenOn(on) }

    override fun onCleared() {
        document?.close()
    }
}

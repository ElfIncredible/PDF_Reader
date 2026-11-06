package com.folio.pdf.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.AppContainer
import com.folio.pdf.data.ColorMode
import com.folio.pdf.data.ScrollMode
import com.folio.pdf.data.db.Bookmark
import com.folio.pdf.edit.Annotation
import com.folio.pdf.edit.AnnotationEditor
import com.folio.pdf.edit.EditorState
import com.folio.pdf.edit.ImageStamp
import com.folio.pdf.edit.NPoint
import com.folio.pdf.edit.NRect
import com.folio.pdf.edit.TextNote
import com.folio.pdf.edit.Tool
import com.folio.pdf.edit.ToolSettings
import com.folio.pdf.pdf.AnnotationReader
import com.folio.pdf.pdf.AnnotationWriter
import com.folio.pdf.pdf.Thumbnails
import com.folio.pdf.ui.editor.EditorActions
import com.folio.pdf.pdf.OutlineEntry
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.pdf.PdfTextExtractor
import com.folio.pdf.pdf.SearchHit
import com.folio.pdf.pdf.TextSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data class Error(val title: String, val message: String) : ViewerUiState
    /** The PDF needs a password to open. */
    data class NeedsPassword(val wrong: Boolean) : ViewerUiState
    data class Ready(
        val document: PdfFile,
        val title: String,
        val initialPage: Int,
        /** height / width of page 1, used as a placeholder until each page is measured. */
        val placeholderAspect: Float,
        /** Opened with a password: read-only until the password is removed. */
        val locked: Boolean = false,
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

/** One-off messages from the ViewModel to the screen. */
sealed interface ViewerEvent {
    data class Message(val text: String) : ViewerEvent
    /** Saved into the original file. */
    data object Saved : ViewerEvent
    /** The original can't be written; ask where to save a copy. */
    data object NeedCopy : ViewerEvent
    /** A copy was saved; open it. */
    data class OpenCopy(val uri: Uri) : ViewerEvent
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
) : ViewModel(), EditorActions {

    private val key = uri.toString()

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var document: PdfFile? = null

    /** What we actually read: the file itself, or a private unlocked copy of a protected PDF. */
    private var workingUri: Uri = uri
    private var unlockedCopy: File? = null
    private val locked: Boolean get() = unlockedCopy != null
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
    private var indexJob: Job? = null

    // ---- Editing state (Phase 3)
    private val editor = AnnotationEditor()
    val editorState: StateFlow<EditorState> = editor.state

    /** False until the file's existing annotations have been read. */
    /** Fillable form fields in this PDF (0 if none). */
    private val _formFields = MutableStateFlow(0)
    val formFields: StateFlow<Int> = _formFields.asStateFlow()

    private val _annotationsReady = MutableStateFlow(false)
    val annotationsReady: StateFlow<Boolean> = _annotationsReady.asStateFlow()

    private val _tools = MutableStateFlow(ToolSettings())
    val tools: StateFlow<ToolSettings> = _tools.asStateFlow()

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events: Flow<ViewerEvent> = _events.receiveAsFlow()

    private val ids = AtomicLong(System.currentTimeMillis())
    private val imageDir = File(appContext.filesDir, "annotation-images")

    init {
        load()
        saveProgressAsYouRead()
        observeSearch()
    }

    // ---- Opening -----------------------------------------------------------------

    private fun load(resumePage: Int? = null) = viewModelScope.launch {
        _state.value = try {
            val info = container.documents.describe(uri)
            val doc = PdfFile.open(appContext, workingUri).also { document = it }
            if (doc.pageCount == 0) {
                ViewerUiState.Error("Empty document", "This PDF has no pages.")
            } else {
                val record = container.recents.recordOpened(info, doc.pageCount)
                texts = AtomicReferenceArray(doc.pageCount)
                startIndexing(doc.pageCount)
                ViewerUiState.Ready(
                    document = doc,
                    title = info.name.removeSuffix(".pdf").removeSuffix(".PDF"),
                    initialPage = resumePage?.coerceIn(0, doc.pageCount - 1) ?: record.lastPage,
                    placeholderAspect = doc.pageSize(0)?.aspect ?: 1.414f,
                    locked = locked,
                )
            }
        } catch (e: SecurityException) {
            if (!locked && runCatching { com.folio.pdf.pdf.PdfSecurity.needsPassword(appContext, uri) }.getOrDefault(false)) {
                ViewerUiState.NeedsPassword(wrong = false)
            } else {
                ViewerUiState.Error(
                    "Can't open this PDF",
                    "Folio's access to it may have expired. Try opening it again from your files.",
                )
            }
        } catch (e: FileNotFoundException) {
            ViewerUiState.Error("File not found", "It may have been moved, renamed or deleted.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ViewerUiState.Error("Can't open this PDF", "The file looks damaged or isn't a valid PDF.")
        }
    }

    private fun startIndexing(total: Int) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch { index(total) }
    }

    private suspend fun index(total: Int) {
        _index.value = IndexProgress(total = total)
        var done = 0
        var characters = 0L
        var lastEmit = 0L
        try {
            PdfTextExtractor.extract(
                context = appContext,
                uri = workingUri,
                onOutline = { _outline.value = it },
                onAnnotations = { pdf ->
                    val found = runCatching { AnnotationReader.read(pdf, imageDir) { ids.incrementAndGet() } }
                        .getOrDefault(emptyList())
                    _formFields.value = com.folio.pdf.pdf.Forms.countFillable(pdf)
                    viewModelScope.launch {
                        // Never overwrite edits the user has already made.
                        if (!editor.state.value.dirty) editor.load(found)
                        dropStaleSelection()
                        _annotationsReady.value = true
                    }
                },
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
            _annotationsReady.value = true
            _index.value = _index.value.copy(finished = true, failed = true)
        }
    }

    fun pageText(page: Int): PageText? = texts?.let { if (page in 0 until it.length()) it.get(page) else null }

    /** Opens a password-protected PDF for reading. */
    fun unlock(password: String) = viewModelScope.launch {
        _state.value = ViewerUiState.Loading
        try {
            val copy = com.folio.pdf.pdf.PdfSecurity.decryptedCopy(appContext, uri, password)
            unlockedCopy = copy
            workingUri = Uri.fromFile(copy)
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ViewerUiState.NeedsPassword(wrong = true)
        }
    }

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

    // ---- Editing (Phase 3) --------------------------------------------------------

    override fun newId(): Long = ids.incrementAndGet()

    override fun create(a: Annotation) {
        editor.add(a)
        if (a is TextNote || a is ImageStamp) {
            _tools.value = _tools.value.copy(tool = Tool.SELECT)
            _selectedId.value = a.id
        }
    }

    override fun select(id: Long?) { _selectedId.value = id }

    override fun gestureUpdate(a: Annotation) = editor.updateDuringGesture(a)

    override fun gestureRemove(id: Long) {
        if (_selectedId.value == id) _selectedId.value = null
        editor.removeDuringGesture(id)
    }

    override fun gestureEnd() = editor.endGesture()

    fun setTool(tool: Tool) {
        _tools.value = _tools.value.copy(tool = tool)
        if (tool != Tool.SELECT) _selectedId.value = null
    }

    fun setToolColor(color: Int) { _tools.value = _tools.value.withColor(color) }
    fun setStrokeWidth(w: Float) { _tools.value = _tools.value.copy(strokeWidth = w) }
    fun setTextSize(s: Float) { _tools.value = _tools.value.copy(textSize = s) }

    fun undo() { editor.undo(); dropStaleSelection() }
    fun redo() { editor.redo(); dropStaleSelection() }

    private fun dropStaleSelection() {
        if (editor.find(_selectedId.value) == null) _selectedId.value = null
    }

    fun deleteSelected() {
        val id = _selectedId.value ?: return
        _selectedId.value = null
        editor.remove(id)
    }

    fun recolorSelected(color: Int) {
        val a = editor.find(_selectedId.value) ?: return
        editor.update(a.withColor(color))
        _tools.value = _tools.value.let { t ->
            // Remember the colour for next time too.
            when (a) {
                is TextNote -> t.copy(textColor = color)
                is com.folio.pdf.edit.Ink -> t.copy(penColor = color)
                is com.folio.pdf.edit.Shape -> t.copy(shapeColor = color)
                else -> t
            }
        }
    }

    fun addText(page: Int, at: NPoint, text: String) {
        if (text.isBlank()) return
        val size = _tools.value.textSize
        val draft = TextNote(newId(), page, at, text, _tools.value.textColor, size)
        // Keep it on the page.
        val (w, _) = draft.size(pageAspect(page))
        val x = at.x.coerceAtMost((1f - w).coerceAtLeast(0f))
        create(draft.copy(origin = NPoint(x, at.y.coerceIn(0f, 0.97f))))
    }

    fun updateText(id: Long, text: String) {
        val note = editor.find(id) as? TextNote ?: return
        if (text.isBlank()) editor.remove(id) else editor.update(note.copy(text = text))
    }

    fun addImage(page: Int, source: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = com.folio.pdf.util.decodeScaledBitmap(appContext, source, 2048) ?: error("Unreadable image")
                imageDir.mkdirs()
                val id = newId()
                val png = bitmap.hasAlpha()
                val file = File(imageDir, "image-$id." + if (png) "png" else "jpg")
                file.outputStream().use {
                    bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
                }
                Triple(id, file, bitmap.height.toFloat() / bitmap.width)
            }
        }
        result.onSuccess { (id, file, imageAspect) ->
            val aspect = pageAspect(page)
            val w = 0.5f
            val h = (w * imageAspect / aspect).coerceAtMost(0.8f)
            val width = h * aspect / imageAspect
            val left = (1f - width) / 2
            val top = (1f - h) / 2
            create(ImageStamp(id, page, NRect(left, top, left + width, top + h), file.absolutePath, imageAspect))
        }.onFailure {
            _events.send(ViewerEvent.Message("Couldn't add that image"))
        }
    }

    /** Places a saved signature near the bottom of the page, ready to drag into place. */
    fun addSignature(page: Int, signature: File) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                imageDir.mkdirs()
                val id = newId()
                // Copy, so deleting the saved signature later doesn't break this document.
                val copy = File(imageDir, "signature-$id.png")
                signature.copyTo(copy, overwrite = true)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(copy.absolutePath, bounds)
                if (bounds.outWidth <= 0) error("Unreadable signature")
                Triple(id, copy, bounds.outHeight.toFloat() / bounds.outWidth)
            }
        }
        result.onSuccess { (id, file, imageAspect) ->
            val aspect = pageAspect(page)
            val w = 0.34f
            val h = w * imageAspect / aspect
            val left = (1f - w) / 2
            val top = (0.8f - h).coerceAtLeast(0.02f)
            create(ImageStamp(id, page, NRect(left, top, left + w, top + h), file.absolutePath, imageAspect))
        }.onFailure {
            _events.send(ViewerEvent.Message("Couldn't add that signature"))
        }
    }

    private fun pageAspect(page: Int): Float {
        val ready = _state.value as? ViewerUiState.Ready ?: return 1.414f
        return ready.document.cachedAspect(page) ?: ready.placeholderAspect
    }

    /**
     * Saves the annotations into the PDF.
     * [copyTarget] null → overwrite the original; otherwise write a new file there.
     */
    fun save(copyTarget: Uri? = null) = viewModelScope.launch {
        if (_saving.value) return@launch
        if (locked) {
            _events.send(ViewerEvent.Message("Remove the password first (⋮ → Remove a password) to save changes"))
            return@launch
        }
        _saving.value = true
        val annotations = editor.state.value.annotations
        val temp = File(appContext.cacheDir, "folio-save-${System.nanoTime()}.pdf")
        try {
            withContext(Dispatchers.IO) { AnnotationWriter.write(appContext, uri, annotations, temp) }
            if (copyTarget != null) {
                container.documents.overwrite(copyTarget, temp)
                container.documents.persistReadPermission(copyTarget)
                editor.markSaved()
                _events.send(ViewerEvent.OpenCopy(copyTarget))
            } else {
                val page = currentPage.value
                // The renderer has the file open; release it before replacing the bytes.
                indexJob?.cancel()
                document?.close()
                document = null
                try {
                    container.documents.overwrite(uri, temp)
                } catch (e: Exception) {
                    load(page)
                    throw e
                }
                editor.markSaved()
                Thumbnails.invalidate(key)
                load(page)
                _events.send(ViewerEvent.Saved)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _events.send(ViewerEvent.NeedCopy)
        } catch (e: FileNotFoundException) {
            _events.send(ViewerEvent.NeedCopy)
        } catch (e: Exception) {
            _events.send(ViewerEvent.Message("Couldn't save: ${e.message ?: "unknown error"}"))
        } finally {
            temp.delete()
            _saving.value = false
        }
    }

    override fun onCleared() {
        document?.close()
        unlockedCopy?.delete()
    }
}

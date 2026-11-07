package com.folio.pdf.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.FolioApp
import com.folio.pdf.data.ColorMode
import com.folio.pdf.data.ScrollMode
import com.folio.pdf.ui.components.EmptyState
import com.folio.pdf.ui.editor.EditorContent
import com.folio.pdf.ui.editor.UnsavedChangesDialog
import com.folio.pdf.ui.tools.ToolKind
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(
    uri: Uri,
    startAction: String = "",
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    onOpenTool: (ToolKind, Uri) -> Unit,
) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: ViewerViewModel = viewModel(
        key = uri.toString(),
        factory = viewModelFactory { initializer { ViewerViewModel(uri, app, app.container) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    when (val s = state) {
        ViewerUiState.Loading -> SimpleViewerScaffold("Opening…", onBack) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        is ViewerUiState.NeedsPassword -> SimpleViewerScaffold("Password needed", onBack) {
            PasswordPrompt(wrong = s.wrong, onOpen = vm::unlock, modifier = Modifier.align(Alignment.Center))
        }
        is ViewerUiState.Error -> SimpleViewerScaffold("", onBack) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = s.title,
                message = s.message,
                modifier = Modifier.align(Alignment.Center),
            ) { Button(onClick = onBack) { Text("Go back") } }
        }
        is ViewerUiState.Ready -> PdfReader(s, vm, uri, prefs, startAction, onBack, onOpenDocument, onOpenTool)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleViewerScaffold(title: String, onBack: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding), content = content) }
}

@Composable
private fun PdfReader(
    state: ViewerUiState.Ready,
    vm: ViewerViewModel,
    uri: Uri,
    prefs: ReaderPrefs,
    startAction: String,
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    onOpenTool: (ToolKind, Uri) -> Unit,
) {
    val doc = state.document
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val index by vm.index.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val isFavourite by vm.isFavorite.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()
    val editor by vm.editorState.collectAsStateWithLifecycle()
    val annotationsReady by vm.annotationsReady.collectAsStateWithLifecycle()
    val formFields by vm.formFields.collectAsStateWithLifecycle()
    val readAloud by vm.readAloud.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableIntStateOf(state.initialPage) }
    var navigator by remember { mutableStateOf<PageNavigator?>(null) }
    val zoom = remember { ContinuousZoom() }

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var selection by remember { mutableStateOf<PageSelection?>(null) }
    var showGoTo by remember { mutableStateOf(false) }
    var showNav by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var exitAfterSave by remember { mutableStateOf(false) }
    var actionDone by rememberSaveable { mutableStateOf(false) }
    var signOnOpen by rememberSaveable { mutableStateOf(false) }
    var bannerDismissed by rememberSaveable { mutableStateOf(false) }

    // Opened via "Sign a PDF": go straight to the editor with signatures showing.
    LaunchedEffect(annotationsReady) {
        if (startAction == "sign" && annotationsReady && !actionDone) {
            actionDone = true
            signOnOpen = true
            editing = true
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { target -> if (target != null) vm.save(target) else exitAfterSave = false }
    fun saveCopy() = saveCopyLauncher.launch("${state.title} (edited).pdf")

    fun jumpTo(target: Int, yFraction: Float = 0f) {
        scope.launch { navigator?.goTo(target.coerceIn(0, doc.pageCount - 1), yFraction) }
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    fun closeSearch() {
        searching = false
        searchText = ""
        vm.setQuery("")
    }

    ImmersiveEffect(immersive = !chromeVisible && selection == null && !searching && !editing)

    fun requestExit() {
        if (editor.dirty) confirmExit = true else onBack()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ViewerEvent.Message -> { exitAfterSave = false; toast(event.text) }
                ViewerEvent.Saved -> if (exitAfterSave) onBack() else toast("Saved")
                ViewerEvent.NeedCopy -> {
                    toast("Folio can't change the original file, so choose where to save a copy")
                    saveCopy()
                }
                is ViewerEvent.OpenCopy -> onOpenDocument(event.uri)
            }
        }
    }

    // Follow along while reading aloud.
    LaunchedEffect(readAloud.page, readAloud.active) {
        if (readAloud.active && readAloud.page != page) navigator?.goTo(readAloud.page)
    }
    LaunchedEffect(readAloud.error) { readAloud.error?.let { toast(it) } }

    // Follow the current search hit.
    val activeHit = search.currentHit
    LaunchedEffect(activeHit) {
        val hit = activeHit ?: return@LaunchedEffect
        val top = vm.pageText(hit.page)?.rects(hit.range)?.firstOrNull()?.top ?: 0f
        navigator?.goTo(hit.page, top)
    }

    val hitsByPage = remember(search.hits) { search.hits.groupBy { it.page } }
    val env = PageEnv(
        doc = doc,
        colorMode = prefs.colorMode,
        placeholderAspect = state.placeholderAspect,
        textVersion = index.done,
        pageText = vm::pageText,
        hitsByPage = hitsByPage,
        annotationsByPage = remember(editor.annotations) { editor.annotations.groupBy { it.page } },
        activeHit = activeHit,
        selection = selection,
        onSelectionChange = { selection = it },
        onTap = {
            if (selection != null) selection = null else chromeVisible = !chromeVisible
        },
        onNoText = {
            toast(
                when {
                    !index.finished -> "Still reading this document's text, try again in a moment"
                    index.failed -> "Couldn't read the text in this PDF"
                    else -> "No selectable text here. It may be a scan; OCR arrives in Phase 5"
                },
            )
        },
    )
    val onPageChanged: (Int) -> Unit = {
        page = it
        vm.onPageChanged(it)
    }

    BackHandler(enabled = !editing && (selection != null || searching || editor.dirty)) {
        when {
            selection != null -> selection = null
            searching -> closeSearch()
            else -> requestExit()
        }
    }

    if (editing) {
        EditorContent(
            vm = vm,
            doc = doc,
            colorMode = prefs.colorMode,
            startPage = page,
            onDone = { p ->
                editing = false
                signOnOpen = false
                vm.select(null)
                page = p
                jumpTo(p)
                if (editor.dirty) toast("Changes not saved yet. Use Save in the editor, or you'll be asked when you leave")
            },
            onSave = { vm.save() },
            onSaveCopy = { saveCopy() },
            startWithSignature = signOnOpen,
        )
        if (confirmExit) ExitDialog(onSave = { confirmExit = false; exitAfterSave = true; vm.save() }, onDiscard = { confirmExit = false; onBack() }, onCancel = { confirmExit = false })
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (prefs.colorMode == ColorMode.NIGHT) Color(0xFF000000)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        when (prefs.scrollMode) {
            ScrollMode.VERTICAL -> VerticalReader(
                env = env,
                zoom = zoom,
                initialPage = page,
                onNavigator = { navigator = it },
                onPageChanged = onPageChanged,
            )
            ScrollMode.PAGED -> PagedReader(
                env = env,
                initialPage = page,
                onNavigator = { navigator = it },
                onPageChanged = onPageChanged,
            )
            ScrollMode.TEXT -> ReflowReader(
                env = env,
                scale = prefs.reflowScale,
                initialPage = page,
                readingPage = readAloud.page.takeIf { readAloud.active },
                onNavigator = { navigator = it },
                onPageChanged = onPageChanged,
            )
        }

        // ---- Top: reader bar / search bar / selection bar
        AnimatedVisibility(
            visible = chromeVisible || searching || selection != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column {
                val sel = selection
                when {
                    sel != null -> {
                        val selectedText = vm.pageText(sel.page)?.slice(sel.range).orEmpty()
                        SelectionTopBar(
                            preview = selectedText.replace('\n', ' '),
                            onClose = { selection = null },
                            onCopy = {
                                clipboard.setText(AnnotatedString(selectedText))
                                selection = null
                                toast("Copied")
                            },
                            onShare = {
                                shareText(context, selectedText)
                                selection = null
                            },
                            onSearch = {
                                val q = selectedText.replace(Regex("\\s+"), " ").take(120)
                                selection = null
                                searching = true
                                searchText = q
                                vm.setQuery(q)
                            },
                            onSelectAll = {
                                val t = vm.pageText(sel.page)
                                if (t != null && t.text.isNotEmpty()) {
                                    selection = PageSelection(sel.page, 0 until t.text.length)
                                }
                            },
                        )
                    }
                    searching -> {
                        SearchTopBar(
                            query = searchText,
                            onQueryChange = { searchText = it; vm.setQuery(it) },
                            state = search,
                            onPrevious = vm::previousHit,
                            onNext = vm::nextHit,
                            onShowResults = { showResults = true },
                            onClose = ::closeSearch,
                        )
                        SearchStatus(index, search)
                    }
                    else -> Column {
                        ReaderTopBar(
                        title = state.title,
                        bookmarked = bookmarks.any { it.page == page },
                        favourite = isFavourite,
                        onBack = ::requestExit,
                        onSearch = { searching = true },
                        onEdit = {
                            if (state.locked) {
                                toast("This PDF is password-protected. Remove the password first (⋮ → Remove a password) to edit it")
                            } else if (annotationsReady) {
                                selection = null
                                editing = true
                            } else {
                                toast("Getting the editor ready, try again in a moment")
                            }
                        },
                        unsaved = editor.dirty,
                        onToggleBookmark = {
                            val wasBookmarked = bookmarks.any { it.page == page }
                            vm.toggleBookmark(page)
                            toast(if (wasBookmarked) "Bookmark removed" else "Page ${page + 1} bookmarked")
                        },
                        onContents = { showNav = true },
                        onReadingMode = { showModes = true },
                        onReadAloud = { vm.startReading(page) },
                        onGoToPage = { showGoTo = true },
                        onToggleFavourite = {
                            vm.toggleFavorite()
                            toast(if (isFavourite) "Removed from favourites" else "Added to favourites")
                        },
                        onShare = { sharePdf(context, uri, state.title) },
                        tools = if (state.locked) listOf(ToolKind.UNLOCK) else listOf(
                            ToolKind.ASSISTANT, ToolKind.FILL_FORM, ToolKind.DIGITAL_SIGN, ToolKind.OCR, ToolKind.REDACT,
                            ToolKind.PROTECT, ToolKind.METADATA, ToolKind.ORGANIZE, ToolKind.COMPRESS,
                            ToolKind.SPLIT, ToolKind.PDF_TO_IMAGES,
                        ),
                        onTool = { kind ->
                            // Tools work on the saved file, so unsaved annotations must be saved first.
                            if (editor.dirty) toast("Save your edits first (✏️ → 💾), then use ${kind.title}")
                            else onOpenTool(kind, uri)
                        },
                    )
                        // Helpful nudges for scanned PDFs and forms.
                        val scanned = index.finished && !index.failed && !index.hasText
                        if (!bannerDismissed && (scanned || formFields > 0)) {
                            ReaderBanner(
                                text = if (formFields > 0) "This PDF has a form with $formFields fields."
                                else "This looks like a scan, so its text can't be searched yet.",
                                action = if (formFields > 0) "Fill it in" else "Make searchable",
                                onAction = {
                                    val kind = if (formFields > 0) ToolKind.FILL_FORM else ToolKind.OCR
                                    if (editor.dirty) toast("Save your edits first (✏️ → 💾)") else onOpenTool(kind, uri)
                                },
                                onDismiss = { bannerDismissed = true },
                            )
                        }
                    }
                }
            }
        }

        // ---- Bottom: page bar, or a small page pill in full-screen
        if (readAloud.active) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                ReadAloudBar(
                    state = readAloud,
                    onPlayPause = { if (readAloud.speaking) vm.pauseReading() else vm.resumeReading() },
                    onStop = { vm.stopReading() },
                    onRate = { vm.setReadingRate(it) },
                )
            }
        }
        AnimatedVisibility(
            visible = chromeVisible && selection == null && !readAloud.active,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PageBar(
                current = page,
                count = doc.pageCount,
                onSeek = { jumpTo(it) },
                onTapIndicator = { showGoTo = true },
                zoom = if (prefs.scrollMode == ScrollMode.VERTICAL) zoom else null,
            )
        }
        AnimatedVisibility(
            visible = !chromeVisible && selection == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            PagePill(page + 1, doc.pageCount)
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
        )
    }

    // ---- Dialogs & sheets
    if (showGoTo) {
        GoToPageDialog(
            pageCount = doc.pageCount,
            current = page + 1,
            onDismiss = { showGoTo = false },
            onGo = { p ->
                showGoTo = false
                jumpTo(p - 1)
            },
        )
    }
    if (showNav) {
        NavigationSheet(
            doc = doc,
            colorMode = prefs.colorMode,
            currentPage = page,
            outline = outline,
            bookmarks = bookmarks,
            onGoTo = { p ->
                showNav = false
                jumpTo(p)
            },
            onDeleteBookmark = vm::removeBookmark,
            onDismiss = { showNav = false },
        )
    }
    if (showModes) {
        ReadingModeSheet(
            prefs = prefs,
            onScrollMode = vm::setScrollMode,
            onColorMode = vm::setColorMode,
            onKeepScreenOn = vm::setKeepScreenOn,
            onReflowScale = vm::setReflowScale,
            onDismiss = { showModes = false },
        )
    }
    if (confirmExit) {
        ExitDialog(
            onSave = { confirmExit = false; exitAfterSave = true; vm.save() },
            onDiscard = { confirmExit = false; onBack() },
            onCancel = { confirmExit = false },
        )
    }
    if (showResults && search.hits.isNotEmpty()) {
        SearchResultsSheet(
            state = search,
            onSelect = { i ->
                showResults = false
                vm.selectHit(i)
            },
            onDismiss = { showResults = false },
        )
    }
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var password by remember { mutableStateOf("") }
    androidx.compose.foundation.layout.Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp))
        Text("This PDF is password-protected", style = MaterialTheme.typography.titleMedium)
        androidx.compose.material3.OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            isError = wrong,
            supportingText = if (wrong) { { Text("That password isn't right") } } else null,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Go,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (password.isNotEmpty()) onOpen(password) }),
        )
        Button(onClick = { onOpen(password) }, enabled = password.isNotEmpty()) { Text("Open") }
        Text(
            "The password is only used to open the file on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReaderBanner(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
            IconButton(onClick = onDismiss) {
                Icon(androidx.compose.material.icons.Icons.Outlined.Close, "Dismiss")
            }
        }
    }
}

@Composable
private fun ExitDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) =
    UnsavedChangesDialog(onSave = onSave, onDiscard = onDiscard, onCancel = onCancel)

private fun sharePdf(context: Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startChooser(context, send, "Share PDF")
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startChooser(context, send, "Share text")
}

private fun startChooser(context: Context, intent: Intent, title: String) {
    try {
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: ActivityNotFoundException) {
        // Nothing can receive it.
    }
}

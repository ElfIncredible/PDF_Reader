@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.redact

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.FolioApp
import com.folio.pdf.edit.NPoint
import com.folio.pdf.edit.NRect
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.pdf.PdfTextExtractor
import com.folio.pdf.pdf.RedactMark
import com.folio.pdf.pdf.Redaction
import com.folio.pdf.pdf.TextSearch
import com.folio.pdf.ui.tools.Hint
import com.folio.pdf.ui.tools.JobCard
import com.folio.pdf.ui.tools.JobState
import com.folio.pdf.ui.tools.PickPdfFirst
import com.folio.pdf.ui.tools.RunButton
import com.folio.pdf.ui.tools.SwitchRow
import com.folio.pdf.ui.tools.ToolKind
import com.folio.pdf.ui.tools.share
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.roundToInt

class RedactViewModel(private val uri: Uri, private val context: Context, private val container: AppContainer) : ViewModel() {
    private val _doc = MutableStateFlow<PdfFile?>(null)
    val doc: StateFlow<PdfFile?> = _doc.asStateFlow()

    private val _marks = MutableStateFlow<List<RedactMark>>(emptyList())
    val marks: StateFlow<List<RedactMark>> = _marks.asStateFlow()
    private val history = ArrayDeque<List<RedactMark>>()

    private val _textReady = MutableStateFlow(false)
    val textReady: StateFlow<Boolean> = _textReady.asStateFlow()
    private var texts: AtomicReferenceArray<PageText?>? = null

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job.asStateFlow()

    var name: String = "Document"
        private set

    init {
        viewModelScope.launch {
            try {
                container.documents.persistReadPermission(uri)
                name = container.documents.describe(uri).name.substringBeforeLast('.')
                val pdf = PdfFile.open(context, uri)
                _doc.value = pdf
                val arr = AtomicReferenceArray<PageText?>(pdf.pageCount)
                texts = arr
                runCatching { PdfTextExtractor.extract(context, uri, onOutline = {}) { i, t -> if (i < arr.length()) arr.set(i, t) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _job.value = JobState.Failed("Couldn't open this PDF. If it has a password, remove it first.")
            } finally {
                _textReady.value = true
            }
        }
    }

    private fun change(next: List<RedactMark>) {
        history.addLast(_marks.value)
        _marks.value = next
    }

    fun add(mark: RedactMark) = change(_marks.value + mark)
    fun remove(mark: RedactMark) = change(_marks.value - mark)
    fun undo() { history.removeLastOrNull()?.let { _marks.value = it } }
    fun clear() { if (_marks.value.isNotEmpty()) change(emptyList()) }

    /** Marks every place [query] appears. Returns how many were found. */
    fun findAndMark(query: String): Int {
        val pages = texts ?: return 0
        val hits = TextSearch.find(pages, query)
        val found = hits.flatMap { hit ->
            pages.get(hit.page)?.rects(hit.range).orEmpty().map { r ->
                // A little padding so the edges of letters are covered too.
                RedactMark(hit.page, NRect(r.left - 0.002f, r.top - 0.001f, r.right + 0.002f, r.bottom + 0.001f))
            }
        }
        if (found.isNotEmpty()) change(_marks.value + found)
        return hits.size
    }

    fun apply(output: Uri, removeHiddenData: Boolean, keepSearchable: Boolean) {
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(null, "Redacting…")
        viewModelScope.launch {
            val temp = File.createTempFile("folio-redact", ".pdf", context.cacheDir)
            _job.value = try {
                val pages = Redaction.apply(context, uri, _marks.value, removeHiddenData, keepSearchable, temp) { p ->
                    _job.value = JobState.Running(p, "Redacting…")
                }
                container.documents.overwrite(output, temp)
                container.documents.persistReadPermission(output)
                JobState.Done("Redacted ${_marks.value.size} areas on $pages ${if (pages == 1) "page" else "pages"}. The original is unchanged.", listOf(output), openable = output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JobState.Failed(e.message ?: "Couldn't redact")
            } finally {
                temp.delete()
            }
        }
    }

    fun dismiss() { _job.value = JobState.Idle }

    override fun onCleared() { _doc.value?.close() }
}

@Composable
fun RedactScreen(initialUri: Uri?, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    PickPdfFirst(
        initialUri = initialUri,
        kind = ToolKind.REDACT,
        message = "Pick the PDF with information to hide, such as ID numbers, addresses or account details.",
        onBack = onBack,
    ) { uri -> Redactor(uri, onBack, onOpenDocument) }
}

@Composable
private fun Redactor(uri: Uri, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val context = LocalContext.current
    val vm: RedactViewModel = viewModel(
        key = "redact:$uri",
        factory = viewModelFactory { initializer { RedactViewModel(uri, app, app.container) } },
    )
    val doc by vm.doc.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val textReady by vm.textReady.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var finding by remember { mutableStateOf(false) }
    var lastFound by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var removeHidden by rememberSaveable { mutableStateOf(true) }
    var keepSearchable by rememberSaveable { mutableStateOf(true) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out != null) vm.apply(out, removeHidden, keepSearchable)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Redact") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { finding = true }, enabled = textReady) { Icon(Icons.Outlined.Search, "Find and mark") }
                    IconButton(onClick = vm::undo, enabled = marks.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pdf = doc
                    if (pdf != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                            Text("Page ${page + 1} of ${pdf.pageCount}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Text("${marks.size} marked", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            IconButton(onClick = { page++ }, enabled = page < pdf.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
                        }
                    }
                    lastFound?.let { Hint(it) }
                    JobCard(job, onOpen = onOpenDocument, onShare = { share(context, it.outputs, it.mime) }, onCancel = vm::dismiss, onDismiss = vm::dismiss)
                    RunButton("Redact ${marks.size} areas…", enabled = marks.isNotEmpty() && job !is JobState.Running) { confirm = true }
                }
            }
        },
    ) { padding ->
        val pdf = doc
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (pdf == null) {
                if (job !is JobState.Failed) CircularProgressIndicator()
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Hint("Drag a box over anything private. Tap a black box to remove it. 🔍 marks every match of a word or number.")
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        RedactPage(
                            doc = pdf,
                            index = page.coerceIn(0, pdf.pageCount - 1),
                            marks = marks.filter { it.page == page },
                            onAdd = vm::add,
                            onRemove = vm::remove,
                        )
                    }
                }
            }
        }
    }

    if (finding) {
        FindDialog(
            onDismiss = { finding = false },
            onFind = { q ->
                finding = false
                val n = vm.findAndMark(q)
                lastFound = if (n == 0) "No matches for \"$q\"" else "Marked $n matches of \"$q\". Check each page before redacting."
            },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Redact for good?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Marked areas are permanently removed in a new copy, not just covered. Pages with marks become images, so their text is gone too.")
                    SwitchRow("Keep the rest searchable (OCR)", keepSearchable) { keepSearchable = it }
                    SwitchRow("Also remove hidden data (author, metadata, scripts, attachments)", removeHidden) { removeHidden = it }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; save.launch("${vm.name} (redacted).pdf") }) { Text("Redact and save copy") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RedactPage(
    doc: PdfFile,
    index: Int,
    marks: List<RedactMark>,
    onAdd: (RedactMark) -> Unit,
    onRemove: (RedactMark) -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { (LocalConfiguration.current.screenWidthDp.dp * 2).toPx() }.roundToInt().coerceAtMost(3000)
    var bitmap by remember(doc, index) { mutableStateOf<Bitmap?>(doc.cachedPage(index, widthPx)) }
    var aspect by remember(doc, index) { mutableFloatStateOf(1.414f) }
    LaunchedEffect(doc, index) {
        doc.pageSize(index)?.let { aspect = it.aspect }
        doc.renderPage(index, widthPx)?.let { bitmap = it }
    }
    var drag by remember { mutableStateOf<Pair<NPoint, NPoint>?>(null) }
    val latestMarks by rememberUpdatedState(marks)

    Surface(
        modifier = Modifier.aspectRatio(1f / aspect, matchHeightConstraintsFirst = true),
        color = Color.White,
        shadowElevation = 3.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            bitmap?.let { Image(it.asImageBitmap(), "Page ${index + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            Canvas(
                Modifier.fillMaxSize().pointerInput(index) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun n(o: Offset) = NPoint((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                        val start = n(down.position)
                        var end = start
                        var moved = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (c.positionChanged()) {
                                end = n(c.position)
                                moved = true
                                drag = start to end
                                c.consume()
                            }
                        }
                        drag = null
                        val r = NRect.of(start, end)
                        if (moved && r.width > 0.01f && r.height > 0.005f) {
                            onAdd(RedactMark(index, r))
                        } else if (!moved) {
                            latestMarks.lastOrNull { it.rect.contains(start) }?.let(onRemove)
                        }
                    }
                },
            ) {
                for (m in marks) {
                    drawRect(
                        Color.Black.copy(alpha = 0.85f),
                        Offset(m.rect.left * size.width, m.rect.top * size.height),
                        Size(m.rect.width * size.width, m.rect.height * size.height),
                    )
                }
                drag?.let { (a, b) ->
                    val r = NRect.of(a, b)
                    val tl = Offset(r.left * size.width, r.top * size.height)
                    val sz = Size(r.width * size.width, r.height * size.height)
                    drawRect(Color.Black.copy(alpha = 0.4f), tl, sz)
                    drawRect(Color.Red, tl, sz, style = Stroke(width = 3f))
                }
            }
        }
    }
}

@Composable
private fun FindDialog(onDismiss: () -> Unit, onFind: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find and mark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Word, name or number") }, singleLine = true)
                Hint("Every match in the document is marked for redaction. Only works where the PDF has text (OCR scans first).")
            }
        },
        confirmButton = { TextButton(onClick = { onFind(q.trim()) }, enabled = q.isNotBlank()) { Text("Mark all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.compare

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.FolioApp
import com.folio.pdf.compare.CompareResult
import com.folio.pdf.compare.DiffOp
import com.folio.pdf.compare.PageDiff
import com.folio.pdf.compare.PdfCompare
import com.folio.pdf.data.DocumentInfo
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.ui.tools.Hint
import com.folio.pdf.ui.tools.RunButton
import com.folio.pdf.ui.tools.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val removedColor = Color(0x66E53935)
private val addedColor = Color(0x6643A047)

class CompareViewModel(private val context: Context, private val container: AppContainer) : ViewModel() {
    val a = MutableStateFlow<DocumentInfo?>(null)
    val b = MutableStateFlow<DocumentInfo?>(null)
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()
    private val _result = MutableStateFlow<CompareResult?>(null)
    val result: StateFlow<CompareResult?> = _result.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    val docA = MutableStateFlow<PdfFile?>(null)
    val docB = MutableStateFlow<PdfFile?>(null)

    fun set(first: Boolean, uri: Uri) = viewModelScope.launch {
        container.documents.persistReadPermission(uri)
        val info = container.documents.describe(uri)
        if (first) a.value = info else b.value = info
        _result.value = null
    }

    fun compare() {
        val ua = a.value?.uri ?: return
        val ub = b.value?.uri ?: return
        _error.value = null
        _progress.value = 0f
        viewModelScope.launch {
            try {
                _result.value = PdfCompare.compare(context, ua, ub) { _progress.value = it }
                docA.value?.close(); docB.value?.close()
                docA.value = runCatching { PdfFile.open(context, ua) }.getOrNull()
                docB.value = runCatching { PdfFile.open(context, ub) }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't compare: ${e.message ?: "one of the files can't be read (password?)"}"
            } finally {
                _progress.value = null
            }
        }
    }

    override fun onCleared() {
        docA.value?.close(); docB.value?.close()
    }
}

@Composable
fun CompareScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: CompareViewModel = viewModel(factory = viewModelFactory { initializer { CompareViewModel(app, app.container) } })
    val a by vm.a.collectAsStateWithLifecycle()
    val b by vm.b.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val docA by vm.docA.collectAsStateWithLifecycle()
    val docB by vm.docB.collectAsStateWithLifecycle()

    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.set(true, u) } }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.set(false, u) } }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var showPages by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare PDFs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("Files") {
                    PickRow("Original", a?.name, onPick = { pickA.launch(arrayOf("application/pdf")) })
                    PickRow("Changed version", b?.name, onPick = { pickB.launch(arrayOf("application/pdf")) })
                    RunButton(
                        text = if (progress != null) "Comparing… ${((progress ?: 0f) * 100).toInt()}%" else "Compare",
                        enabled = a != null && b != null && progress == null,
                        onClick = { selected = -1; vm.compare() },
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            val r = result
            if (r != null) {
                item {
                    val summary = when {
                        !r.aHasText || !r.bHasText -> "One of these PDFs has no text (a scan?). Make it searchable first (Tools → Make PDF searchable), then compare."
                        r.totalChanges == 0 && r.pagesA == r.pagesB -> "No text differences found."
                        else -> "${r.totalChanges} changes on ${r.changedPages.size} pages" +
                            if (r.pagesA != r.pagesB) " · page count ${r.pagesA} → ${r.pagesB}" else ""
                    }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text(summary, modifier = Modifier.padding(12.dp))
                    }
                }
                if (r.changedPages.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(r.changedPages) { pd ->
                                FilterChip(
                                    selected = selected == pd.page,
                                    onClick = { selected = pd.page },
                                    label = { Text("Page ${pd.page + 1} · ${pd.changes}") },
                                )
                            }
                        }
                    }
                    val current = r.diffs.getOrNull(selected) ?: r.changedPages.first()
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(false to "Text", true to "Pages").forEachIndexed { i, (pages, label) ->
                                SegmentedButton(
                                    selected = showPages == pages,
                                    onClick = { showPages = pages },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(label) }
                            }
                        }
                    }
                    item {
                        if (!showPages) {
                            DiffText(current)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Hint("Red = removed from the original, green = added in the changed version.")
                                ComparePage("Original", docA, current.page, r.textA.getOrNull(current.page), current, original = true)
                                ComparePage("Changed version", docB, current.page, r.textB.getOrNull(current.page), current, original = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(label: String, name: String?, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(name ?: "Not chosen", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onPick) { Text(if (name == null) "Choose" else "Change") }
    }
}

@Composable
private fun DiffText(page: PageDiff) {
    val text = remember(page) {
        buildAnnotatedString {
            page.pieces.forEach { p ->
                when (p.op) {
                    DiffOp.SAME -> append(p.text)
                    DiffOp.REMOVED -> withStyle(SpanStyle(background = removedColor, textDecoration = TextDecoration.LineThrough)) { append(p.text) }
                    DiffOp.ADDED -> withStyle(SpanStyle(background = addedColor)) { append(p.text) }
                }
                append(' ')
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun ComparePage(title: String, doc: PdfFile?, page: Int, text: PageText?, diff: PageDiff, original: Boolean) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    if (doc == null || page >= doc.pageCount) {
        Hint(if (doc == null) "Preview unavailable" else "This version has no page ${page + 1}.")
        return
    }
    var bitmap by remember(doc, page) { mutableStateOf<Bitmap?>(null) }
    var aspect by remember(doc, page) { mutableFloatStateOf(1.414f) }
    LaunchedEffect(doc, page) {
        doc.pageSize(page)?.let { aspect = it.aspect }
        bitmap = doc.renderPage(page, 1200)
    }
    val rects = remember(diff, text, original) {
        if (text == null) emptyList() else diff.pieces.flatMap { p ->
            val range = if (original) p.aRange.takeIf { p.op == DiffOp.REMOVED } else p.bRange.takeIf { p.op == DiffOp.ADDED }
            range?.let { text.rects(it) }.orEmpty()
        }
    }
    Surface(color = Color.White, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f / aspect)) {
        Box {
            bitmap?.let { Image(it.asImageBitmap(), title, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            Canvas(Modifier.fillMaxSize()) {
                rects.forEach { r ->
                    drawRect(
                        if (original) removedColor else addedColor,
                        Offset(r.left * size.width, r.top * size.height),
                        Size(r.width * size.width, r.height * size.height),
                    )
                }
            }
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.FolioApp
import com.folio.pdf.pdf.CompressionLevel
import com.folio.pdf.pdf.ImageFormat
import com.folio.pdf.pdf.PageRanges
import com.folio.pdf.pdf.PaperSize
import com.folio.pdf.ui.components.PdfThumbnail
import com.folio.pdf.util.formatFileSize

private const val PDF = "application/pdf"

/** One tool's screen. Organize pages has its own screen (see OrganizeScreen). */
@Composable
fun ToolScreen(
    kind: ToolKind,
    initialUri: Uri?,
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: ToolViewModel = viewModel(
        key = "${kind.id}:$initialUri",
        factory = viewModelFactory { initializer { ToolViewModel(kind, initialUri, app, app.container) } },
    )
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val busy = job is JobState.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                kind.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (kind) {
                ToolKind.MERGE -> MergeContent(vm, inputs, busy)
                ToolKind.SPLIT -> SplitContent(vm, inputs, busy)
                ToolKind.COMPRESS -> CompressContent(vm, inputs, busy)
                ToolKind.IMAGES_TO_PDF -> ImagesContent(vm, inputs, busy)
                ToolKind.PDF_TO_IMAGES -> PdfToImagesContent(vm, inputs, busy)
                ToolKind.TEXT_TO_PDF -> TextContent(vm, inputs, busy)
                ToolKind.ORGANIZE -> Unit
            }
            JobCard(
                job = job,
                onOpen = onOpenDocument,
                onShare = { done -> share(context, done.outputs, done.mime) },
                onCancel = vm::cancel,
                onDismiss = vm::reset,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- Merge ---------------------------------------------------------------------

@Composable
private fun MergeContent(vm: ToolViewModel, inputs: List<ToolInput>, busy: Boolean) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF)) { it?.let(vm::merge) }

    Section("Files, in order") {
        if (inputs.isEmpty()) Hint("Add two or more PDFs. Use the arrows to change their order.")
        inputs.forEachIndexed { i, input ->
            InputRow(input, onRemove = { vm.remove(i) }, onUp = { vm.move(i, -1) }.takeIf { i > 0 },
                onDown = { vm.move(i, +1) }.takeIf { i < inputs.size - 1 })
        }
        OutlinedButton(onClick = { pick.launch(arrayOf(PDF)) }, enabled = !busy) {
            Icon(Icons.Outlined.Add, null)
            Text("Add PDFs", Modifier.padding(start = 6.dp))
        }
    }
    val ready = inputs.size >= 2 && inputs.none { it.error != null }
    val total = inputs.sumOf { it.pages ?: 0 }
    RunButton(
        text = if (inputs.size >= 2) "Merge ${inputs.size} files ($total pages)" else "Merge",
        enabled = ready && !busy,
        onClick = { save.launch("Merged.pdf") },
    )
}

// ---- Split ---------------------------------------------------------------------

private enum class SplitMode(val label: String) { EACH("Every page"), EVERY_N("Every N pages"), CUSTOM("Custom") }

@Composable
private fun SplitContent(vm: ToolViewModel, inputs: List<ToolInput>, busy: Boolean) {
    val input = inputs.firstOrNull()
    var mode by rememberSaveable { mutableStateOf(SplitMode.EACH) }
    var everyN by rememberSaveable { mutableStateOf("2") }
    var custom by rememberSaveable { mutableStateOf("") }

    val pages = input?.pages
    val plan: PageRanges.Parsed? = pages?.let { count ->
        when (mode) {
            SplitMode.EACH -> PageRanges.Parsed.Ok(PageRanges.everyN(1, count))
            SplitMode.EVERY_N -> everyN.toIntOrNull()?.takeIf { it > 0 }
                ?.let { PageRanges.Parsed.Ok(PageRanges.everyN(it, count)) }
                ?: PageRanges.Parsed.Error("Enter how many pages go in each file")
            SplitMode.CUSTOM -> PageRanges.parse(custom, count)
        }
    }
    val latestPlan by rememberUpdatedState(plan)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val p = latestPlan
        if (tree != null && p is PageRanges.Parsed.Ok) vm.split(p.ranges, tree)
    }

    SinglePdfPicker(vm, input, busy)
    if (pages != null) {
        Section("How to split") {
            Choice(SplitMode.entries, mode, { it.label }) { mode = it }
            when (mode) {
                SplitMode.EVERY_N -> OutlinedTextField(
                    value = everyN,
                    onValueChange = { v -> everyN = v.filter(Char::isDigit).take(4) },
                    label = { Text("Pages per file") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SplitMode.CUSTOM -> OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Page ranges") },
                    placeholder = { Text("e.g. 1-3, 4-10, 11-") },
                    supportingText = { Text("Each range becomes its own PDF. \"11-\" means page 11 to the end.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SplitMode.EACH -> Unit
            }
            when (plan) {
                is PageRanges.Parsed.Ok -> {
                    val labels = plan.ranges.take(8).joinToString(", ") { PageRanges.label(it) }
                    Hint("Creates ${plan.ranges.size} PDFs: $labels${if (plan.ranges.size > 8) ", …" else ""}")
                }
                is PageRanges.Parsed.Error -> if (mode != SplitMode.CUSTOM || custom.isNotBlank()) ErrorText(plan.message)
                null -> Unit
            }
        }
    }
    RunButton(
        text = "Choose a folder and split",
        enabled = plan is PageRanges.Parsed.Ok && !busy,
        onClick = { folder.launch(null) },
    )
}

// ---- Compress ------------------------------------------------------------------

@Composable
private fun CompressContent(vm: ToolViewModel, inputs: List<ToolInput>, busy: Boolean) {
    val input = inputs.firstOrNull()
    var level by rememberSaveable { mutableStateOf(CompressionLevel.MEDIUM) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF)) { uri ->
        uri?.let { vm.compress(level, it) }
    }
    SinglePdfPicker(vm, input, busy)
    Section("Compression") {
        Choice(CompressionLevel.entries, level, { it.label }) { level = it }
        Hint(level.detail + ". Only pictures are compressed; text and drawings stay sharp.")
    }
    RunButton(
        text = "Compress",
        enabled = input != null && input.error == null && input.pages != null && !busy,
        onClick = { save.launch("${vm.baseName()} (compressed).pdf") },
    )
}

// ---- Images → PDF --------------------------------------------------------------

private val margins = listOf("None" to 0f, "Small" to 18f, "Normal" to 36f)

@Composable
private fun ImagesContent(vm: ToolViewModel, inputs: List<ToolInput>, busy: Boolean) {
    var paper by rememberSaveable { mutableStateOf(PaperSize.A4) }
    var margin by rememberSaveable { mutableStateOf(margins[1].second) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { vm.add(it) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF)) { uri ->
        uri?.let { vm.imagesToPdf(paper, margin, it) }
    }

    Section("Images, in page order") {
        if (inputs.isEmpty()) Hint("Each image becomes one page. Photos are turned upright automatically.")
        inputs.forEachIndexed { i, input ->
            InputRow(input, icon = Icons.Outlined.Image, onRemove = { vm.remove(i) },
                onUp = { vm.move(i, -1) }.takeIf { i > 0 },
                onDown = { vm.move(i, +1) }.takeIf { i < inputs.size - 1 })
        }
        OutlinedButton(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !busy,
        ) {
            Icon(Icons.Outlined.Add, null)
            Text("Add images", Modifier.padding(start = 6.dp))
        }
    }
    Section("Page size") { Choice(PaperSize.entries, paper, { it.label }) { paper = it } }
    Section("Margins") {
        Choice(margins.map { it.second }, margin, { m -> margins.first { it.second == m }.first }) { margin = it }
    }
    RunButton(
        text = if (inputs.isEmpty()) "Create PDF" else "Create ${inputs.size}-page PDF",
        enabled = inputs.isNotEmpty() && !busy,
        onClick = { save.launch("Images.pdf") },
    )
}

// ---- PDF → images --------------------------------------------------------------

private val qualities = listOf(96 to "Screen", 150 to "Standard", 300 to "Print")

@Composable
private fun PdfToImagesContent(vm: ToolViewModel, inputs: List<ToolInput>, busy: Boolean) {
    val input = inputs.firstOrNull()
    var format by rememberSaveable { mutableStateOf(ImageFormat.JPEG) }
    var dpi by rememberSaveable { mutableStateOf(150) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { vm.pdfToImages(format, dpi, it) }
    }
    SinglePdfPicker(vm, input, busy)
    Section("Format") {
        Choice(ImageFormat.entries, format, { it.label }) { format = it }
        Hint(if (format == ImageFormat.JPEG) "JPG: smaller files, best for photos." else "PNG: sharper text, bigger files.")
    }
    Section("Quality") {
        Choice(qualities.map { it.first }, dpi, { d -> qualities.first { it.first == d }.second }) { dpi = it }
        Hint("$dpi dots per inch")
    }
    RunButton(
        text = input?.pages?.let { "Choose a folder and save $it images" } ?: "Save images",
        enabled = input != null && input.error == null && input.pages != null && !busy,
        onClick = { folder.launch(null) },
    )
}

// ---- Text → PDF ----------------------------------------------------------------

private val textSizes = listOf(10f to "Small", 12f to "Medium", 14f to "Large")

@Composable
private fun TextContent(vm: ToolViewModel, inputs: List<ToolInput>, busy: Boolean) {
    val input = inputs.firstOrNull()
    var size by rememberSaveable { mutableStateOf(12f) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.add(listOf(u)) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF)) { uri ->
        uri?.let { vm.textToPdf(size, it) }
    }
    Section("Text file") {
        if (input != null) InputRow(input, icon = Icons.Outlined.Description, onRemove = { vm.remove(0) })
        OutlinedButton(onClick = { pick.launch(arrayOf("text/plain", "text/*")) }, enabled = !busy) {
            Text(if (input == null) "Choose a .txt file" else "Choose a different file")
        }
        Hint("Word, Excel and PowerPoint files need a conversion server; that arrives with the Folio server in Phase 7.")
    }
    Section("Text size") { Choice(textSizes.map { it.first }, size, { s -> textSizes.first { it.first == s }.second }) { size = it } }
    RunButton(
        text = "Convert to PDF",
        enabled = input != null && !busy,
        onClick = { save.launch("${vm.baseName()}.pdf") },
    )
}

// ---- Shared pieces -------------------------------------------------------------

@Composable
private fun SinglePdfPicker(vm: ToolViewModel, input: ToolInput?, busy: Boolean) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.add(listOf(u)) } }
    Section("PDF") {
        if (input != null) InputRow(input, onRemove = { vm.remove(0) })
        OutlinedButton(onClick = { pick.launch(arrayOf(PDF)) }, enabled = !busy) {
            Text(if (input == null) "Choose a PDF" else "Choose a different PDF")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun InputRow(
    input: ToolInput,
    onRemove: () -> Unit,
    icon: ImageVector = Icons.Outlined.PictureAsPdf,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon == Icons.Outlined.PictureAsPdf && input.error == null) {
                PdfThumbnail(input.info.uri.toString())
            } else {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(input.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                when {
                    input.error != null -> Text(input.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Text(
                        listOfNotNull(
                            input.pages?.let { "$it pages" },
                            formatFileSize(input.info.sizeBytes).ifEmpty { null },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onUp != null) IconButton(onClick = onUp) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
            if (onDown != null) IconButton(onClick = onDown) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove") }
        }
    }
}

@Composable
private fun RunButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(text) }
}

@Composable
private fun JobCard(
    job: JobState,
    onOpen: (Uri) -> Unit,
    onShare: (JobState.Done) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (job) {
        JobState.Idle -> Unit
        is JobState.Running -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(job.label, style = MaterialTheme.typography.titleSmall)
                if (job.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Keep Folio open until this finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        is JobState.Done -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Done", style = MaterialTheme.typography.titleMedium)
                }
                Text(job.summary, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    job.openable?.let { uri -> Button(onClick = { onOpen(uri) }) { Text("Open") } }
                    OutlinedButton(onClick = { onShare(job) }) { Text("Share") }
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            }
        }
        is JobState.Failed -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.message, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            }
        }
    }
}

private fun share(context: Context, uris: List<Uri>, mime: String) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }
    intent.type = mime
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Share"))
    } catch (_: ActivityNotFoundException) {
    }
}

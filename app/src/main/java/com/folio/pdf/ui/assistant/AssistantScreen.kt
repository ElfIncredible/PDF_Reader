@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.assistant

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.FolioApp
import com.folio.pdf.data.AppSettings
import com.folio.pdf.net.ExtractedTable
import com.folio.pdf.translate.Translator
import com.folio.pdf.ui.tools.Choice
import com.folio.pdf.ui.tools.Hint
import com.folio.pdf.ui.tools.PickPdfFirst
import com.folio.pdf.ui.tools.RunButton
import com.folio.pdf.ui.tools.Section
import com.folio.pdf.ui.tools.SwitchRow
import com.folio.pdf.ui.tools.ToolKind

private enum class AssistantTab(val label: String) { SUMMARY("Summary"), ASK("Ask"), TABLES("Tables"), TRANSLATE("Translate") }

@Composable
fun AssistantScreen(initialUri: Uri?, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    PickPdfFirst(
        initialUri = initialUri,
        kind = ToolKind.ASSISTANT,
        message = "Pick a PDF to summarise, ask questions about, pull tables out of, or translate.",
        onBack = onBack,
    ) { uri -> Assistant(uri, onBack, onOpenSettings) }
}

@Composable
private fun Assistant(uri: Uri, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: AssistantViewModel = viewModel(
        key = "assistant:$uri",
        factory = viewModelFactory { initializer { AssistantViewModel(uri, app, app.container) } },
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AssistantTab.SUMMARY) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("AI assistant")
                            Text(vm.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Server settings") } },
                )
                TabRow(selectedTabIndex = tab.ordinal) {
                    AssistantTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                pages == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Hint("Reading the document's text…")
                }
                !vm.hasText -> Box(Modifier.padding(24.dp)) {
                    Hint("This PDF has no text yet, which usually means it's a scan. Use Tools → Make PDF searchable first, then come back.")
                }
                tab == AssistantTab.TRANSLATE -> TranslateTab(vm, pages!!.size)
                else -> ServerGate(settings, onOpenSettings, onAllow = vm::allowServer) {
                    when (tab) {
                        AssistantTab.SUMMARY -> SummaryTab(vm)
                        AssistantTab.ASK -> AskTab(vm)
                        AssistantTab.TABLES -> TablesTab(vm)
                        AssistantTab.TRANSLATE -> Unit
                    }
                }
            }
        }
    }
}

/** Shows [content] only once a server is set up and the user agreed to send documents to it. */
@Composable
private fun ServerGate(settings: AppSettings, onOpenSettings: () -> Unit, onAllow: () -> Unit, content: @Composable () -> Unit) {
    when {
        settings.serverUrl.isBlank() -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect your Folio server", style = MaterialTheme.typography.titleMedium)
            Hint(
                "Summaries, questions and tables run on a small Python server you control (it's in the folio-server folder of the project). " +
                    "It can use a free AI model on your own computer, so your documents stay with you.",
            )
            Button(onClick = onOpenSettings) { Text("Set up in Settings") }
            Hint("Translation works without a server; try the Translate tab.")
        }
        !settings.serverConsent -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send this document to your server?", style = MaterialTheme.typography.titleMedium)
            Hint(
                "To summarise or answer questions, Folio sends the document's text to ${settings.serverUrl}. For tables it sends the file. " +
                    "If your server uses an online AI service (not a local model), that service will see the text too.",
            )
            Button(onClick = onAllow) { Text("Allow") }
            Hint("You can turn this off any time in Settings.")
        }
        else -> content()
    }
}

// ---- Summary -----------------------------------------------------------------------

@Composable
private fun SummaryTab(vm: AssistantViewModel) {
    val state by vm.summary.collectAsStateWithLifecycle()
    var length by rememberSaveable { mutableStateOf("medium") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("Length") {
            Choice(listOf("short", "medium", "long"), length, { it.replaceFirstChar { c -> c.uppercase() } }) { length = it }
        }
        RunButton(if (state is Ai.Done) "Summarise again" else "Summarise", enabled = state !is Ai.Running) { vm.summarize(length) }
        when (val s = state) {
            is Ai.Running -> Progress(s)
            is Ai.Failed -> ErrorCard(s.message)
            is Ai.Done -> {
                val text = buildString {
                    append(s.value.summary)
                    if (s.value.points.isNotEmpty()) {
                        append("\n\nKey points:\n")
                        s.value.points.forEach { append("• ").append(it).append('\n') }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(s.value.summary, style = MaterialTheme.typography.bodyLarge)
                        if (s.value.points.isNotEmpty()) {
                            Text("Key points", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            s.value.points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                                Icon(Icons.Outlined.ContentCopy, null); Text("Copy", Modifier.padding(start = 4.dp))
                            }
                            TextButton(onClick = { shareText(context, text) }) {
                                Icon(Icons.Outlined.Share, null); Text("Share", Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
                Hint("AI summaries can miss or misread things. Check important details on the cited pages.")
            }
            Ai.Idle -> Hint("Get the gist of the document, with key points and the pages they come from.")
        }
    }
}

// ---- Ask ---------------------------------------------------------------------------

@Composable
private fun AskTab(vm: AssistantViewModel) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val asking by vm.asking.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (chat.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Ask anything about this document. Answers come only from its text, with page numbers.")
                    listOf("What is this document about?", "What are the key dates and deadlines?", "What do I have to pay, and when?")
                        .forEach { s -> AssistChip(onClick = { vm.ask(s) }, label = { Text(s) }) }
                }
            }
            items(chat) { turn -> ChatBubble(turn) }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("Ask about this PDF") },
                    modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.ask(question); question = "" }),
                )
                if (asking) {
                    CircularProgressIndicator(Modifier.padding(12.dp))
                } else {
                    IconButton(onClick = { vm.ask(question); question = "" }, enabled = question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Ask")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
        ) { Text(turn.question, Modifier.padding(12.dp)) }
        when {
            turn.error != null -> ErrorCard(turn.error)
            turn.answer == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).heightIn(max = 20.dp), strokeWidth = 2.dp)
                Hint("Thinking…")
            }
            else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(turn.answer)
                    turn.citations.forEach { c ->
                        Text("p. ${c.page}: “${c.snippet.take(140)}…”", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---- Tables ------------------------------------------------------------------------

@Composable
private fun TablesTab(vm: AssistantViewModel) {
    val state by vm.tables.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<ExtractedTable?>(null) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { out ->
        val t = pending
        if (out != null && t != null) vm.saveCsv(t, out)
    }
    val xlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { out -> out?.let(vm::saveXlsx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Hint("Finds tables (invoices, statements, price lists…) and saves them for Excel or Google Sheets. Works best on PDFs made by computer; scanned tables may come out messy.")
        RunButton(if (state is Ai.Done) "Look again" else "Find tables", enabled = state !is Ai.Running) { vm.findTables() }
        when (val s = state) {
            is Ai.Running -> Progress(s)
            is Ai.Failed -> ErrorCard(s.message)
            is Ai.Done -> {
                if (s.value.isEmpty()) {
                    Hint("No tables found.")
                } else {
                    OutlinedButton(onClick = { xlsx.launch("${vm.title} tables.xlsx") }) { Text("Save all ${s.value.size} as Excel") }
                    s.value.forEach { t ->
                        TablePreview(t, onSaveCsv = {
                            pending = t
                            csv.launch("${vm.title} p${t.page} table ${t.index}.csv")
                        })
                    }
                }
            }
            Ai.Idle -> Unit
        }
    }
}

@Composable
private fun TablePreview(table: ExtractedTable, onSaveCsv: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Page ${table.page} · ${table.rows.size} rows", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onSaveCsv) { Text("Save CSV") }
            }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                table.rows.take(6).forEachIndexed { r, row ->
                    Row {
                        row.forEach { cell ->
                            Text(
                                cell,
                                modifier = Modifier.widthIn(min = 72.dp, max = 160.dp).padding(4.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (table.rows.size > 6) Hint("… ${table.rows.size - 6} more rows")
            }
        }
    }
}

// ---- Translate (on the phone) -------------------------------------------------------------

@Composable
private fun TranslateTab(vm: AssistantViewModel, pageCount: Int) {
    val state by vm.translation.collectAsStateWithLifecycle()
    var target by rememberSaveable { mutableStateOf(Translator.defaultTarget()) }
    var whole by rememberSaveable { mutableStateOf(false) }
    var pageText by rememberSaveable { mutableStateOf("1") }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        out?.let(vm::saveTranslation)
    }
    val page = pageText.toIntOrNull()?.takeIf { it in 1..pageCount }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Hint("Translates on your phone with Google's offline models. Nothing is uploaded. Each language is downloaded once (about 30 MB).")
        Section("Translate into") {
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(Translator.languages.firstOrNull { it.code == target }?.name ?: target, modifier = Modifier.weight(1f))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                    Translator.languages.forEach { l ->
                        DropdownMenuItem(text = { Text(l.name) }, onClick = { target = l.code; menu = false })
                    }
                }
            }
        }
        Section("What to translate") {
            SwitchRow("Whole document (up to 30 pages)", whole) { whole = it }
            if (!whole) {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Page (1–$pageCount)") },
                    singleLine = true,
                    isError = page == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            SwitchRow("Download language packs on Wi-Fi only", wifiOnly) { wifiOnly = it }
        }
        RunButton("Translate", enabled = state !is Ai.Running && (whole || page != null)) {
            vm.translate(target, if (whole) null else page!! - 1, wifiOnly)
        }
        when (val s = state) {
            is Ai.Running -> Progress(s)
            is Ai.Failed -> ErrorCard(s.message)
            is Ai.Done -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint("${langName(s.value.from)} → ${langName(s.value.to)} · ${s.value.pages}")
                    Text(s.value.text, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(s.value.text)) }) { Text("Copy") }
                        TextButton(onClick = { shareText(context, s.value.text) }) { Text("Share") }
                        TextButton(onClick = { save.launch("${vm.title} (${s.value.to}).pdf") }) { Text("Save as PDF") }
                    }
                    Hint("Machine translation: good for understanding, but check anything important with a person.")
                }
            }
            Ai.Idle -> Unit
        }
    }
}

private fun langName(code: String) = Translator.languages.firstOrNull { it.code == code }?.name ?: code

// ---- Shared ----------------------------------------------------------------------------

@Composable
private fun Progress(s: Ai.Running) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
        if (s.label.isNotBlank()) Hint(s.label)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
    }
}

private fun shareText(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}

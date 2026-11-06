@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.metadata

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.FolioApp
import com.folio.pdf.pdf.CheckStatus
import com.folio.pdf.pdf.Metadata
import com.folio.pdf.pdf.PdfMetadata
import com.folio.pdf.pdf.PdfaCheck
import com.folio.pdf.ui.tools.Hint
import com.folio.pdf.ui.tools.JobCard
import com.folio.pdf.ui.tools.JobState
import com.folio.pdf.ui.tools.PickPdfFirst
import com.folio.pdf.ui.tools.RunButton
import com.folio.pdf.ui.tools.Section
import com.folio.pdf.ui.tools.ToolKind
import com.folio.pdf.ui.tools.share
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

class MetadataViewModel(private val uri: Uri, private val context: Context, private val container: AppContainer) : ViewModel() {
    private val _meta = MutableStateFlow<PdfMetadata?>(null)
    val meta: StateFlow<PdfMetadata?> = _meta.asStateFlow()
    private val _checks = MutableStateFlow<List<PdfaCheck>?>(null)
    val checks: StateFlow<List<PdfaCheck>?> = _checks.asStateFlow()
    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job.asStateFlow()
    var name = "Document"
        private set

    init {
        viewModelScope.launch {
            try {
                container.documents.persistReadPermission(uri)
                name = container.documents.describe(uri).name.substringBeforeLast('.')
                _meta.value = Metadata.read(context, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _job.value = JobState.Failed(e.message ?: "Couldn't read this PDF")
            }
        }
    }

    fun check() = viewModelScope.launch {
        _checks.value = emptyList()
        _checks.value = runCatching { Metadata.pdfaCheck(context, uri) }.getOrElse {
            listOf(PdfaCheck("Check", CheckStatus.FAIL, it.message ?: "Couldn't check this PDF"))
        }
    }

    private fun write(output: Uri, label: String, done: String, work: suspend (File) -> Unit) {
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(null, label)
        viewModelScope.launch {
            val temp = File.createTempFile("folio-meta", ".pdf", context.cacheDir)
            _job.value = try {
                work(temp)
                container.documents.overwrite(output, temp)
                container.documents.persistReadPermission(output)
                JobState.Done(done, listOf(output), openable = output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JobState.Failed(e.message ?: "Couldn't save")
            } finally {
                temp.delete()
            }
        }
    }

    fun save(m: PdfMetadata, output: Uri) = write(output, "Saving…", "Saved the new document info in a copy") { Metadata.write(context, uri, m, it) }

    fun clean(output: Uri) = write(output, "Removing hidden data…", "Saved a clean copy: no metadata, scripts or attachments") {
        Metadata.removeHiddenData(context, uri, it)
    }

    /** Converts to PDF/A-2b on the Folio server (Ghostscript; veraPDF validation if installed). */
    fun convertToPdfa(output: Uri) {
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(null, "Converting on your server…")
        viewModelScope.launch {
            _job.value = try {
                val settings = container.settings.settings.first()
                if (!settings.serverConsent) throw IllegalStateException("Allow sending documents to your server in Settings → Folio server")
                val server = com.folio.pdf.net.FolioServer.from(context, settings)
                    ?: throw IllegalStateException("Set up your Folio server in Settings first")
                val (bytes, validation) = server.pdfa(uri, 2)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(output, "wt")?.use { it.write(bytes) }
                }
                container.documents.persistReadPermission(output)
                val verdict = when {
                    validation == "passed" -> "Validated with veraPDF: passed ✅"
                    validation.startsWith("failed") -> "veraPDF found problems: ${validation.removePrefix("failed: ")}"
                    else -> "Not validated (veraPDF isn't installed on the server)"
                }
                JobState.Done("Converted to PDF/A-2b. $verdict", listOf(output), openable = output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JobState.Failed(e.message ?: "Couldn't convert")
            }
        }
    }

    fun dismiss() { _job.value = JobState.Idle }
}

@Composable
fun MetadataScreen(initialUri: Uri?, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    PickPdfFirst(
        initialUri = initialUri,
        kind = ToolKind.METADATA,
        message = "See and edit a PDF's title, author and keywords, remove hidden data, or check it for archiving (PDF/A).",
        onBack = onBack,
    ) { uri -> MetadataEditor(uri, onBack, onOpenDocument) }
}

@Composable
private fun MetadataEditor(uri: Uri, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val context = LocalContext.current
    val vm: MetadataViewModel = viewModel(
        key = "meta:$uri",
        factory = viewModelFactory { initializer { MetadataViewModel(uri, app, app.container) } },
    )
    val meta by vm.meta.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()

    var edited by remember(meta) { mutableStateOf(meta) }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val m = edited
        if (out != null && m != null) vm.save(m, out)
    }
    val pdfaCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out != null) vm.convertToPdfa(out)
    }
    val cleanCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out != null) vm.clean(out)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val m = edited
        if (m == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (job is JobState.Failed) JobCard(job, onOpen = {}, onShare = {}, onCancel = {}, onDismiss = onBack)
                else CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Details") {
                Field("Title", m.title) { edited = m.copy(title = it) }
                Field("Author", m.author) { edited = m.copy(author = it) }
                Field("Subject", m.subject) { edited = m.copy(subject = it) }
                Field("Keywords", m.keywords) { edited = m.copy(keywords = it) }
                val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                Hint(
                    listOfNotNull(
                        "${m.pages} pages · PDF ${m.version}",
                        m.pdfa,
                        m.creator.takeIf { it.isNotBlank() }?.let { "Made with $it" },
                        m.producer.takeIf { it.isNotBlank() }?.let { "Produced by $it" },
                        m.created?.let { "Created ${fmt.format(Date(it))}" },
                        m.modified?.let { "Modified ${fmt.format(Date(it))}" },
                    ).joinToString("\n"),
                )
                RunButton("Save as a copy", enabled = edited != meta && job !is JobState.Running) {
                    saveCopy.launch("${vm.name} (edited info).pdf")
                }
            }

            Section("Privacy") {
                Hint("PDFs can carry your name, software details, edit history, scripts and attached files without you noticing. Remove them before sharing widely.")
                OutlinedButton(onClick = { cleanCopy.launch("${vm.name} (clean).pdf") }, enabled = job !is JobState.Running) {
                    Text("Remove hidden data…")
                }
            }

            Section("Archiving (PDF/A)") {
                Hint("PDF/A is the format courts, governments and archives ask for, so documents still open correctly decades from now.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::check) { Text(if (checks == null) "Check readiness" else "Check again") }
                    OutlinedButton(onClick = { pdfaCopy.launch("${vm.name} (PDF-A).pdf") }, enabled = job !is JobState.Running) {
                        Text("Convert (server)")
                    }
                }
                checks?.let { list ->
                    if (list.isEmpty()) CircularProgressIndicator()
                    list.forEach { c -> CheckRow(c) }
                    if (list.isNotEmpty()) {
                        val fails = list.count { it.status == CheckStatus.FAIL }
                        Hint(
                            if (fails == 0) "No blocking problems found. For certainty, convert on your server: it validates with veraPDF when installed."
                            else "$fails problems would stop this file being accepted as PDF/A. \"Convert (server)\" fixes most of them.",
                        )
                    }
                }
            }

            JobCard(job, onOpen = onOpenDocument, onShare = { share(context, it.outputs, it.mime) }, onCancel = vm::dismiss, onDismiss = vm::dismiss)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CheckRow(c: PdfaCheck) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (c.status) {
            CheckStatus.PASS -> Icon(Icons.Filled.CheckCircle, "Pass", tint = MaterialTheme.colorScheme.primary)
            CheckStatus.WARN -> Icon(Icons.Outlined.Warning, "Warning", tint = MaterialTheme.colorScheme.tertiary)
            CheckStatus.FAIL -> Icon(Icons.Outlined.ErrorOutline, "Problem", tint = MaterialTheme.colorScheme.error)
        }
        Column {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.sign

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.AppContainer
import com.folio.pdf.FolioApp
import com.folio.pdf.pdf.ToolException
import com.folio.pdf.sign.DigitalIdInfo
import com.folio.pdf.sign.DigitalIds
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

class DigitalSignViewModel(
    private val uri: Uri,
    private val context: Context,
    private val container: AppContainer,
) : ViewModel() {
    private val _id = MutableStateFlow(DigitalIds.info(context))
    val id: StateFlow<DigitalIdInfo?> = _id.asStateFlow()

    private val _existing = MutableStateFlow<List<com.folio.pdf.sign.SignatureReport>>(emptyList())
    val existing: StateFlow<List<com.folio.pdf.sign.SignatureReport>> = _existing.asStateFlow()

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    init {
        viewModelScope.launch {
            container.documents.persistReadPermission(uri)
            _name.value = container.documents.describe(uri).name.substringBeforeLast('.')
            _existing.value = com.folio.pdf.sign.SignatureCheck.verify(context, uri)
        }
    }

    private fun attempt(label: String, block: suspend () -> JobState) {
        if (_job.value is JobState.Running) return
        _job.value = JobState.Running(null, label)
        viewModelScope.launch {
            _job.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolException) {
                JobState.Failed(e.message ?: "Something went wrong")
            } catch (e: Exception) {
                JobState.Failed("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun createId(name: String, email: String, password: String) = attempt("Creating your digital ID…") {
        _id.value = DigitalIds.create(context, name.trim(), email.trim(), password.toCharArray())
        JobState.Idle
    }

    fun importId(source: Uri, password: String) = attempt("Importing…") {
        _id.value = DigitalIds.import(context, source, password.toCharArray())
        JobState.Idle
    }

    fun removeId() {
        DigitalIds.delete(context)
        _id.value = null
    }

    fun sign(password: String, reason: String, location: String, output: Uri) = attempt("Signing…") {
        val temp = File.createTempFile("folio-signed", ".pdf", context.cacheDir)
        try {
            DigitalIds.sign(context, uri, password.toCharArray(), reason, location, temp)
            container.documents.overwrite(output, temp)
            container.documents.persistReadPermission(output)
            JobState.Done("Signed with your digital ID. The signature is saved in a new copy.", listOf(output), openable = output)
        } finally {
            temp.delete()
        }
    }

    fun dismiss() { _job.value = JobState.Idle }
}

@Composable
fun DigitalSignScreen(initialUri: Uri?, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    PickPdfFirst(
        initialUri = initialUri,
        kind = ToolKind.DIGITAL_SIGN,
        message = "Pick the finished PDF you want to sign with your certificate.",
        onBack = onBack,
    ) { uri -> DigitalSigner(uri, onBack, onOpenDocument) }
}

@Composable
private fun DigitalSigner(uri: Uri, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val context = LocalContext.current
    val vm: DigitalSignViewModel = viewModel(
        key = "dsign:$uri",
        factory = viewModelFactory { initializer { DigitalSignViewModel(uri, app, app.container) } },
    )
    val id by vm.id.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val busy = job is JobState.Running

    var reason by rememberSaveable { mutableStateOf("I approve this document") }
    var location by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked -> importUri = picked }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) vm.sign(password, reason, location, target)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign with digital ID") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Hint(
                "A digital signature uses a certificate to prove who signed and that the PDF hasn't been changed since. " +
                    "For a handwritten-style signature, use ✏️ Edit → Sign instead (you can do both: sign by hand first, save, then sign digitally).",
            )
            Section("Document") {
                Text(name.ifEmpty { "PDF" }, style = MaterialTheme.typography.titleMedium)
                if (existing.isEmpty()) {
                    Hint("Not digitally signed yet.")
                } else {
                    existing.forEach { r ->
                        val verdict = when {
                            r.intact == false -> "❌ Signature is broken: the signed content doesn't match"
                            r.intact == null -> "⚠️ Couldn't check this signature"
                            !r.coversWholeFile -> "⚠️ Valid, but the PDF was changed after this signature"
                            r.selfSigned -> "✅ Intact · personal (self-signed) ID, identity not verified by an authority"
                            else -> "✅ Intact · certificate issued by ${r.issuer ?: "unknown"}"
                        }
                        Text(r.signer + (r.date?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                        Hint(verdict)
                    }
                }
            }

            Section("Your digital ID") {
                val info = id
                if (info == null) {
                    Hint("You don't have a digital ID on this phone yet.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { creating = true }, enabled = !busy) { Text("Create one") }
                        OutlinedButton(onClick = { importPicker.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }, enabled = !busy) {
                            Text("Import .p12 / .pfx")
                        }
                    }
                    Hint("Have one from your employer, a government or a certificate authority? Import it; those signatures are trusted by other apps. A Folio-created ID is personal (self-signed).")
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(14.dp)) {
                            Icon(Icons.Outlined.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(info.name, style = MaterialTheme.typography.titleSmall)
                                if (info.email.isNotBlank()) Text(info.email, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    (if (info.selfSigned) "Personal (self-signed)" else "Issued by ${info.issuer}") +
                                        " · valid until " + DateFormat.getDateInstance().format(Date(info.expires)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    TextButton(onClick = vm::removeId, enabled = !busy) { Text("Remove this ID") }
                }
            }

            if (id != null) {
                Section("Sign") {
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(location, { location = it }, label = { Text("Location (optional)") }, placeholder = { Text("e.g. Nairobi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text("Digital ID password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("The signed PDF is saved as a new copy. Any later change, even a highlight, will show the signature as modified, so sign last.")
                }
                RunButton(
                    text = "Sign and save copy",
                    enabled = password.isNotEmpty() && !busy,
                    onClick = { save.launch("$name (signed).pdf") },
                )
            }

            JobCard(
                job = job,
                onOpen = onOpenDocument,
                onShare = { done -> share(context, done.outputs, done.mime) },
                onCancel = vm::dismiss,
                onDismiss = vm::dismiss,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (creating) {
        CreateIdDialog(
            onDismiss = { creating = false },
            onCreate = { n, e, p -> creating = false; vm.createId(n, e, p) },
        )
    }
    importUri?.let { source ->
        PasswordDialog(
            title = "Digital ID password",
            message = "Enter the password for this .p12 / .pfx file.",
            onDismiss = { importUri = null },
            onConfirm = { p -> importUri = null; vm.importId(source, p) },
        )
    }
}

@Composable
private fun CreateIdDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && password.length >= 6 && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a digital ID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("E-mail (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(confirm, { confirm = it }, label = { Text("Repeat password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirm.isNotEmpty() && confirm != password)
                Hint("Folio never stores this password. If you forget it, remove the ID and create a new one.")
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, email, password) }, enabled = valid) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

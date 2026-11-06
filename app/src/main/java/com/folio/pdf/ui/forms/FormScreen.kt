@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.forms

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.FolioApp
import com.folio.pdf.pdf.FormField
import com.folio.pdf.ui.components.EmptyState
import com.folio.pdf.ui.editor.UnsavedChangesDialog
import com.folio.pdf.ui.tools.PickPdfFirst
import com.folio.pdf.ui.tools.ToolKind
import kotlinx.coroutines.launch

@Composable
fun FormScreen(initialUri: Uri?, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    PickPdfFirst(
        initialUri = initialUri,
        kind = ToolKind.FILL_FORM,
        message = "Pick a PDF with fillable fields, such as an application or registration form.",
        onBack = onBack,
    ) { uri -> FormFiller(uri, onBack, onOpenDocument) }
}

@Composable
private fun FormFiller(uri: Uri, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: FormViewModel = viewModel(
        key = "form:$uri",
        factory = viewModelFactory { initializer { FormViewModel(uri, app, app.container) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var pendingFlatten by remember { mutableStateOf(false) }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) vm.save(target, pendingFlatten)
    }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is FormEvent.Message -> snackbar.showSnackbar(e.text)
                FormEvent.NeedCopy -> {
                    scope.launch { snackbar.showSnackbar("Folio can't change the original, so save a copy") }
                    pendingFlatten = false
                    saveCopy.launch("${state.title} (filled).pdf")
                }
                is FormEvent.Open -> onOpenDocument(e.uri)
            }
        }
    }

    fun exit() { if (state.dirty) confirmExit = true else onBack() }
    BackHandler(enabled = state.dirty) { exit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Fill form")
                        if (state.title.isNotEmpty()) {
                            Text(state.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = ::exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        if (state.saving) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = { menu = true }, enabled = state.fields.isNotEmpty()) {
                                Icon(Icons.Outlined.Save, "Save")
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save") },
                                enabled = state.dirty,
                                onClick = { menu = false; vm.save(null, flatten = false) },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as a copy…") },
                                onClick = { menu = false; pendingFlatten = false; saveCopy.launch("${state.title} (filled).pdf") },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Save a final copy…")
                                        Text(
                                            "Answers become part of the page and can't be changed",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = { menu = false; pendingFlatten = true; saveCopy.launch("${state.title} (final).pdf") },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Outlined.Info, title = "Can't fill this PDF", message = state.error.orEmpty())
            }
            state.fields.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = ToolKind.FILL_FORM.icon,
                    title = if (state.xfaOnly) "This form type isn't supported" else "No fillable fields",
                    message = if (state.xfaOnly) {
                        "It's an XFA form, a format only some Adobe apps support. Try asking for a regular PDF form."
                    } else {
                        "This PDF has no form fields. You can still type on it with ✏️ Edit → Text, and sign it with ✏️ → Sign."
                    },
                )
            }
            else -> FieldList(state, onChange = vm::set, modifier = Modifier.padding(padding))
        }
    }

    if (confirmExit) {
        UnsavedChangesDialog(
            onSave = { confirmExit = false; vm.save(null, flatten = false) },
            onDiscard = { confirmExit = false; onBack() },
            onCancel = { confirmExit = false },
        )
    }
}

@Composable
private fun FieldList(state: FormUiState, onChange: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val byPage = remember(state.fields) { state.fields.groupBy { it.page }.toSortedMap() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    "${state.fields.size} fields. Fill in what you need, then tap 💾. " +
                        "Tip: \"Save a final copy\" makes your answers show in every app.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        byPage.forEach { (page, fields) ->
            item(key = "page-$page") {
                Text(
                    if (page >= 0) "Page ${page + 1}" else "Other fields",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(fields, key = { it.name }) { field ->
                FieldInput(field, state.values[field.name].orEmpty()) { onChange(field.name, it) }
            }
        }
    }
}

@Composable
private fun FieldInput(field: FormField, value: String, onChange: (String) -> Unit) {
    val enabled = !field.readOnly
    when (field) {
        is FormField.Text -> OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(if (field.maxLength > 0) v.take(field.maxLength) else v) },
            label = { Text(field.label) },
            enabled = enabled,
            singleLine = !field.multiline,
            minLines = if (field.multiline) 3 else 1,
            supportingText = if (field.maxLength > 0) { { Text("${value.length} / ${field.maxLength}") } } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        is FormField.Check -> Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange((value != "true").toString()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = value == "true", onCheckedChange = { onChange(it.toString()) }, enabled = enabled)
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
        }

        is FormField.Choice -> Column {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            if (field.options.size <= 5 && !field.editable) {
                field.options.forEachIndexed { i, option ->
                    val export = field.exportValues.getOrElse(i) { option }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(export) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == export, onClick = { onChange(export) }, enabled = enabled)
                        Text(option)
                    }
                }
            } else {
                var open by remember { mutableStateOf(false) }
                val shown = field.exportValues.indexOf(value).takeIf { it >= 0 }?.let { field.options.getOrNull(it) } ?: value
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(shown.ifEmpty { "Choose…" }, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        field.options.forEachIndexed { i, option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { open = false; onChange(field.exportValues.getOrElse(i) { option }) },
                            )
                        }
                    }
                }
            }
        }

        is FormField.SignatureSlot -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Gesture, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(field.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (field.signed) "Already signed" else "Save the form, then sign it: open it and use ✏️ → Sign, or ⋮ → Sign with digital ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

package com.folio.pdf.ui.browser

import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.pdf.FolioApp
import com.folio.pdf.ui.components.EmptyState
import com.folio.pdf.ui.components.PdfThumbnail
import com.folio.pdf.util.formatFileSize
import com.folio.pdf.util.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOpenDocument: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: BrowserViewModel = viewModel(factory = BrowserViewModel.factory(app.container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.onFilePicked(uri)
            onOpenDocument(uri)
        }
    }
    var sortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (state.content is BrowserContent.Files) {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Outlined.Refresh, "Refresh") }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        trailingIcon = {
                                            if (order == state.sort) Icon(Icons.Outlined.Check, null)
                                        },
                                        onClick = { vm.setSort(order); sortMenu = false },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val content = state.content) {
                BrowserContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                BrowserContent.NoFolder -> EmptyState(
                    icon = Icons.Outlined.CreateNewFolder,
                    title = "Pick a folder to browse",
                    message = "Choose a folder such as Downloads or Documents. Folio lists the PDFs inside it " +
                        "and can't see any of your other files.",
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("application/pdf")) }) {
                        Text("Open a single file")
                    }
                }

                is BrowserContent.Error -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Folder unavailable",
                    message = content.message,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                }

                is BrowserContent.Files -> FileList(
                    content = content,
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onChangeFolder = { folderPicker.launch(null) },
                    onOpenFile = { filePicker.launch(arrayOf("application/pdf")) },
                    onOpenDocument = onOpenDocument,
                )
            }
        }
    }
}

@Composable
private fun FileList(
    content: BrowserContent.Files,
    query: String,
    onQueryChange: (String) -> Unit,
    onChangeFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search ${content.total} PDFs") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "Clear search") }
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        content.folderName,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onOpenFile) {
                        Icon(Icons.Outlined.FileOpen, null)
                        Text("File", Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = onChangeFolder) { Text("Change") }
                }
            }
        }
        if (content.files.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = if (query.isBlank()) "No PDFs in this folder" else "No matches",
                    message = if (query.isBlank()) "Try another folder, or open a single file instead."
                    else "Nothing here matches \"$query\".",
                )
            }
        }
        items(content.files, key = { it.uri.toString() }) { file ->
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { onOpenDocument(file.uri) },
                leadingContent = { PdfThumbnail(file.uri.toString()) },
                headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        listOf(formatFileSize(file.sizeBytes), formatRelativeTime(file.lastModified))
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                    )
                },
            )
        }
    }
}

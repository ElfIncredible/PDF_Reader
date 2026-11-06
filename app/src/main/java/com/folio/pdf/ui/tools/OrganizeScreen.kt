@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.FolioApp
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.ui.components.EmptyState
import com.folio.pdf.ui.editor.UnsavedChangesDialog
import kotlinx.coroutines.launch

/** Entry point: asks for a PDF first if none was given. */
@Composable
fun OrganizeScreen(initialUri: Uri?, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    var uri by remember { mutableStateOf(initialUri) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) uri = picked
    }
    val current = uri
    if (current == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(ToolKind.ORGANIZE.title) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = ToolKind.ORGANIZE.icon,
                    title = "Choose a PDF to organize",
                    message = "Reorder, rotate, delete or extract pages. Tap pages to select them, then pick an action.",
                ) {
                    Button(onClick = { pick.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Outlined.FileOpen, null)
                        Text("Choose PDF", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    } else {
        Organizer(current, onBack, onOpenDocument)
    }
}

@Composable
private fun Organizer(uri: Uri, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: OrganizeViewModel = viewModel(
        key = "organize:$uri",
        factory = viewModelFactory { initializer { OrganizeViewModel(uri, app, app.container) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saveMenu by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        it?.let { target -> vm.save(target) }
    }
    val extract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        it?.let(vm::extractSelected)
    }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is OrganizeEvent.Message -> snackbar.showSnackbar(e.text)
                OrganizeEvent.NeedCopy -> {
                    scope.launch { snackbar.showSnackbar("Folio can't change the original file, so save a copy") }
                    saveCopy.launch("${state.title} (organized).pdf")
                }
                is OrganizeEvent.Open -> onOpenDocument(e.uri)
            }
        }
    }

    fun exit() { if (state.dirty) confirmExit = true else onBack() }
    BackHandler(enabled = state.selectionCount > 0 || state.dirty) {
        if (state.selectionCount > 0) vm.clearSelection() else exit()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state.selectionCount > 0) "${state.selectionCount} selected" else "Organize pages")
                        Text(
                            "${state.pages.size} pages" + if (state.dirty) " · unsaved" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = ::exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                    IconButton(onClick = { if (state.selectionCount == state.pages.size) vm.clearSelection() else vm.selectAll() }) {
                        Icon(Icons.Outlined.SelectAll, "Select all")
                    }
                    Box {
                        if (state.saving) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = { saveMenu = true }) { Icon(Icons.Outlined.Save, "Save") }
                        }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save") },
                                enabled = state.dirty,
                                onClick = { saveMenu = false; vm.save(null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as a copy…") },
                                onClick = { saveMenu = false; saveCopy.launch("${state.title} (organized).pdf") },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selectionCount > 0) {
                SelectionActions(
                    onRotateLeft = { vm.rotateSelected(-90) },
                    onRotateRight = { vm.rotateSelected(90) },
                    onEarlier = { vm.moveSelected(-1) },
                    onLater = { vm.moveSelected(+1) },
                    onMoveTo = { moveDialog = true },
                    onExtract = { extract.launch("${state.title} (pages).pdf") },
                    onDelete = vm::deleteSelected,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val doc = state.document
        when {
            state.loading || (doc == null && state.error == null) -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Outlined.ErrorOutline, title = "Can't organize this PDF", message = state.error.orEmpty())
            }
            doc != null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.pages, key = { it.key }) { item ->
                    val position = state.pages.indexOf(item)
                    PageTile(
                        doc = doc,
                        item = item,
                        position = position,
                        selected = item.key in state.selected,
                        onClick = { vm.toggle(item.key) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (moveDialog) {
        MoveToDialog(
            pageCount = state.pages.size,
            onDismiss = { moveDialog = false },
            onMove = { p -> moveDialog = false; vm.moveSelectedTo(p) },
        )
    }
    if (confirmExit) {
        UnsavedChangesDialog(
            onSave = { confirmExit = false; vm.save(null) },
            onDiscard = { confirmExit = false; onBack() },
            onCancel = { confirmExit = false },
        )
    }
}

@Composable
private fun PageTile(
    doc: PdfFile,
    item: PageItem,
    position: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(doc, item.source) { mutableStateOf<Bitmap?>(doc.cachedPage(item.source, THUMB)) }
    LaunchedEffect(doc, item.source) { if (bitmap == null) bitmap = doc.renderPage(item.source, THUMB) }
    val sideways = item.rotation == 90 || item.rotation == 270

    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .fillMaxSize(if (sideways) 0.75f else 1f)
                    .graphicsLayer { rotationZ = item.rotation.toFloat() },
                color = Color.White,
                shadowElevation = 2.dp,
                border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Page ${position + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp),
                )
            }
        }
        Text(
            buildString {
                append(position + 1)
                if (item.source != position) append("  (was ${item.source + 1})")
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val THUMB = 260

@Composable
private fun SelectionActions(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onMoveTo: () -> Unit,
    onExtract: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Action(Icons.Outlined.RotateLeft, "Rotate left", onRotateLeft)
            Action(Icons.Outlined.RotateRight, "Rotate right", onRotateRight)
            Action(Icons.AutoMirrored.Filled.ArrowBack, "Earlier", onEarlier)
            Action(Icons.AutoMirrored.Filled.ArrowForward, "Later", onLater)
            Action(Icons.Outlined.FileOpen, "Move to…", onMoveTo)
            Action(Icons.Outlined.Save, "Extract", onExtract)
            Action(Icons.Outlined.Delete, "Delete", onDelete)
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun MoveToDialog(pageCount: Int, onDismiss: () -> Unit, onMove: (Int) -> Unit) {
    var text by remember { mutableStateOf("1") }
    val n = text.toIntOrNull()
    val valid = n != null && n in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move selected pages to…") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(5) },
                label = { Text("New position") },
                supportingText = { Text("1 puts them at the start, $pageCount at the end") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { n?.let(onMove) }, enabled = valid) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

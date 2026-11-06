package com.folio.pdf.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.pdf.data.ColorMode
import com.folio.pdf.edit.NPoint
import com.folio.pdf.edit.TextNote
import com.folio.pdf.edit.Tool
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.ui.viewer.ViewerViewModel

private sealed interface TextDialog {
    data class New(val at: NPoint) : TextDialog
    data class Edit(val note: TextNote) : TextDialog
}

/**
 * Full-screen editing: one page at a time, a tool bar at the bottom, undo/redo/save on top.
 */
@Composable
fun EditorContent(
    vm: ViewerViewModel,
    doc: PdfFile,
    colorMode: ColorMode,
    startPage: Int,
    onDone: (page: Int) -> Unit,
    onSave: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(startPage.coerceIn(0, doc.pageCount - 1)) }
    val editor by vm.editorState.collectAsStateWithLifecycle()
    val tools by vm.tools.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    var textDialog by remember { mutableStateOf<TextDialog?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addImage(page, uri)
    }

    BackHandler { onDone(page) }

    val pageAnnotations = remember(editor.annotations, page) { editor.annotations.filter { it.page == page } }
    // Re-read the page text as more of the document is indexed (Phase 2 text engine).
    val pageText = remember(page, index.done) { vm.pageText(page) }
    val selected = editor.annotations.firstOrNull { it.id == selectedId }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                if (colorMode == ColorMode.NIGHT) MaterialTheme.colorScheme.surfaceContainerLowest
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            EditTopBar(
                dirty = editor.dirty,
                canUndo = editor.canUndo,
                canRedo = editor.canRedo,
                saving = saving,
                onDone = { onDone(page) },
                onUndo = vm::undo,
                onRedo = vm::redo,
                onSave = onSave,
                onSaveCopy = onSaveCopy,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            PageEditor(
                doc = doc,
                pageIndex = page,
                colorMode = colorMode,
                pageText = pageText,
                annotations = pageAnnotations,
                selectedId = selectedId,
                settings = tools,
                actions = vm,
                onRequestText = { at -> textDialog = TextDialog.New(at) },
            )
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.navigationBarsPadding()) {
                PageNavRow(
                    page = page,
                    count = doc.pageCount,
                    onPrevious = { vm.select(null); page = (page - 1).coerceAtLeast(0) },
                    onNext = { vm.select(null); page = (page + 1).coerceAtMost(doc.pageCount - 1) },
                )
                HorizontalDivider()
                if (selected != null && tools.tool == Tool.SELECT) {
                    SelectionRow(
                        selected = selected,
                        onColor = vm::recolorSelected,
                        onEditText = { (selected as? TextNote)?.let { textDialog = TextDialog.Edit(it) } },
                        onDelete = vm::deleteSelected,
                        onDeselect = { vm.select(null) },
                    )
                } else {
                    StyleRow(
                        settings = tools,
                        onColor = vm::setToolColor,
                        onStrokeWidth = vm::setStrokeWidth,
                        onTextSize = vm::setTextSize,
                    )
                }
                EditToolbar(
                    current = tools.tool,
                    onTool = vm::setTool,
                    onImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
        }
    }

    when (val d = textDialog) {
        is TextDialog.New -> TextEntryDialog(
            initial = "",
            onDismiss = { textDialog = null },
            onConfirm = { text ->
                textDialog = null
                vm.addText(page, d.at, text)
            },
        )
        is TextDialog.Edit -> TextEntryDialog(
            initial = d.note.text,
            onDismiss = { textDialog = null },
            onConfirm = { text ->
                textDialog = null
                vm.updateText(d.note.id, text)
            },
        )
        null -> Unit
    }
}

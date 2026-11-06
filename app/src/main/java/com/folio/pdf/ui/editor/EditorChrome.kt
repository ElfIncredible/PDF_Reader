@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.pdf.edit.Annotation
import com.folio.pdf.edit.ImageStamp
import com.folio.pdf.edit.Palette
import com.folio.pdf.edit.TextNote
import com.folio.pdf.edit.Tool
import com.folio.pdf.edit.ToolSettings

@Composable
fun EditTopBar(
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    saving: Boolean,
    onDone: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    var saveMenu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onDone) { Icon(Icons.Outlined.Check, "Done editing") }
        },
        title = {
            Column {
                Text("Editing", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (dirty) "Unsaved changes" else "All changes saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
            Box {
                if (saving) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = { saveMenu = true }) { Icon(Icons.Outlined.Save, "Save") }
                }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Save") },
                        onClick = { saveMenu = false; onSave() },
                        enabled = dirty,
                    )
                    DropdownMenuItem(
                        text = { Text("Save as a copy…") },
                        onClick = { saveMenu = false; onSaveCopy() },
                    )
                }
            }
        },
    )
}

@Composable
fun PageNavRow(page: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onPrevious, enabled = page > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page")
        }
        Text("Page ${page + 1} of $count", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onNext, enabled = page < count - 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page")
        }
    }
}

private data class ToolItem(val tool: Tool?, val icon: ImageVector, val label: String)

private val toolItems = listOf(
    ToolItem(Tool.SELECT, Icons.Outlined.TouchApp, "Select"),
    ToolItem(Tool.HIGHLIGHT, Icons.Outlined.BorderColor, "Highlight"),
    ToolItem(Tool.UNDERLINE, Icons.Outlined.FormatUnderlined, "Underline"),
    ToolItem(Tool.STRIKEOUT, Icons.Outlined.StrikethroughS, "Strike"),
    ToolItem(Tool.PEN, Icons.Outlined.Draw, "Pen"),
    ToolItem(Tool.TEXT, Icons.Outlined.TextFields, "Text"),
    ToolItem(null, Icons.Outlined.Image, "Image"),
    ToolItem(Tool.RECTANGLE, Icons.Outlined.CropSquare, "Box"),
    ToolItem(Tool.OVAL, Icons.Outlined.Circle, "Circle"),
    ToolItem(Tool.LINE, Icons.Outlined.HorizontalRule, "Line"),
    ToolItem(Tool.ARROW, Icons.AutoMirrored.Filled.ArrowForward, "Arrow"),
    ToolItem(Tool.ERASER, Icons.Outlined.DeleteSweep, "Eraser"),
)

@Composable
fun EditToolbar(current: Tool, onTool: (Tool) -> Unit, onImage: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        toolItems.forEach { item ->
            val selected = item.tool != null && item.tool == current
            Column(
                modifier = Modifier
                    .width(64.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { if (item.tool == null) onImage() else onTool(item.tool) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Colour swatches and a size slider for the active tool. */
@Composable
fun StyleRow(
    settings: ToolSettings,
    onColor: (Int) -> Unit,
    onStrokeWidth: (Float) -> Unit,
    onTextSize: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (settings.hasColor) ColorSwatches(selected = settings.color, onColor = onColor)
        when {
            settings.hasStroke -> SizeSlider(
                label = "Thickness",
                value = settings.strokeWidth,
                range = 0.0015f..0.02f,
                onChange = onStrokeWidth,
            )
            settings.tool == Tool.TEXT -> SizeSlider(
                label = "Text size",
                value = settings.textSize,
                range = 0.012f..0.08f,
                onChange = onTextSize,
            )
            settings.tool == Tool.SELECT -> Hint("Tap something you've added to select it. Drag to move, drag the round handle to resize.")
            settings.tool == Tool.ERASER -> Hint("Tap or rub over anything you've added to remove it.")
            settings.tool == Tool.HIGHLIGHT || settings.tool == Tool.UNDERLINE || settings.tool == Tool.STRIKEOUT ->
                Hint("Drag across text, or tap a word. Use two fingers to zoom and move the page.")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ColorSwatches(selected: Int, onColor: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Palette.all.forEach { c ->
            val isSelected = c == selected
            Box(
                Modifier
                    .size(32.dp)
                    .border(
                        BorderStroke(
                            if (isSelected) 3.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
                        CircleShape,
                    )
                    .padding(4.dp)
                    .background(Color(c), CircleShape)
                    .clickable { onColor(c) },
            )
        }
    }
}

@Composable
private fun SizeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

/** Actions for the annotation picked with the Select tool. */
@Composable
fun SelectionRow(
    selected: Annotation,
    onColor: (Int) -> Unit,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
    onDeselect: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (selected) {
                    is TextNote -> "Text box"
                    is ImageStamp -> "Image"
                    else -> "Selected"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (selected is TextNote) {
                TextButton(onClick = onEditText) {
                    Icon(Icons.Outlined.Edit, null)
                    Text("Edit", Modifier.padding(start = 4.dp))
                }
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, null)
                Text("Delete", Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onDeselect) { Text("Done") }
        }
        if (selected !is ImageStamp) ColorSwatches(selected = selected.color, onColor = onColor)
    }
}

@Composable
fun TextEntryDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus),
                placeholder = { Text("Type here") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trimEnd()) }, enabled = text.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun UnsavedChangesDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save your changes?") },
        text = { Text("You've edited this PDF. If you leave without saving, your changes will be lost.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun ReadOnlyNotice(message: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

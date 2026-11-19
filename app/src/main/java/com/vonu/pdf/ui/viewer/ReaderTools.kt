@file:OptIn(ExperimentalMaterial3Api::class)

package com.vonu.pdf.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vonu.pdf.ui.components.BrandLabel
import com.vonu.pdf.ui.tools.ToolKind

/** Tools shown in the strip before "More". Five 64 dp slots fit a 320 dp phone. */
const val READER_STRIP_SIZE = 4

/** Tools that work on the open file, in the order a new user most likely needs them. */
private val READER_TOOLS = listOf(
    ToolKind.SIGN,
    ToolKind.ORGANIZE,
    ToolKind.COMPRESS,
    ToolKind.OCR,
    ToolKind.FILL_FORM,
    ToolKind.REDACT,
    ToolKind.PROTECT,
    ToolKind.DIGITAL_SIGN,
    ToolKind.SPLIT,
    ToolKind.PDF_TO_IMAGES,
    ToolKind.METADATA,
    ToolKind.ASSISTANT,
)

/**
 * The reader's tools, best first: what this file needs (a form to fill, a scan to make searchable),
 * then what this phone's owner uses most, then the default order. A locked file only offers Unlock.
 */
fun rankReaderTools(locked: Boolean, hasForm: Boolean, isScan: Boolean, uses: Map<String, Int>): List<ToolKind> {
    if (locked) return listOf(ToolKind.UNLOCK)
    val contextual = listOfNotNull(
        ToolKind.FILL_FORM.takeIf { hasForm },
        ToolKind.OCR.takeIf { isScan },
    )
    val byUse = READER_TOOLS
        .filterNot { it.comingSoon || it in contextual }
        // Stable sort: equal counts keep the default order.
        .sortedByDescending { uses[it.id] ?: 0 }
    return contextual + byUse
}

/** Always-visible strip of the top tools, so nobody has to guess that tools hide behind ⋮. */
@Composable
fun ReaderToolStrip(tools: List<ToolKind>, onTool: (ToolKind) -> Unit, onMore: () -> Unit) {
    val shown = tools.take(READER_STRIP_SIZE)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        shown.forEach { kind ->
            StripButton(kind.icon, kind.shortTitle, Modifier.weight(1f)) { onTool(kind) }
        }
        if (tools.size > shown.size) {
            StripButton(Icons.Outlined.MoreHoriz, "More", Modifier.weight(1f), emphasised = false, onClick = onMore)
        }
    }
}

@Composable
private fun StripButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 32.dp)
                .background(
                    if (emphasised) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (emphasised) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The tools that didn't fit in the strip, each with a line on what it does: the strip's "More". */
@Composable
fun ReaderToolsSheet(tools: List<ToolKind>, onTool: (ToolKind) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        BrandLabel(
            "More tools for this PDF",
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).semantics { heading() },
        )
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(tools, key = { it.id }) { kind ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onTool(kind) }.padding(horizontal = 8.dp),
                    leadingContent = {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    },
                    headlineContent = { Text(kind.title, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(kind.description) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

package com.vonu.pdf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vonu.pdf.data.db.RecentDocument
import com.vonu.pdf.util.formatFileSize
import com.vonu.pdf.util.formatRelativeTime

/** A recently opened document: thumbnail, name, progress, favourite star and a menu. */
@Composable
fun DocumentCard(
    doc: RecentDocument,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    removeLabel: String = "Remove from recents",
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PdfThumbnail(doc.uri)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Reading position first when there is one; otherwise length and size. When is always last.
                val details = listOfNotNull(
                    when {
                        doc.lastPage > 0 -> "Page ${doc.lastPage + 1} of ${doc.pageCount}"
                        doc.pageCount == 1 -> "1 page"
                        doc.pageCount > 1 -> "${doc.pageCount} pages"
                        else -> null
                    },
                    formatFileSize(doc.sizeBytes).ifEmpty { null },
                    formatRelativeTime(doc.lastOpenedAt).ifEmpty { null },
                ).joinToString(" · ")
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Progress only once reading has started; an empty bar on every new file is noise.
                if (doc.pageCount > 1 && doc.lastPage > 0) {
                    LinearProgressIndicator(
                        progress = { (doc.lastPage + 1f) / doc.pageCount },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                if (doc.isFavorite) {
                    Icon(Icons.Filled.Star, "Unstar", tint = MaterialTheme.colorScheme.tertiary)
                } else {
                    Icon(Icons.Outlined.StarBorder, "Star")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(removeLabel) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
    }
}

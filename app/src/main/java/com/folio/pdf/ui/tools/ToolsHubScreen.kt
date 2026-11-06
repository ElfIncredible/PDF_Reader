@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Upcoming(val title: String, val note: String, val icon: ImageVector)

private val upcoming = listOf(
    Upcoming("Sign documents", "Phase 5", Icons.Outlined.Draw),
    Upcoming("Fill forms", "Phase 5", Icons.Outlined.Edit),
    Upcoming("Scan with camera + OCR", "Phase 5", Icons.Outlined.DocumentScanner),
    Upcoming("Passwords & redaction", "Phase 6", Icons.Outlined.Lock),
    Upcoming("Word, Excel, PowerPoint → PDF", "Phase 7 · needs the Folio server", Icons.Outlined.CloudUpload),
)

@Composable
fun ToolsHubScreen(onOpenTool: (ToolKind) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ToolKind.entries) { kind ->
                Card(
                    onClick = { onOpenTool(kind) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    ListItem(
                        leadingContent = { Icon(kind.icon, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(kind.title) },
                        supportingContent = { Text(kind.description) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            item {
                Text(
                    "Coming next",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                )
            }
            items(upcoming) { u ->
                ListItem(
                    leadingContent = { Icon(u.icon, null, tint = MaterialTheme.colorScheme.outline) },
                    headlineContent = { Text(u.title, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supportingContent = { Text(u.note) },
                )
            }
        }
    }
}

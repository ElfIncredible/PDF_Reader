@file:OptIn(ExperimentalMaterial3Api::class)

package com.vonu.pdf.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vonu.pdf.ui.components.BrandLabel

/** Tools tab: every available tool, grouped by the job it does. */
@Composable
fun ToolsHubScreen(onOpenTool: (ToolKind) -> Unit) {
    val groups = ToolKind.available.groupBy { it.group }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tools") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            ToolGroup.entries.forEach { group ->
                val tools = groups[group].orEmpty()
                if (tools.isEmpty()) return@forEach
                item(key = group.name) {
                    BrandLabel(group.title, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() })
                }
                items(tools, key = { it.id }) { kind -> ToolRow(kind, onClick = { onOpenTool(kind) }) }
            }
        }
    }
}

@Composable
private fun ToolRow(kind: ToolKind, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        headlineContent = { Text(kind.title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(kind.description) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

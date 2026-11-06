package com.folio.pdf.ui.history

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.pdf.FolioApp
import com.folio.pdf.ui.components.DocumentCard
import com.folio.pdf.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenDocument: (Uri) -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(app.container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (state.total > 0) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, "Clear history")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.total > 0) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search ${state.total} documents") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") }
                            }
                        },
                    )
                }
            }
            when {
                state.loading -> Unit
                state.total == 0 -> item {
                    EmptyState(
                        icon = Icons.Outlined.History,
                        title = "No history yet",
                        message = "Every PDF you open is listed here, grouped by when you last read it.",
                    )
                }
                state.groups.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = "No matches",
                        message = "No document names match \"${state.query}\".",
                    )
                }
                else -> state.groups.forEach { group ->
                    item(key = "header-${group.title}") {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                        )
                    }
                    items(group.documents, key = { it.uri }) { doc ->
                        DocumentCard(
                            doc = doc,
                            onClick = { onOpenDocument(Uri.parse(doc.uri)) },
                            onToggleFavorite = { vm.toggleFavorite(doc.uri) },
                            onRemove = { vm.remove(doc.uri) },
                            removeLabel = "Remove from history",
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Your files aren't deleted. Favourites stay; everything else is removed from this list.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

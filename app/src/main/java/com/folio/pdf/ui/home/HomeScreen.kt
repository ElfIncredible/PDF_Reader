package com.folio.pdf.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.pdf.FolioApp
import com.folio.pdf.data.db.RecentDocument
import com.folio.pdf.ui.components.DocumentCard
import com.folio.pdf.ui.tools.ToolKind
import com.folio.pdf.ui.components.EmptyState
import com.folio.pdf.ui.components.PdfThumbnail
import kotlinx.coroutines.launch

private val PDF_MIME = arrayOf("application/pdf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Uri) -> Unit,
    onBrowse: () -> Unit,
    onHistory: () -> Unit,
    onTool: (ToolKind?) -> Unit,
    onSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app.container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.onDocumentPicked(uri)
            onOpenDocument(uri)
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Folio") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(PDF_MIME) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Open PDF") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                QuickActions(
                    onOpenFile = { picker.launch(PDF_MIME) },
                    onBrowse = onBrowse,
                    onTool = onTool,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onHistory) { Text("History") }
                    FilterChip(
                        selected = state.filter == RecentFilter.ALL,
                        onClick = { vm.setFilter(RecentFilter.ALL) },
                        label = { Text("All") },
                    )
                    FilterChip(
                        selected = state.filter == RecentFilter.FAVORITES,
                        onClick = { vm.setFilter(RecentFilter.FAVORITES) },
                        label = { Text("Favourites") },
                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (!state.loading && state.documents.isEmpty()) {
                item {
                    if (state.filter == RecentFilter.FAVORITES) {
                        EmptyState(
                            icon = Icons.Outlined.StarBorder,
                            title = "No favourites yet",
                            message = "Tap the star on any document to keep it here.",
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.History,
                            title = "Nothing opened yet",
                            message = "Open a PDF and it will show up here, so you can jump back to where you left off.",
                        )
                    }
                }
            } else if (state.documents.isNotEmpty()) {
                val first = state.documents.first()
                val showContinue = state.filter == RecentFilter.ALL && first.pageCount > 1 && first.lastPage > 0
                if (showContinue) {
                    item(key = "continue") {
                        ContinueReadingCard(first, onClick = { onOpenDocument(Uri.parse(first.uri)) })
                    }
                }
                items(if (showContinue) state.documents.drop(1) else state.documents, key = { it.uri }) { doc ->
                    DocumentCard(
                        doc = doc,
                        onClick = { onOpenDocument(Uri.parse(doc.uri)) },
                        onToggleFavorite = { vm.toggleFavorite(doc.uri) },
                        onRemove = { vm.remove(doc.uri) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    onOpenFile: () -> Unit,
    onBrowse: () -> Unit,
    onTool: (ToolKind?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Icons.Outlined.FileOpen, "Open file", onOpenFile, Modifier.weight(1f), primary = true)
            ActionTile(Icons.Outlined.FolderOpen, "Browse", onBrowse, Modifier.weight(1f), primary = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(ToolKind.SIGN.icon, "Sign", { onTool(ToolKind.SIGN) }, Modifier.weight(1f))
            ActionTile(ToolKind.SCAN.icon, "Scan", { onTool(ToolKind.SCAN) }, Modifier.weight(1f))
            ActionTile(ToolKind.ASSISTANT.icon, "AI", { onTool(ToolKind.ASSISTANT) }, Modifier.weight(1f))
            ActionTile(Icons.Outlined.Apps, "All tools", { onTool(null) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    badge: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = if (primary) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null)
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            if (badge != null) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) { Text(badge) }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(doc: RecentDocument, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(doc.uri)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Continue reading", style = MaterialTheme.typography.labelMedium)
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Page ${doc.lastPage + 1} of ${doc.pageCount}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { (doc.lastPage + 1f) / doc.pageCount },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

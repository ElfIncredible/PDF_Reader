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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.folio.pdf.ui.components.EmptyState
import com.folio.pdf.ui.components.PdfThumbnail
import com.folio.pdf.util.formatFileSize
import com.folio.pdf.util.formatRelativeTime
import kotlinx.coroutines.launch

private val PDF_MIME = arrayOf("application/pdf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Uri) -> Unit,
    onBrowse: () -> Unit,
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
    val comingSoon: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

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
                    onComingSoon = comingSoon,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
            } else {
                items(state.documents, key = { it.uri }) { doc ->
                    RecentDocumentRow(
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
    onComingSoon: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Icons.Outlined.FileOpen, "Open file", onOpenFile, Modifier.weight(1f), primary = true)
            ActionTile(Icons.Outlined.FolderOpen, "Browse", onBrowse, Modifier.weight(1f), primary = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Icons.Outlined.MergeType, "Merge", { onComingSoon("Merge & split arrive in Phase 4") }, Modifier.weight(1f), badge = "Soon")
            ActionTile(Icons.Outlined.Draw, "Sign", { onComingSoon("Signatures arrive in Phase 5") }, Modifier.weight(1f), badge = "Soon")
            ActionTile(Icons.Outlined.DocumentScanner, "Scan", { onComingSoon("The camera scanner arrives in Phase 5") }, Modifier.weight(1f), badge = "Soon")
            ActionTile(Icons.Outlined.Compress, "Compress", { onComingSoon("Compression arrives in Phase 4") }, Modifier.weight(1f), badge = "Soon")
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
private fun RecentDocumentRow(
    doc: RecentDocument,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
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
                val details = listOfNotNull(
                    if (doc.pageCount > 0) "Page ${doc.lastPage + 1} of ${doc.pageCount}" else null,
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
                if (doc.pageCount > 1) {
                    LinearProgressIndicator(
                        progress = { (doc.lastPage + 1f) / doc.pageCount },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                if (doc.isFavorite) {
                    Icon(Icons.Filled.Star, "Remove from favourites", tint = MaterialTheme.colorScheme.tertiary)
                } else {
                    Icon(Icons.Outlined.StarBorder, "Add to favourites")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove from recents") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
    }
}

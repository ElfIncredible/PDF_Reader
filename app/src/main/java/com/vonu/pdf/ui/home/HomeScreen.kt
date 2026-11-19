package com.vonu.pdf.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vonu.pdf.VonuApp
import com.vonu.pdf.data.db.RecentDocument
import com.vonu.pdf.ui.components.BrandLabel
import com.vonu.pdf.ui.components.DocumentCard
import com.vonu.pdf.ui.components.EmptyState
import com.vonu.pdf.ui.components.PdfThumbnail
import com.vonu.pdf.ui.components.VonuBrandTitle

private val PDF_MIME = arrayOf("application/pdf")

/** Files tab: one clear way in (Open a PDF), then what you read last. Tools and History are tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Uri) -> Unit,
    onBrowse: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as VonuApp
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app.container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.onDocumentPicked(uri)
            onOpenDocument(uri)
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { VonuBrandTitle() },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "start") {
                StartPanel(onOpenFile = { picker.launch(PDF_MIME) }, onBrowse = onBrowse)
            }
            val hasDocs = state.documents.isNotEmpty()
            if (hasDocs || state.filter == RecentFilter.FAVORITES) {
                item(key = "recent-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = state.filter == RecentFilter.ALL,
                            onClick = { vm.setFilter(RecentFilter.ALL) },
                            label = { Text("All") },
                        )
                        FilterChip(
                            selected = state.filter == RecentFilter.FAVORITES,
                            onClick = { vm.setFilter(RecentFilter.FAVORITES) },
                            label = { Text("Starred") },
                            leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            if (!state.loading && !hasDocs) {
                item(key = "empty") {
                    if (state.filter == RecentFilter.FAVORITES) {
                        EmptyState(
                            icon = Icons.Outlined.StarBorder,
                            title = "No starred files yet",
                            message = "Tap the star on any document to keep it here.",
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.History,
                            title = "Nothing opened yet",
                            message = "PDFs you open show up here, so you can pick up where you left off.",
                        )
                    }
                }
            } else if (hasDocs) {
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
private fun StartPanel(onOpenFile: () -> Unit, onBrowse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your PDFs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Open a file to read, sign or share it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            ButtonIcon(Icons.Outlined.FileOpen)
            Text("Open a PDF", style = MaterialTheme.typography.titleSmall)
        }
        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            ButtonIcon(Icons.Outlined.FolderOpen)
            Text("Browse folders", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}

@Composable
private fun ContinueReadingCard(doc: RecentDocument, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(doc.uri)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BrandLabel("Continue reading")
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Page ${doc.lastPage + 1} of ${doc.pageCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (doc.lastPage + 1f) / doc.pageCount },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

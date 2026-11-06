@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.batch

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.pdf.FolioApp
import com.folio.pdf.batch.BatchOp
import com.folio.pdf.batch.BatchOptions
import com.folio.pdf.batch.ItemStatus
import com.folio.pdf.pdf.CompressionLevel
import com.folio.pdf.ui.tools.Choice
import com.folio.pdf.ui.tools.Hint
import com.folio.pdf.ui.tools.PasswordField
import com.folio.pdf.ui.tools.RunButton
import com.folio.pdf.ui.tools.Section

@Composable
fun BatchScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val runner = app.container.batch
    val state by runner.state.collectAsStateWithLifecycle()

    val files = remember { mutableStateListOf<Uri>() }
    var op by rememberSaveable { mutableStateOf(BatchOp.COMPRESS) }
    var level by rememberSaveable { mutableStateOf(CompressionLevel.MEDIUM) }
    var password by remember { mutableStateOf("") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { app.container.documents.persistReadPermission(it) }
        files += uris.filterNot { it in files }
    }
    val latest by rememberUpdatedState(Triple(op, level, password))
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            app.container.documents.persistTreePermission(tree)
            val (o, l, p) = latest
            runner.start(o, files.toList(), tree, BatchOptions(l, p))
        }
    }
    // Ask once for permission to post the "batch finished" notification (Android 13+).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { folder.launch(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch process") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.items.isNotEmpty()) {
                Section(if (state.running) "Working… ${state.finished}/${state.items.size}" else "Last batch: ${state.op?.label.orEmpty()}") {
                    state.items.forEach { item ->
                        ListItem(
                            leadingContent = {
                                when (val st = item.status) {
                                    ItemStatus.Waiting -> Icon(Icons.Outlined.HourglassEmpty, null)
                                    is ItemStatus.Working -> CircularProgressIndicator(progress = { st.progress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                                    is ItemStatus.Done -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                    is ItemStatus.Failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                                }
                            },
                            headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    when (val st = item.status) {
                                        ItemStatus.Waiting -> "Waiting"
                                        is ItemStatus.Working -> "${(st.progress * 100).toInt()}%"
                                        is ItemStatus.Done -> st.detail
                                        is ItemStatus.Failed -> st.message
                                    },
                                )
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) OutlinedButton(onClick = runner::cancel) { Text("Cancel") }
                        else TextButton(onClick = runner::clear) { Text("Clear") }
                    }
                    if (state.running) Hint("You can leave this screen; Folio keeps going and tells you when it's done.")
                    else if (state.folderName.isNotEmpty()) Hint("Saved in ${state.folderName}. Your original files weren't changed.")
                }
            }

            if (!state.running) {
                Section("Files (${files.size})") {
                    files.forEachIndexed { i, uri ->
                        ListItem(
                            headlineContent = { Text(uri.lastPathSegment?.substringAfterLast('/') ?: "PDF ${i + 1}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { IconButton(onClick = { files.removeAt(i) }) { Icon(Icons.Outlined.Close, "Remove") } },
                        )
                    }
                    OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Outlined.Add, null)
                        Text("Add PDFs", Modifier.padding(start = 6.dp))
                    }
                }
                Section("What to do") {
                    BatchOp.entries.forEach { o ->
                        Row(Modifier.fillMaxWidth()) {
                            androidx.compose.material3.RadioButton(selected = op == o, onClick = { op = o })
                            Text(o.label, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    when (op) {
                        BatchOp.COMPRESS -> Choice(CompressionLevel.entries, level, { it.label }) { level = it }
                        BatchOp.PROTECT -> {
                            PasswordField(password, { password = it }, "Password for all files")
                            Hint("Every file gets the same password.")
                        }
                        BatchOp.OCR -> Hint("Scanned pages get a searchable text layer; pages that already have text are left alone.")
                        BatchOp.CLEAN -> Hint("Removes author, title and other metadata, scripts and attachments.")
                    }
                }
                RunButton(
                    text = "Choose output folder and start",
                    enabled = files.isNotEmpty() && (op != BatchOp.PROTECT || password.length >= 6),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else folder.launch(null)
                    },
                )
                Hint("Each result is saved as a new file, e.g. \"Report (compressed).pdf\", in the folder you choose.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

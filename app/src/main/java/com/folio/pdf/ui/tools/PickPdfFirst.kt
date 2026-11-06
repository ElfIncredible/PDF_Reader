@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.pdf.ui.components.EmptyState

/** Shows [content] for a PDF, asking the user to choose one first if none was given. */
@Composable
fun PickPdfFirst(
    initialUri: Uri?,
    kind: ToolKind,
    message: String,
    onBack: () -> Unit,
    content: @Composable (Uri) -> Unit,
) {
    var uri by rememberSaveable { mutableStateOf(initialUri?.toString()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) uri = picked.toString()
    }
    val current = uri
    if (current != null) {
        content(Uri.parse(current))
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            EmptyState(icon = kind.icon, title = "Choose a PDF", message = message) {
                Button(onClick = { pick.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Outlined.FileOpen, null)
                    Text("Choose PDF", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

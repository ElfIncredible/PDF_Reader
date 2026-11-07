package com.folio.pdf.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.folio.pdf.BuildConfig
import com.folio.pdf.net.FolioServer
import com.folio.pdf.FolioApp
import com.folio.pdf.data.AppSettings
import com.folio.pdf.data.ThemeMode
import com.folio.pdf.util.DeviceProfile
import com.folio.pdf.util.LiteMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as FolioApp).container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("Appearance")
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> "Match system"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    }
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = settings.themeMode == mode,
                            role = Role.RadioButton,
                            onClick = { scope.launch { container.settings.setThemeMode(mode) } },
                        ),
                        headlineContent = { Text(label) },
                        leadingContent = { RadioButton(selected = settings.themeMode == mode, onClick = null) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    title = "Wallpaper colours",
                    subtitle = "Tint the app with colours from your wallpaper",
                    checked = settings.dynamicColor,
                    onChange = { scope.launch { container.settings.setDynamicColor(it) } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Reading")
            SwitchRow(
                title = "Keep screen on",
                subtitle = "Stop the screen dimming while a document is open",
                checked = settings.keepScreenOn,
                onChange = { scope.launch { container.settings.setKeepScreenOn(it) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Performance")
            Column(Modifier.selectableGroup()) {
                LiteMode.entries.forEach { mode ->
                    val (label, hint) = when (mode) {
                        LiteMode.AUTO -> "Automatic" to "On for basic phones, off for faster ones"
                        LiteMode.ON -> "Lite mode always on" to "Smoothest on older phones and saves battery"
                        LiteMode.OFF -> "Off" to "Sharpest pages and fastest zoom; uses more memory"
                    }
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = settings.liteMode == mode,
                            role = Role.RadioButton,
                            onClick = { scope.launch { container.settings.setLiteMode(mode) } },
                        ),
                        headlineContent = { Text(label) },
                        supportingContent = { Text(hint) },
                        leadingContent = { RadioButton(selected = settings.liteMode == mode, onClick = null) },
                    )
                }
            }
            val liteOn = when (settings.liteMode) {
                LiteMode.AUTO -> DeviceProfile.isBasicPhone
                LiteMode.ON -> true
                LiteMode.OFF -> false
            }
            Text(
                (if (DeviceProfile.isBasicPhone) "This phone counts as a basic phone. " else "This phone has plenty of memory. ") +
                    "Lite mode is ${if (liteOn) "on" else "off"}. " +
                    "Lite renders pages a little softer, keeps fewer pages in memory, " +
                    "makes thumbnails one at a time and limits zoom to 3×.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Folio server (AI & conversions)")
            ServerSection(settings)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Privacy")
            ListItem(
                modifier = Modifier.clickable { confirmClear = true },
                headlineContent = { Text("Clear recent history") },
                supportingContent = { Text("Favourites are kept") },
            )
            ListItem(
                modifier = Modifier.clickable {
                    scope.launch { container.settings.setLibraryTreeUri(null) }
                },
                headlineContent = { Text("Forget browse folder") },
                supportingContent = { Text("You can pick a folder again from Browse") },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text("Folio PDF") },
                supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear recent history?") },
            text = { Text("Your files aren't deleted. Only the list of recently opened documents is cleared.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.recents.clearHistory() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerSection(settings: AppSettings) {
    val context = LocalContext.current
    val container = (context.applicationContext as FolioApp).container
    val scope = rememberCoroutineScope()
    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings.serverToken) { mutableStateOf(settings.serverToken) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val changed = url.trim().trimEnd('/') != settings.serverUrl || token.trim() != settings.serverToken

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Summaries, questions, tables, Office → PDF and PDF/A run on a small server you control " +
                "(the folio-server folder in the project). Reading, editing and everything else work without it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Server address") }, placeholder = { Text("http://192.168.1.20:8000") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("Access token (FOLIO_TOKEN)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        if (FolioServer.isInsecure(url)) {
            Text(
                "⚠️ This is a plain http:// address outside your home network: documents would travel unencrypted. Use https:// for servers on the internet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { scope.launch { container.settings.setServer(url, token); status = "Saved" } },
                enabled = changed,
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    testing = true
                    status = null
                    scope.launch {
                        container.settings.setServer(url, token)
                        status = try {
                            val h = FolioServer(context, url.trim().trimEnd('/'), token.trim()).health()
                            buildString {
                                append("✅ Connected (server ${h.version}). ")
                                append(if (h.ai) "AI: ${h.model ?: "on"}. " else "AI is off. ")
                                append(if (h.convert) "Office → PDF ✓. " else "No LibreOffice. ")
                                append(if (h.pdfa) "PDF/A ✓" else "No Ghostscript")
                                if (h.pdfa) append(if (h.validate) " + veraPDF." else ".")
                            }
                        } catch (e: Exception) {
                            "❌ " + (e.message ?: "Couldn't connect")
                        }
                        testing = false
                    }
                },
                enabled = url.isNotBlank() && !testing,
            ) { Text(if (testing) "Testing…" else "Test connection") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    SwitchRow(
        title = "Allow sending documents to this server",
        subtitle = "Needed for AI and conversions. If your server uses an online AI service, that service sees the text too.",
        checked = settings.serverConsent,
        onChange = { scope.launch { container.settings.setServerConsent(it) } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

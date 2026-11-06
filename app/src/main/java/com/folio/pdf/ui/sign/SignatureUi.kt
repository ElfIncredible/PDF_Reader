@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.pdf.ui.sign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.pdf.sign.SignatureStore
import com.folio.pdf.util.decodeScaledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val inkColors = listOf(Color(0xFF111111), Color(0xFF0D47A1), Color(0xFF1B5E20))

/** Pick a saved signature (or make a new one) to place on the page. */
@Composable
fun SignatureSheet(onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val signatures = remember(version) { SignatureStore.list(context) }
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your signatures", style = MaterialTheme.typography.titleLarge)
            if (signatures.isEmpty()) {
                Text(
                    "Make your signature once (draw it, type it or take a photo) and reuse it on any PDF. " +
                        "It stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Tap one to place it, then drag and resize it on the page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                ) {
                    items(signatures, key = { it.absolutePath }) { file ->
                        SignatureCard(
                            file = file,
                            onClick = { onPick(file) },
                            onDelete = {
                                SignatureStore.delete(file)
                                version++
                            },
                        )
                    }
                }
            }
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Outlined.Add, null)
                Text("New signature", Modifier.padding(start = 6.dp))
            }
            Box(Modifier.height(12.dp))
        }
    }

    if (creating) {
        CreateSignatureDialog(
            onDismiss = { creating = false },
            onSaved = { file ->
                creating = false
                version++
                onPick(file)
            },
        )
    }
}

@Composable
private fun SignatureCard(file: File, onClick: () -> Unit, onDelete: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.2f)) {
            image?.let {
                Image(
                    it,
                    contentDescription = "Saved signature",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Outlined.Delete, "Delete signature", tint = Color.Gray)
            }
        }
    }
}

private enum class SignMode(val label: String) { DRAW("Draw"), TYPE("Type"), PHOTO("Photo") }

@Composable
fun CreateSignatureDialog(onDismiss: () -> Unit, onSaved: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(SignMode.DRAW) }
    var ink by remember { mutableStateOf(inkColors[0]) }
    var saving by remember { mutableStateOf(false) }

    // Draw
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val strokeWidthPx = with(LocalDensity.current) { 3.dp.toPx() }

    // Type
    var typed by rememberSaveable { mutableStateOf("") }
    var style by rememberSaveable { mutableStateOf(SignatureStore.TypedStyle.SCRIPT) }

    // Photo
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var photoSource by remember { mutableStateOf<Bitmap?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val src = withContext(Dispatchers.IO) { decodeScaledBitmap(context, uri, 1600) }
            photoSource = src
            photo = src?.let { withContext(Dispatchers.Default) { SignatureStore.cleanPhoto(it, ink.toArgb()) } }
        }
    }

    val canSave = when (mode) {
        SignMode.DRAW -> strokes.isNotEmpty()
        SignMode.TYPE -> typed.isNotBlank()
        SignMode.PHOTO -> photo != null
    } && !saving

    fun save() {
        saving = true
        scope.launch {
            val bitmap: Bitmap? = when (mode) {
                SignMode.DRAW -> renderStrokes(strokes, padSize, strokeWidthPx * 1.6f, ink.toArgb())
                SignMode.TYPE -> SignatureStore.renderTyped(typed.trim(), style, ink.toArgb())
                SignMode.PHOTO -> photo
            }
            val file = bitmap?.let { b -> withContext(Dispatchers.IO) { SignatureStore.save(context, b) } }
            saving = false
            if (file != null) onSaved(file)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("New signature") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cancel") } },
                    actions = {
                        if (saving) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = ::save, enabled = canSave) { Text("Save") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TabRow(selectedTabIndex = mode.ordinal) {
                    SignMode.entries.forEach { m ->
                        Tab(selected = mode == m, onClick = { mode = m }, text = { Text(m.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ink", style = MaterialTheme.typography.labelLarge)
                    inkColors.forEach { c ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .border(if (c == ink) 3.dp else 1.dp, if (c == ink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                .padding(4.dp)
                                .background(c, CircleShape)
                                .clickable {
                                    ink = c
                                    photoSource?.let { src -> scope.launch { photo = withContext(Dispatchers.Default) { SignatureStore.cleanPhoto(src, c.toArgb()) } } }
                                },
                        )
                    }
                }

                when (mode) {
                    SignMode.DRAW -> {
                        Text(
                            "Sign with your finger in the box.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SignaturePad(
                            strokes = strokes,
                            ink = ink,
                            strokeWidthPx = strokeWidthPx,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f).onSizeChanged { padSize = it },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, null)
                                Text("Undo", Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
                        }
                    }
                    SignMode.TYPE -> {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it.take(60) },
                            label = { Text("Your name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SignatureStore.TypedStyle.entries.forEach { s ->
                                FilterChip(selected = style == s, onClick = { style = s }, label = { Text(s.label) })
                            }
                        }
                        if (typed.isNotBlank()) {
                            val preview = remember(typed, style, ink) {
                                SignatureStore.renderTyped(typed.trim(), style, ink.toArgb()).asImageBitmap()
                            }
                            PreviewBox(preview)
                        }
                    }
                    SignMode.PHOTO -> {
                        Text(
                            "Sign on plain white paper, then take or pick a clear, well-lit photo. " +
                                "Folio removes the paper and keeps just the ink.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Outlined.PhotoCamera, null)
                            Text(if (photo == null) "Choose photo" else "Choose a different photo", Modifier.padding(start = 6.dp))
                        }
                        photo?.let { PreviewBox(it.asImageBitmap()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBox(image: ImageBitmap) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().aspectRatio(2.4f),
    ) {
        Image(image, contentDescription = "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
    }
}

/** A white pad you sign on with a finger. */
@Composable
fun SignaturePad(
    strokes: MutableList<List<Offset>>,
    ink: Color,
    strokeWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val points = arrayListOf(down.position)
                        current = points.toList()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) {
                                points += change.position
                                current = points.toList()
                                change.consume()
                            }
                        }
                        if (points.size == 1) points += points[0] + Offset(0.5f, 0.5f)
                        strokes += points.toList()
                        current = emptyList()
                    }
                },
        ) {
            // Signing line
            val lineY = size.height * 0.75f
            drawLine(Color(0xFFBDBDBD), Offset(size.width * 0.08f, lineY), Offset(size.width * 0.92f, lineY), 2f)
            val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            (strokes + listOf(current)).forEach { pts ->
                if (pts.size >= 2) {
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    }
                    drawPath(path, ink, style = stroke)
                }
            }
        }
    }
}

/** Draws the strokes onto a transparent bitmap at 2× for crisp printing. */
private fun renderStrokes(strokes: List<List<Offset>>, size: IntSize, widthPx: Float, color: Int): Bitmap? {
    if (strokes.isEmpty() || size.width <= 0 || size.height <= 0) return null
    val scale = 2f
    val bitmap = Bitmap.createBitmap((size.width * scale).toInt(), (size.height * scale).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = widthPx * scale
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (pts in strokes) {
        if (pts.size < 2) continue
        val path = android.graphics.Path().apply {
            moveTo(pts[0].x * scale, pts[0].y * scale)
            for (i in 1 until pts.size) lineTo(pts[i].x * scale, pts[i].y * scale)
        }
        canvas.drawPath(path, paint)
    }
    return SignatureStore.trimTransparent(bitmap) ?: bitmap
}

@Suppress("unused")
private val signIcon = Icons.Outlined.Gesture

package com.vonu.pdf.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vonu.pdf.R
import com.vonu.pdf.ui.theme.Wordmark

private val Label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.5.sp)

/** Brand bar title: the Vonu PDF mark, a small "VONU" label and the product name. */
@Composable
fun VonuBrandTitle(name: String = "PDF") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Vonu $name" },
    ) {
        Image(painterResource(R.drawable.ic_vonu_mark), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("VONU", style = Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Small monospaced section label, as used across the brand ("SIGN & FILL", "CONTINUE READING"). */
@Composable
fun BrandLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Cold-start landing: mark, wordmark and one quiet progress line on the brand ground. */
@Composable
fun VonuLanding(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { contentDescription = "Vonu PDF is loading" },
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.ic_vonu_mark), contentDescription = null, modifier = Modifier.size(112.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "vonu",
                style = TextStyle(fontFamily = Wordmark, fontSize = 44.sp, letterSpacing = (-1).sp),
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "PDF",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 56.dp)
                .width(96.dp),
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

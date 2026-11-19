package com.vonu.pdf.util

import android.text.format.DateUtils
import java.util.Locale

fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

fun formatRelativeTime(epochMillis: Long): String = when {
    epochMillis <= 0 -> ""
    // The platform says "0 min. ago" for the first minute.
    System.currentTimeMillis() - epochMillis < DateUtils.MINUTE_IN_MILLIS -> "Just now"
    else -> DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

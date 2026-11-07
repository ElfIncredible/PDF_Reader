package com.vonu.pdf.util

import android.app.ActivityManager
import android.content.Context

/** Lite mode setting: decide automatically from the phone, or force it on/off. */
enum class LiteMode { AUTO, ON, OFF }

/**
 * How hard Vonu may work on this phone. In Lite mode, every heavy part of the app scales
 * down: page image sizes, caches, pre-rendering, OCR resolution and zoom, so it stays smooth
 * on phones with 1–2 GB of RAM.
 */
object DeviceProfile {
    /** True on basic phones (or when the user forces Lite mode on). Read everywhere; cheap. */
    @Volatile
    var lite: Boolean = false
        private set

    @Volatile
    var isBasicPhone: Boolean = false
        private set

    fun init(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        isBasicPhone = am == null || am.isLowRamDevice || am.memoryClass <= 192 || info.totalMem < 3L * 1024 * 1024 * 1024
        lite = isBasicPhone
    }

    fun apply(mode: LiteMode) {
        lite = when (mode) {
            LiteMode.AUTO -> isBasicPhone
            LiteMode.ON -> true
            LiteMode.OFF -> false
        }
    }

    /** Page images for page-by-page view and the editor, relative to screen width. */
    val renderScale: Float get() = if (lite) 1.25f else 2f

    /** Largest page bitmap, in pixels (4 bytes each). */
    val maxPagePixels: Long get() = if (lite) 5_000_000L else 12_000_000L

    /** Page cache gets 1/N of the app's memory. */
    val cacheDivisor: Int get() = if (lite) 14 else 8

    /** Pages rendered ahead while reading. */
    val prefetchPages: Int get() = if (lite) 1 else 2

    /** Widest image used for OCR and redaction. */
    val ocrMaxWidth: Int get() = if (lite) 1700 else 2600

    /** How many first-page thumbnails may be made at once. */
    val thumbnailWorkers: Int get() = if (lite) 1 else 2

    val maxZoom: Float get() = if (lite) 3f else 5f
}

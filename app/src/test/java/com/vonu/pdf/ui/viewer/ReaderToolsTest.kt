package com.vonu.pdf.ui.viewer

import com.vonu.pdf.ui.tools.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderToolsTest {

    private fun top(form: Boolean = false, scan: Boolean = false, uses: Map<String, Int> = emptyMap()) =
        rankReaderTools(locked = false, hasForm = form, isScan = scan, uses = uses).take(READER_STRIP_SIZE)

    @Test
    fun newPhoneGetsTheDefaultOrder() {
        assertEquals(listOf(ToolKind.SIGN, ToolKind.ORGANIZE, ToolKind.COMPRESS, ToolKind.OCR), top())
    }

    @Test
    fun mostUsedToolsComeFirst() {
        val uses = mapOf(ToolKind.REDACT.id to 5, ToolKind.SPLIT.id to 2)
        assertEquals(listOf(ToolKind.REDACT, ToolKind.SPLIT, ToolKind.SIGN, ToolKind.ORGANIZE), top(uses = uses))
    }

    @Test
    fun whatTheFileNeedsBeatsUsage() {
        val uses = mapOf(ToolKind.REDACT.id to 9)
        assertEquals(
            listOf(ToolKind.FILL_FORM, ToolKind.OCR, ToolKind.REDACT, ToolKind.SIGN),
            top(form = true, scan = true, uses = uses),
        )
    }

    @Test
    fun lockedFileOnlyOffersUnlock() {
        assertEquals(listOf(ToolKind.UNLOCK), rankReaderTools(locked = true, hasForm = true, isScan = false, uses = emptyMap()))
    }

    @Test
    fun everyToolAppearsOnceAndHeldBackToolsNever() {
        val all = rankReaderTools(locked = false, hasForm = true, isScan = true, uses = mapOf(ToolKind.OCR.id to 3))
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.none { it.comingSoon })
    }
}

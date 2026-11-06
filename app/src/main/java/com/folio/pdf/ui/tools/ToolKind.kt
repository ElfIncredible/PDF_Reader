package com.folio.pdf.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolKind(val id: String, val title: String, val description: String, val icon: ImageVector) {
    ASSISTANT("assistant", "AI assistant", "Summarise, ask questions, extract tables, translate", Icons.Outlined.AutoAwesome),
    CONVERT_OFFICE("office-to-pdf", "Word, Excel, PowerPoint → PDF", "Convert Office documents (uses your Folio server)", Icons.Outlined.Transform),
    SIGN("sign", "Sign a PDF", "Add your handwritten signature or initials", Icons.Outlined.Gesture),
    FILL_FORM("fill-form", "Fill a form", "Type into a PDF form's fields and save it", Icons.Outlined.Assignment),
    SCAN("scan", "Scan documents", "Use the camera to make a clean, searchable PDF", Icons.Outlined.DocumentScanner),
    OCR("ocr", "Make PDF searchable", "Recognise text in scanned pages (OCR)", Icons.Outlined.FindInPage),
    DIGITAL_SIGN("digital-sign", "Sign with digital ID", "A certificate signature that proves who signed", Icons.Outlined.VerifiedUser),
    PROTECT("protect", "Add a password", "Lock a PDF so it needs a password to open", Icons.Outlined.Lock),
    UNLOCK("unlock", "Remove a password", "Unlock a PDF you know the password for", Icons.Outlined.LockOpen),
    REDACT("redact", "Redact", "Black out private information, for good", Icons.Outlined.VisibilityOff),
    COMPARE("compare", "Compare PDFs", "See what changed between two versions", Icons.Outlined.Compare),
    BATCH("batch", "Batch process", "Compress, OCR or protect many PDFs at once", Icons.Outlined.Layers),
    METADATA("metadata", "Document info & PDF/A", "Edit title and author, remove hidden data, check PDF/A", Icons.Outlined.Info),
    MERGE("merge", "Merge PDFs", "Combine several PDFs into one", Icons.Outlined.MergeType),
    SPLIT("split", "Split PDF", "Break a PDF into smaller files", Icons.Outlined.ContentCut),
    ORGANIZE("organize", "Organize pages", "Reorder, rotate, delete or extract pages", Icons.Outlined.GridView),
    COMPRESS("compress", "Compress PDF", "Make a PDF smaller to share or email", Icons.Outlined.Compress),
    IMAGES_TO_PDF("images-to-pdf", "Images to PDF", "Turn photos or screenshots into a PDF", Icons.Outlined.Image),
    PDF_TO_IMAGES("pdf-to-images", "PDF to images", "Save every page as a JPG or PNG", Icons.Outlined.PhotoLibrary),
    TEXT_TO_PDF("text-to-pdf", "Text to PDF", "Convert a .txt file into a PDF", Icons.Outlined.Description),
    ;

    companion object {
        fun fromId(id: String?): ToolKind? = entries.firstOrNull { it.id == id }
    }
}

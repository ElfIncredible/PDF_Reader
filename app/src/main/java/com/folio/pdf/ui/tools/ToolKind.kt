package com.folio.pdf.ui.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolKind(val id: String, val title: String, val description: String, val icon: ImageVector) {
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

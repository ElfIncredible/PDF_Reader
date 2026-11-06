package com.folio.pdf.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation

/**
 * Removes data people often don't know is inside a PDF: author/title metadata, XMP,
 * scripts, embedded attachments, page thumbnails and app-private data.
 */
object Sanitizer {
    private fun n(s: String) = COSName.getPDFName(s)

    fun clean(doc: PDDocument) {
        doc.documentInformation = PDDocumentInformation()
        val catalog = doc.documentCatalog
        catalog.metadata = null
        catalog.cosObject.removeItem(n("OpenAction"))
        catalog.cosObject.removeItem(n("AA"))
        catalog.cosObject.removeItem(n("PieceInfo"))
        catalog.names?.cosObject?.let {
            it.removeItem(n("JavaScript"))
            it.removeItem(n("EmbeddedFiles"))
        }
        for (page in doc.pages) {
            page.cosObject.removeItem(n("Thumb"))
            page.cosObject.removeItem(n("PieceInfo"))
            page.cosObject.removeItem(n("AA"))
        }
    }
}

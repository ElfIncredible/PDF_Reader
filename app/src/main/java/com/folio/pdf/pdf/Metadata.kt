package com.folio.pdf.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = "",
    val created: Long? = null,
    val modified: Long? = null,
    val pages: Int = 0,
    val version: Float = 0f,
    val pdfa: String? = null,
)

enum class CheckStatus { PASS, WARN, FAIL }

data class PdfaCheck(val title: String, val status: CheckStatus, val detail: String)

object Metadata {
    private fun n(s: String) = COSName.getPDFName(s)

    suspend fun read(context: Context, input: Uri): PdfMetadata = withContext(Dispatchers.IO) {
        PdfTools.openForEditing(context, input).use { doc ->
            val info = doc.documentInformation
            PdfMetadata(
                title = info.title.orEmpty(),
                author = info.author.orEmpty(),
                subject = info.subject.orEmpty(),
                keywords = info.keywords.orEmpty(),
                creator = info.creator.orEmpty(),
                producer = info.producer.orEmpty(),
                created = info.creationDate?.timeInMillis,
                modified = info.modificationDate?.timeInMillis,
                pages = doc.numberOfPages,
                version = doc.version,
                pdfa = pdfaClaim(doc),
            )
        }
    }

    /** Saves new title/author/subject/keywords (into both the Info dictionary and XMP). */
    suspend fun write(context: Context, input: Uri, m: PdfMetadata, target: File) = withContext(Dispatchers.IO) {
        PdfTools.openForEditing(context, input).use { doc ->
            val info = doc.documentInformation
            info.title = m.title.ifBlank { null }
            info.author = m.author.ifBlank { null }
            info.subject = m.subject.ifBlank { null }
            info.keywords = m.keywords.ifBlank { null }
            info.modificationDate = Calendar.getInstance()
            doc.documentInformation = info
            // Keep XMP in step (PDF/A requires both to match), preserving any PDF/A claim.
            val existing = doc.documentCatalog.metadata
            if (existing != null) {
                val claim = readXmp(doc)?.let { Regex("""pdfaid:part[=>"']+\s*"?(\d)""").find(it)?.groupValues?.get(1) }
                val conformance = readXmp(doc)?.let { Regex("""pdfaid:conformance[=>"']+\s*"?([ABUabu])""").find(it)?.groupValues?.get(1) }
                val xmp = buildXmp(m, claim, conformance)
                val meta = PDMetadata(doc)
                meta.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
                doc.documentCatalog.metadata = meta
            }
            doc.save(target)
        }
    }

    /** Removes metadata, scripts, attachments and other hidden data. */
    suspend fun removeHiddenData(context: Context, input: Uri, target: File) = withContext(Dispatchers.IO) {
        PdfTools.openForEditing(context, input).use { doc ->
            Sanitizer.clean(doc)
            doc.save(target)
        }
    }

    private fun readXmp(doc: PDDocument): String? =
        runCatching { doc.documentCatalog.metadata?.toByteArray()?.toString(Charsets.UTF_8) }.getOrNull()

    private fun pdfaClaim(doc: PDDocument): String? {
        val xmp = readXmp(doc) ?: return null
        val part = Regex("""pdfaid:part[=>"']+\s*"?(\d)""").find(xmp)?.groupValues?.get(1) ?: return null
        val conformance = Regex("""pdfaid:conformance[=>"']+\s*"?([ABUabu])""").find(xmp)?.groupValues?.get(1).orEmpty()
        return "PDF/A-$part${conformance.lowercase()}"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun buildXmp(m: PdfMetadata, pdfaPart: String?, conformance: String?): String {
        val pdfa = if (pdfaPart != null) {
            """<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/"><pdfaid:part>$pdfaPart</pdfaid:part>""" +
                (conformance?.let { "<pdfaid:conformance>${it.uppercase()}</pdfaid:conformance>" } ?: "") + "</rdf:Description>"
        } else ""
        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(m.title)}</rdf:li></rdf:Alt></dc:title>
<dc:creator><rdf:Seq><rdf:li>${esc(m.author)}</rdf:li></rdf:Seq></dc:creator>
<dc:description><rdf:Alt><rdf:li xml:lang="x-default">${esc(m.subject)}</rdf:li></rdf:Alt></dc:description>
</rdf:Description>
<rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/"><pdf:Keywords>${esc(m.keywords)}</pdf:Keywords></rdf:Description>
$pdfa
</rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>"""
    }

    /**
     * Quick PDF/A readiness check: the problems that most often stop a file being archival.
     * (Full validation needs veraPDF, planned for the Folio server in Phase 7.)
     */
    suspend fun pdfaCheck(context: Context, input: Uri): List<PdfaCheck> = withContext(Dispatchers.IO) {
        val checks = ArrayList<PdfaCheck>()
        val encrypted = runCatching { PdfSecurity.needsPassword(context, input) }.getOrDefault(false)
        if (encrypted) {
            checks += PdfaCheck("Encryption", CheckStatus.FAIL, "PDF/A files can't be password-protected.")
            return@withContext checks
        }
        PdfTools.openForEditing(context, input).use { doc ->
            val claim = pdfaClaim(doc)
            checks += if (claim != null) PdfaCheck("PDF/A identification", CheckStatus.PASS, "The file says it's $claim.")
            else PdfaCheck("PDF/A identification", CheckStatus.WARN, "The file doesn't declare a PDF/A level.")

            val intents = runCatching { doc.documentCatalog.outputIntents }.getOrDefault(emptyList())
            checks += if (intents.isNotEmpty()) PdfaCheck("Colour profile", CheckStatus.PASS, "Has an output intent (colour profile).")
            else PdfaCheck("Colour profile", CheckStatus.FAIL, "No output intent; PDF/A needs an embedded colour profile.")

            val missingFonts = sortedSetOf<String>()
            var transparency = false
            val seen = HashSet<Any>()
            fun scan(res: PDResources?, depth: Int) {
                if (res == null || depth > 6 || !seen.add(res.cosObject)) return
                for (name in res.fontNames) {
                    val f = runCatching { res.getFont(name) }.getOrNull() ?: continue
                    if (!f.isEmbedded) missingFonts += f.name ?: name.name
                }
                for (name in res.extGStateNames) {
                    val gs = runCatching { res.getExtGState(name) }.getOrNull() ?: continue
                    val d = gs.cosObject
                    if (d.getFloat(n("CA"), 1f) < 1f || d.getFloat(n("ca"), 1f) < 1f) transparency = true
                    val sm = d.getDictionaryObject(n("SMask"))
                    if (sm != null && sm != n("None")) transparency = true
                }
                for (name in res.xObjectNames) {
                    when (val x = runCatching { res.getXObject(name) }.getOrNull()) {
                        is PDImageXObject -> if (x.cosObject.getDictionaryObject(n("SMask")) != null) transparency = true
                        is PDFormXObject -> scan(x.resources, depth + 1)
                    }
                }
            }
            for (page in doc.pages) scan(page.resources, 0)
            checks += if (missingFonts.isEmpty()) PdfaCheck("Fonts", CheckStatus.PASS, "All fonts are embedded.")
            else PdfaCheck("Fonts", CheckStatus.FAIL, "Not embedded: ${missingFonts.take(6).joinToString()}${if (missingFonts.size > 6) "…" else ""}")

            checks += if (transparency) PdfaCheck("Transparency", CheckStatus.WARN, "Uses transparency: fine for PDF/A-2 and -3, not allowed in PDF/A-1.")
            else PdfaCheck("Transparency", CheckStatus.PASS, "No transparency.")

            val names = doc.documentCatalog.names?.cosObject
            val hasJs = names?.containsKey(n("JavaScript")) == true ||
                doc.documentCatalog.cosObject.containsKey(n("AA"))
            checks += if (hasJs) PdfaCheck("Scripts & actions", CheckStatus.FAIL, "Contains JavaScript or automatic actions.")
            else PdfaCheck("Scripts & actions", CheckStatus.PASS, "No scripts.")

            val attachments = names?.containsKey(n("EmbeddedFiles")) == true
            checks += if (attachments) PdfaCheck("Attachments", CheckStatus.WARN, "Has attached files: allowed only in PDF/A-3.")
            else PdfaCheck("Attachments", CheckStatus.PASS, "No attached files.")

            val multimedia = doc.pages.any { p ->
                runCatching { p.annotations }.getOrDefault(emptyList()).any { it.subtype in setOf("Movie", "Sound", "Screen", "3D", "RichMedia") }
            }
            if (multimedia) checks += PdfaCheck("Media", CheckStatus.FAIL, "Contains audio, video or 3D content.")

            checks += if (readXmp(doc) != null) PdfaCheck("XMP metadata", CheckStatus.PASS, "Has XMP metadata.")
            else PdfaCheck("XMP metadata", CheckStatus.FAIL, "No XMP metadata.")
        }
        checks
    }
}

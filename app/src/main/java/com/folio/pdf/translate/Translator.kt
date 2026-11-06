package com.folio.pdf.translate

import com.folio.pdf.ocr.await
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

data class Language(val code: String, val name: String)

/**
 * On-device translation with ML Kit: the language model (~30 MB each) is downloaded once,
 * then translation works offline and nothing is uploaded.
 */
object Translator {

    /** All languages ML Kit can translate, by name in the phone's language. */
    val languages: List<Language> by lazy {
        TranslateLanguage.getAllLanguages()
            .map { Language(it, Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { c -> c.uppercase() }) }
            .sortedBy { it.name }
    }

    /** The phone's language if supported, otherwise English. */
    fun defaultTarget(): String {
        val phone = Locale.getDefault().language
        return TranslateLanguage.fromLanguageTag(phone) ?: TranslateLanguage.ENGLISH
    }

    /** Best guess of the text's language, or null if unsure / unsupported. */
    suspend fun detect(text: String): String? {
        val tag = LanguageIdentification.getClient().identifyLanguage(text.take(2000)).await()
        if (tag == "und") return null
        return TranslateLanguage.fromLanguageTag(tag)
    }

    /**
     * Translates [text] from [source] to [target]. Long text is sent paragraph by paragraph.
     * [onProgress] gets 0..1.
     */
    suspend fun translate(
        text: String,
        source: String,
        target: String,
        wifiOnly: Boolean,
        onProgress: (Float) -> Unit = {},
    ): String {
        if (source == target) return text
        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
        )
        try {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            client.downloadModelIfNeeded(conditions).await()
            val paragraphs = text.split(Regex("""\n\s*\n|\n""")).filter { it.isNotBlank() }
            val out = StringBuilder()
            paragraphs.forEachIndexed { i, p ->
                out.append(client.translate(p.trim()).await()).append("\n\n")
                onProgress((i + 1f) / paragraphs.size)
            }
            return out.toString().trim()
        } finally {
            client.close()
        }
    }
}

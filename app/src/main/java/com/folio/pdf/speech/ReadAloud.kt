package com.folio.pdf.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.folio.pdf.util.Reflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class ReadAloudState(
    val active: Boolean = false,
    val speaking: Boolean = false,
    val page: Int = 0,
    val rate: Float = 1f,
    val error: String? = null,
)

/**
 * Reads the document aloud with the phone's built-in text-to-speech (no download, works
 * offline on almost every Android phone). Reads paragraph by paragraph, page after page.
 */
class ReadAloud(context: Context, private val pageText: (Int) -> String?, private val pageCount: Int) {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private var ready = false
    private var pendingStart: Int? = null
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var generation = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            _state.update { it.copy(active = false, speaking = false, error = "This phone has no text-to-speech voice. Install one in Settings → Accessibility → Text-to-speech.") }
        } else {
            pendingStart?.let { start(it) }
            pendingStart = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = advance(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = advance(utteranceId)
        })
    }

    /** Starts reading at [page] (0-based). */
    fun start(page: Int, languageTag: String? = null) {
        if (!ready) {
            pendingStart = page
            _state.update { it.copy(active = true, page = page) }
            return
        }
        languageTag?.let { tag ->
            val locale = Locale.forLanguageTag(tag)
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(locale)
        }
        tts.setSpeechRate(_state.value.rate)
        loadPage(page)
    }

    fun pause() {
        generation++
        tts.stop()
        _state.update { it.copy(speaking = false) }
    }

    fun resume() {
        if (!_state.value.active) return
        _state.update { it.copy(speaking = true) }
        speakFrom(chunkIndex)
    }

    fun stop() {
        generation++
        tts.stop()
        _state.value = ReadAloudState(rate = _state.value.rate)
    }

    fun setRate(rate: Float) {
        _state.update { it.copy(rate = rate) }
        tts.setSpeechRate(rate)
        if (_state.value.speaking) {
            generation++
            tts.stop()
            speakFrom(chunkIndex)
        }
    }

    fun shutdown() {
        generation++
        runCatching { tts.stop(); tts.shutdown() }
    }

    private fun loadPage(page: Int) {
        var p = page
        // Skip pages without text (pictures, blank pages).
        while (p < pageCount) {
            val paragraphs = Reflow.paragraphs(pageText(p).orEmpty()).flatMap { split(it) }
            if (paragraphs.isNotEmpty()) {
                chunks = paragraphs
                chunkIndex = 0
                _state.update { it.copy(active = true, speaking = true, page = p, error = null) }
                speakFrom(0)
                return
            }
            p++
        }
        _state.update { it.copy(active = false, speaking = false, error = if (page == 0) "There's no text to read. If this is a scan, make it searchable first." else null) }
    }

    private fun speakFrom(index: Int) {
        val gen = generation
        for (i in index until chunks.size) {
            tts.speak(chunks[i], TextToSpeech.QUEUE_ADD, Bundle(), "$gen:${_state.value.page}:$i")
        }
    }

    private fun advance(utteranceId: String?) {
        val parts = utteranceId?.split(':') ?: return
        if (parts.size != 3 || parts[0].toIntOrNull() != generation) return
        val i = parts[2].toIntOrNull() ?: return
        chunkIndex = i + 1
        if (chunkIndex >= chunks.size && _state.value.speaking) loadPage(_state.value.page + 1)
    }

    /** TTS engines limit one utterance (~4000 chars); split long paragraphs at sentence ends. */
    private fun split(paragraph: String): List<String> {
        val max = minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 3000)
        if (paragraph.length <= max) return listOf(paragraph)
        val out = ArrayList<String>()
        var rest = paragraph
        while (rest.length > max) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';'), max).takeIf { it > max / 2 } ?: max
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }
}

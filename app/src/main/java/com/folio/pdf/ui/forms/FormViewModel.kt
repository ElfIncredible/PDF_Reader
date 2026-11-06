package com.folio.pdf.ui.forms

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.pdf.AppContainer
import com.folio.pdf.pdf.FormField
import com.folio.pdf.pdf.Forms
import com.folio.pdf.pdf.Thumbnails
import com.folio.pdf.pdf.ToolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class FormUiState(
    val loading: Boolean = true,
    val title: String = "",
    val fields: List<FormField> = emptyList(),
    /** Current answers: field name → value ("true"/"false" for check boxes). */
    val values: Map<String, String> = emptyMap(),
    val original: Map<String, String> = emptyMap(),
    val xfaOnly: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = values != original
}

sealed interface FormEvent {
    data class Message(val text: String) : FormEvent
    data object NeedCopy : FormEvent
    data class Open(val uri: Uri) : FormEvent
}

class FormViewModel(
    private val uri: Uri,
    private val context: Context,
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(FormUiState())
    val state: StateFlow<FormUiState> = _state.asStateFlow()

    private val _events = Channel<FormEvent>(Channel.BUFFERED)
    val events: Flow<FormEvent> = _events.receiveAsFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            container.documents.persistReadPermission(uri)
            val name = container.documents.describe(uri).name.substringBeforeLast('.')
            val info = Forms.read(context, uri)
            val values = info.fields.associate { f ->
                f.name to when (f) {
                    is FormField.Text -> f.value
                    is FormField.Check -> f.checked.toString()
                    is FormField.Choice -> f.value.orEmpty()
                    is FormField.SignatureSlot -> ""
                }
            }
            _state.value = FormUiState(
                loading = false, title = name, fields = info.fields,
                values = values, original = values, xfaOnly = info.isXfaOnly,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            _state.value = FormUiState(loading = false, error = e.message)
        } catch (e: Exception) {
            _state.value = FormUiState(loading = false, error = "Couldn't read this PDF's form.")
        }
    }

    fun set(name: String, value: String) = _state.update { it.copy(values = it.values + (name to value)) }

    /** [copyTarget] null → overwrite the original. */
    fun save(copyTarget: Uri?, flatten: Boolean) = viewModelScope.launch {
        val s = _state.value
        if (s.saving) return@launch
        _state.update { it.copy(saving = true) }
        // Only write what changed (and everything when flattening).
        val changed = if (flatten) s.values else s.values.filter { (k, v) -> s.original[k] != v }
        val temp = File.createTempFile("folio-form", ".pdf", context.cacheDir)
        try {
            val failed = Forms.fill(context, uri, changed, flatten, temp)
            if (copyTarget != null) {
                container.documents.overwrite(copyTarget, temp)
                container.documents.persistReadPermission(copyTarget)
                _state.update { it.copy(original = it.values) }
                _events.send(FormEvent.Open(copyTarget))
            } else {
                container.documents.overwrite(uri, temp)
                Thumbnails.invalidate(uri.toString())
                if (flatten) load() else _state.update { it.copy(original = it.values) }
                _events.send(FormEvent.Message("Saved"))
            }
            if (failed.isNotEmpty()) {
                _events.send(FormEvent.Message("${failed.size} fields couldn't show some characters with this form's font"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            _events.send(FormEvent.NeedCopy)
        } catch (e: java.io.FileNotFoundException) {
            _events.send(FormEvent.NeedCopy)
        } catch (e: Exception) {
            _events.send(FormEvent.Message("Couldn't save: ${e.message ?: "unknown error"}"))
        } finally {
            temp.delete()
            _state.update { it.copy(saving = false) }
        }
    }
}

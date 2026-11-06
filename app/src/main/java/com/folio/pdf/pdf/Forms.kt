package com.folio.pdf.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A fillable field, flattened into something a simple form screen can show. */
sealed interface FormField {
    /** Fully-qualified name: unique key used to write the value back. */
    val name: String
    val label: String
    /** Zero-based page of the field's first widget, or -1. */
    val page: Int
    val readOnly: Boolean

    data class Text(
        override val name: String, override val label: String, override val page: Int, override val readOnly: Boolean,
        val value: String, val multiline: Boolean, val maxLength: Int,
    ) : FormField

    data class Check(
        override val name: String, override val label: String, override val page: Int, override val readOnly: Boolean,
        val checked: Boolean,
    ) : FormField

    /** Radio group, combo box or list box: pick one of [options]. */
    data class Choice(
        override val name: String, override val label: String, override val page: Int, override val readOnly: Boolean,
        val options: List<String>,
        /** What gets written for each option (may differ from what's shown). */
        val exportValues: List<String>,
        val value: String?,
        val editable: Boolean,
    ) : FormField

    /** An empty signature slot: shown so people know to sign. */
    data class SignatureSlot(
        override val name: String, override val label: String, override val page: Int, override val readOnly: Boolean,
        val signed: Boolean,
    ) : FormField
}

data class FormInfo(val fields: List<FormField>, val isXfaOnly: Boolean)

object Forms {

    fun countFillable(doc: PDDocument): Int = runCatching {
        doc.documentCatalog.acroForm?.fieldTree?.count {
            it is PDTerminalField && it !is PDSignatureField && it.fieldType != "Btn" || it is PDCheckBox || it is PDRadioButton
        } ?: 0
    }.getOrDefault(0)

    suspend fun read(context: Context, input: Uri): FormInfo = withContext(Dispatchers.IO) {
        PdfTools.openForEditing(context, input).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return@use FormInfo(emptyList(), false)
            val fields = form.fieldTree.mapNotNull { f -> runCatching { describe(doc, f) }.getOrNull() }
            FormInfo(fields, isXfaOnly = fields.isEmpty() && form.hasXFA())
        }
    }

    private fun describe(doc: PDDocument, f: PDField): FormField? {
        if (f !is PDTerminalField) return null
        val name = f.fullyQualifiedName ?: return null
        val label = (f.alternateFieldName?.takeIf { it.isNotBlank() } ?: prettify(f.partialName ?: name)).trim()
        val page = f.widgets.firstOrNull()?.page?.let { doc.pages.indexOf(it) } ?: -1
        val readOnly = f.isReadOnly
        return when (f) {
            is PDTextField -> FormField.Text(name, label, page, readOnly, f.value.orEmpty(), f.isMultiline, f.maxLen)
            is PDCheckBox -> FormField.Check(name, label, page, readOnly, f.isChecked)
            is PDRadioButton -> {
                val options = f.onValues.toList().sorted()
                FormField.Choice(name, label, page, readOnly, options, options, f.value?.takeIf { it != "Off" }, editable = false)
            }
            is PDChoice -> {
                val shown = f.optionsDisplayValues
                val exports = f.optionsExportValues.ifEmpty { shown }
                FormField.Choice(
                    name, label, page, readOnly, shown, exports, f.value.firstOrNull(),
                    editable = f is PDComboBox && f.isEdit,
                )
            }
            is PDSignatureField -> FormField.SignatureSlot(name, label, page, readOnly, signed = f.value != null)
            else -> null // push buttons
        }
    }

    /** "first_name" → "First name". */
    private fun prettify(raw: String): String =
        raw.replace('_', ' ').replace('.', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim().lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Writes [values] (field name → text, "true"/"false" for check boxes, or an
     * export value for choices) and saves to [target]. With [flatten] the answers become
     * part of the page and can no longer be edited. Returns names that couldn't be set.
     */
    suspend fun fill(
        context: Context,
        input: Uri,
        values: Map<String, String>,
        flatten: Boolean,
        target: File,
    ): List<String> = withContext(Dispatchers.IO) {
        val failed = ArrayList<String>()
        PdfTools.openForEditing(context, input).use { doc ->
            val form = doc.documentCatalog.acroForm ?: throw ToolException("This PDF has no form fields")
            for ((name, value) in values) {
                val field = form.getField(name) ?: continue
                val ok = runCatching {
                    when (field) {
                        is PDCheckBox -> if (value == "true") field.check() else field.unCheck()
                        is PDRadioButton -> if (value.isEmpty()) field.setValue("Off") else field.setValue(value)
                        is PDChoice -> field.setValue(value)
                        is PDTextField -> field.value = value
                        else -> Unit
                    }
                }.isSuccess
                if (!ok) failed += name
            }
            if (flatten) {
                runCatching { form.flatten() }.onFailure { throw ToolException("Couldn't flatten this form: ${it.message}") }
            }
            doc.save(target)
        }
        failed
    }
}

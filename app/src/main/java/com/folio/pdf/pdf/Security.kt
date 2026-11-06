package com.folio.pdf.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

data class Permissions(
    val print: Boolean = true,
    val copy: Boolean = true,
    val edit: Boolean = true,
)

/** Password protection with AES-256 (the strongest standard PDF encryption). */
object PdfSecurity {

    /** True if the PDF can't be opened without a password. */
    suspend fun needsPassword(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use {
                PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()).close()
            }
            false
        } catch (e: InvalidPasswordException) {
            true
        }
    }

    /**
     * Locks [input] with [openPassword]. With restrictions, a separate owner password is
     * set (random if blank) so the limits can't simply be switched off.
     */
    suspend fun protect(
        context: Context,
        input: Uri,
        openPassword: String,
        ownerPassword: String,
        permissions: Permissions,
        target: File,
    ) = withContext(Dispatchers.IO) {
        PdfTools.openForEditing(context, input).use { doc ->
            val access = AccessPermission().apply {
                setCanPrint(permissions.print)
                setCanExtractContent(permissions.copy)
                setCanModify(permissions.edit)
                setCanModifyAnnotations(permissions.edit)
                setCanAssembleDocument(permissions.edit)
                setCanFillInForm(true)
                setCanExtractForAccessibility(true)
            }
            val owner = ownerPassword.ifBlank { randomPassword() }
            val policy = StandardProtectionPolicy(owner, openPassword, access).apply { encryptionKeyLength = 256 }
            doc.protect(policy)
            doc.save(target)
        }
    }

    /** Removes the password and any restrictions. */
    suspend fun unlock(context: Context, input: Uri, password: String, target: File) = withContext(Dispatchers.IO) {
        PdfTools.openForEditing(context, input, password).use { it.save(target) }
    }

    /** A private decrypted copy for viewing a protected PDF. Delete it when done. */
    suspend fun decryptedCopy(context: Context, input: Uri, password: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "unlocked").apply { mkdirs() }
        val target = File(dir, "${input.toString().hashCode()}.pdf")
        unlock(context, input, password, target)
        target
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

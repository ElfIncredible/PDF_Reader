package com.folio.pdf.sign

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.Selector

data class SignatureReport(
    val signer: String,
    val date: String?,
    /** The signed bytes still match the signature. null = couldn't check. */
    val intact: Boolean?,
    /** False if the file was changed (e.g. annotated) after this signature. */
    val coversWholeFile: Boolean,
    val selfSigned: Boolean,
    val issuer: String?,
)

/** Checks the digital signatures in a PDF: is each one intact, and who issued the certificate? */
object SignatureCheck {
    private val bc = BouncyCastleProvider()

    suspend fun verify(context: Context, input: Uri): List<SignatureReport> = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(input)?.use { it.readBytes() } ?: return@withContext emptyList()
        runCatching {
            PDDocument.load(bytes.inputStream(), MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                doc.signatureDictionaries.map { sig ->
                    val date = sig.signDate?.time?.let { java.text.DateFormat.getDateTimeInstance().format(it) }
                    val range = sig.byteRange
                    val coversAll = range != null && range.size == 4 && range[2] + range[3] == bytes.size
                    var intact: Boolean? = null
                    var selfSigned = false
                    var issuer: String? = null
                    var signer = sig.name ?: "Unknown signer"
                    runCatching {
                        val signed = sig.getSignedContent(bytes)
                        val contents = sig.getContents(bytes)
                        val cms = CMSSignedData(CMSProcessableByteArray(signed), contents)
                        val info = cms.signerInfos.signers.first()
                        @Suppress("UNCHECKED_CAST")
                        val holder = cms.certificates.getMatches(info.sid as Selector<X509CertificateHolder>).first()
                        intact = info.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(holder))
                        selfSigned = holder.subject == holder.issuer
                        issuer = holder.issuer.toString().substringAfter("CN=").substringBefore(',')
                        if (sig.name == null) signer = holder.subject.toString().substringAfter("CN=").substringBefore(',')
                    }
                    SignatureReport(signer, date, intact, coversAll, selfSigned, issuer)
                }
            }
        }.getOrDefault(emptyList())
    }
}

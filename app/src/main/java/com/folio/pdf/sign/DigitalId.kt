package com.folio.pdf.sign

import android.content.Context
import android.net.Uri
import com.folio.pdf.pdf.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/** What we show about the saved digital ID (kept outside the password-protected file). */
data class DigitalIdInfo(
    val name: String,
    val email: String,
    val issuer: String,
    val expires: Long,
    val selfSigned: Boolean,
)

/**
 * One digital ID (certificate + private key) per app, stored as a password-protected
 * PKCS#12 file in private storage. The password is never saved: it's asked for each signing.
 */
object DigitalIds {
    // Our own BouncyCastle instance; Android ships a cut-down provider under the same name.
    private val bc = BouncyCastleProvider()

    private fun file(context: Context) = File(context.filesDir, "digital-id.p12")
    private fun prefs(context: Context) = context.getSharedPreferences("digital_id", Context.MODE_PRIVATE)

    fun info(context: Context): DigitalIdInfo? {
        if (!file(context).exists()) return null
        val p = prefs(context)
        return DigitalIdInfo(
            name = p.getString("name", "").orEmpty(),
            email = p.getString("email", "").orEmpty(),
            issuer = p.getString("issuer", "").orEmpty(),
            expires = p.getLong("expires", 0),
            selfSigned = p.getBoolean("self", false),
        )
    }

    fun delete(context: Context) {
        file(context).delete()
        prefs(context).edit().clear().apply()
    }

    /** Imports a .p12 / .pfx file (from a company, a government or a certificate authority). */
    suspend fun import(context: Context, source: Uri, password: CharArray): DigitalIdInfo = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw ToolException("Couldn't read that file")
        val store = KeyStore.getInstance("PKCS12")
        try {
            store.load(bytes.inputStream(), password)
        } catch (e: IOException) {
            throw ToolException("Wrong password, or this isn't a .p12 / .pfx digital ID")
        }
        val (_, chain) = firstKey(store, password)
        file(context).writeBytes(bytes)
        remember(context, chain[0] as X509Certificate)
    }

    /**
     * Creates a personal, self-signed digital ID. It proves a document hasn't changed since
     * you signed it, but other people's apps will say the signer isn't verified by an authority.
     */
    suspend fun create(context: Context, name: String, email: String, password: CharArray): DigitalIdInfo =
        withContext(Dispatchers.Default) {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
                addRDN(BCStyle.CN, name)
                if (email.isNotBlank()) addRDN(BCStyle.EmailAddress, email)
            }.build()
            val now = Date()
            val until = Calendar.getInstance().apply { add(Calendar.YEAR, 5) }.time
            val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now.time), now, until, subject, keys.public)
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keys.private)
            val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))

            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry("folio", keys.private, password, arrayOf<Certificate>(cert))
            FileOutputStream(file(context)).use { store.store(it, password) }
            remember(context, cert, emailOverride = email)
        }

    private fun remember(context: Context, cert: X509Certificate, emailOverride: String? = null): DigitalIdInfo {
        val subject = cert.subjectX500Principal.name
        val issuer = cert.issuerX500Principal.name
        val info = DigitalIdInfo(
            name = rdn(subject, "CN") ?: subject,
            // RFC 2253 writes e-mail addresses hex-encoded, so prefer what the user typed.
            email = emailOverride ?: rdn(subject, "E").orEmpty(),
            issuer = rdn(issuer, "CN") ?: issuer,
            expires = cert.notAfter.time,
            selfSigned = subject == issuer,
        )
        prefs(context).edit()
            .putString("name", info.name).putString("email", info.email).putString("issuer", info.issuer)
            .putLong("expires", info.expires).putBoolean("self", info.selfSigned)
            .apply()
        return info
    }

    private fun rdn(dn: String, key: String): String? =
        dn.split(',').map { it.trim() }.firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter('=')?.removePrefix("#")

    private fun firstKey(store: KeyStore, password: CharArray): Pair<PrivateKey, Array<Certificate>> {
        for (alias in store.aliases().toList()) {
            if (!store.isKeyEntry(alias)) continue
            val key = store.getKey(alias, password) as? PrivateKey ?: continue
            val chain = store.getCertificateChain(alias) ?: continue
            if (chain.isNotEmpty()) return key to chain
        }
        throw ToolException("That digital ID doesn't contain a signing key")
    }

    /**
     * Signs [input] with the saved ID (PAdES / adbe.pkcs7.detached) into [target]. The
     * signature is added as an incremental update, so the original bytes stay intact,
     * which is what lets viewers check that nothing changed after signing.
     */
    suspend fun sign(
        context: Context,
        input: Uri,
        password: CharArray,
        reason: String,
        location: String,
        target: File,
    ) = withContext(Dispatchers.IO) {
        val idFile = file(context)
        if (!idFile.exists()) throw ToolException("Set up a digital ID first")
        val store = KeyStore.getInstance("PKCS12")
        try {
            idFile.inputStream().use { store.load(it, password) }
        } catch (e: IOException) {
            throw ToolException("Wrong password for your digital ID")
        }
        val (key, chain) = firstKey(store, password)
        val cert = chain[0] as X509Certificate
        if (cert.notAfter.before(Date())) throw ToolException("Your digital ID has expired")

        // Incremental saving needs the original file on disk.
        val source = File.createTempFile("folio-sign-src", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(input)?.use { i -> source.outputStream().use { i.copyTo(it) } }
                ?: throw ToolException("Couldn't read the PDF")
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.isEncrypted) throw ToolException("Password-protected PDFs can't be signed yet (Phase 6)")
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    name = rdn(cert.subjectX500Principal.name, "CN") ?: "Folio user"
                    if (reason.isNotBlank()) this.reason = reason
                    if (location.isNotBlank()) this.location = location
                    signDate = Calendar.getInstance()
                }
                doc.addSignature(signature, SignatureInterface { content -> cms(content.readBytes(), key, chain) })
                FileOutputStream(target).use { doc.saveIncremental(it) }
            }
        } finally {
            source.delete()
        }
    }

    private fun cms(content: ByteArray, key: PrivateKey, chain: Array<Certificate>): ByteArray {
        val algorithm = if (key.algorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"
        val signer = JcaContentSignerBuilder(algorithm).setProvider(bc).build(key)
        val digests = JcaDigestCalculatorProviderBuilder().setProvider(bc).build()
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digests).build(signer, chain[0] as X509Certificate))
            addCertificates(JcaCertStore(chain.toList()))
        }
        return generator.generate(CMSProcessableByteArray(content), false).encoded
    }

    /** Signatures already in a PDF: who and when. */
    suspend fun existingSignatures(context: Context, input: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(input)?.use {
                PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            }?.use { doc ->
                doc.signatureDictionaries.map { s ->
                    val who = s.name ?: "Unknown signer"
                    val whenSigned = s.signDate?.time?.let { d -> java.text.DateFormat.getDateInstance().format(d) }
                    if (whenSigned != null) "$who · $whenSigned" else who
                }
            }
        }.getOrNull().orEmpty()
    }
}

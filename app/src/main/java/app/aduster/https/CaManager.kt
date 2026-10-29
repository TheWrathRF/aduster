package app.aduster.https

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.aduster.core.NativeCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The filtering CA. Generated on the device; its private key is stored encrypted with a
 * hardware-backed Android Keystore key and never leaves the app. Only the public certificate is
 * exported, for the user to install.
 */
object CaManager {
    private const val WRAP_ALIAS = "aduster-ca-wrap"
    private const val EXPORT_NAME = "Aduster_CA.crt"
    private const val PREFS = "ca"
    private const val SAVED_NAME = "saved_name"

    data class Ca(val certPem: String, val keyPem: String)

    /**
     * Only the public certificate is kept in memory. The private key is unwrapped (a Keystore
     * binder call) when the core needs it, which is only when the proxy starts or its config
     * changes, and dropped right after.
     */
    @Volatile
    private var cachedCert: Pair<Long, String>? = null

    /** AndroidCAStore alias under which our certificate was last found. */
    @Volatile
    private var alias: String? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "ca").apply { mkdirs() }
    private fun certFile(ctx: Context) = File(dir(ctx), "ca.pem")
    private fun keyFile(ctx: Context) = File(dir(ctx), "ca.key.enc")

    fun exists(ctx: Context) = certFile(ctx).exists() && keyFile(ctx).exists()

    /**
     * The CA, created on first use. An existing certificate is never replaced here, even when
     * its key cannot be read (Keystore error, a device-to-device transfer): the user installed
     * that certificate, and a silent new one would leave browsers trusting the wrong one. Null
     * then; [keyUnreadable] says why and [recreate] is the explicit way out.
     */
    @Synchronized
    fun ensure(ctx: Context): Ca? {
        load(ctx)?.let { return it }
        if (certFile(ctx).exists()) return null
        return generate(ctx)
    }

    /** A certificate exists but its private key cannot be decrypted. */
    fun keyUnreadable(ctx: Context): Boolean = certFile(ctx).exists() && load(ctx) == null

    /** The user asked for a new certificate: the old files go, a new pair is made. */
    @Synchronized
    fun recreate(ctx: Context): Ca? {
        certFile(ctx).delete()
        keyFile(ctx).delete()
        cachedCert = null
        alias = null
        return generate(ctx)
    }

    private fun generate(ctx: Context): Ca? {
        val o = runCatching { Json.parseToJsonElement(NativeCore.nativeGenerateCa()).jsonObject }.getOrNull() ?: return null
        val cert = o["cert"]?.jsonPrimitive?.content ?: return null
        val key = o["key"]?.jsonPrimitive?.content ?: return null
        // Key first: a certificate is only ever on disk with its key next to it.
        keyFile(ctx).writeBytes(encrypt(key.encodeToByteArray()))
        certFile(ctx).writeText(cert)
        cachedCert = null
        return Ca(cert, key)
    }

    /** The certificate and its private key, read together from disk; the caller must not keep the key. */
    fun load(ctx: Context): Ca? {
        if (!exists(ctx)) return null
        val cert = runCatching { certFile(ctx).readText() }.getOrNull() ?: return null
        return runCatching { Ca(cert, decrypt(keyFile(ctx).readBytes()).decodeToString()) }.getOrNull()
    }

    /** The public certificate (PEM), cached per file version (a new certificate is read again). */
    fun certPem(ctx: Context): String? {
        if (!exists(ctx)) return null
        val f = certFile(ctx)
        val stamp = f.lastModified() xor f.length()
        cachedCert?.let { (s, pem) -> if (s == stamp) return pem }
        return runCatching { f.readText() }.getOrNull()?.also { cachedCert = stamp to it }
    }

    /**
     * Whether [ca]'s key belongs to its certificate: a test signature made with the key must
     * verify with the certificate's public key. A mismatch must never reach the proxy (every
     * leaf would fail in the browser).
     */
    fun keyMatches(ca: Ca): Boolean = runCatching {
        val b64 = ca.keyPem.lines().filterNot { it.startsWith("-----") }.joinToString("")
        val der = Base64.getDecoder().decode(b64)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(pemToDer(ca.certPem).inputStream()) as X509Certificate
        val alg = cert.publicKey.algorithm
        val key = java.security.KeyFactory.getInstance(alg).generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        val sigAlg = if (alg == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val data = "aduster-ca-check".encodeToByteArray()
        val sig = java.security.Signature.getInstance(sigAlg).run { initSign(key); update(data); sign() }
        java.security.Signature.getInstance(sigAlg).run { initVerify(cert.publicKey); update(data); verify(sig) }
    }.getOrDefault(false)

    private fun pemToDer(pem: String): ByteArray = Base64.getDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    private fun certDer(ctx: Context): ByteArray? {
        val pem = runCatching { certFile(ctx).readText() }.getOrNull() ?: return null
        val b64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("")
        return runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
    }

    /** The store's last answer (see [trustState]); null until one was read. */
    @Volatile
    private var lastTrusted: Boolean? = null

    /**
     * [read]'s answer, remembered. An error reading the store (busy or failing for a moment)
     * keeps the last known answer: as "not trusted" it would stop HTTPS filtering until the next
     * check, up to 30 minutes. Not trusted only when nothing was known yet.
     */
    internal fun trustState(read: () -> Boolean): Boolean = try {
        read().also { lastTrusted = it }
    } catch (_: Exception) {
        lastTrusted ?: false
    }

    /** True once the user has installed our certificate as a trusted user CA (full scan). */
    fun isInstalled(ctx: Context): Boolean {
        val ours = certDer(ctx) ?: return false
        return trustState {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val found = ks.aliases().asSequence()
                .filter { it.startsWith("user:") }
                .firstOrNull { (ks.getCertificate(it) as? X509Certificate)?.encoded?.contentEquals(ours) == true }
            alias = found
            found != null
        }
    }

    /** Cheap re-check: looks only at the alias found last time (falls back to a full scan). */
    fun stillInstalled(ctx: Context): Boolean {
        val a = alias ?: return isInstalled(ctx)
        val ours = certDer(ctx) ?: return false
        return trustState {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            (ks.getCertificate(a) as? X509Certificate)?.encoded?.contentEquals(ours) == true
        }
    }

    /**
     * Saves the public certificate to Downloads for installation from system settings. Null when
     * it could not be made or written (Keystore or storage errors are said, never a crash); a
     * half-written file is removed.
     */
    fun exportToDownloads(ctx: Context): Uri? {
        val der = runCatching { certDer(ctx) ?: ensure(ctx)?.let { certDer(ctx) } }.getOrNull() ?: return null
        val resolver = ctx.contentResolver
        var uri: Uri? = null
        return try {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(EXPORT_NAME),
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, EXPORT_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(der) } ?: error("no output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    /**
     * The name the saved file got in Downloads: a file left by an earlier install of
     * the app is not this install's to delete, so the new one can be "Aduster_CA (1).crt", and
     * the user must pick that one. Kept, so the install step names it on every visit.
     */
    fun savedName(ctx: Context, uri: Uri): String {
        val name = try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: EXPORT_NAME
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SAVED_NAME, name).apply()
        return name
    }

    /** The name the certificate was last saved under, or null if it was never saved. */
    fun lastSavedName(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SAVED_NAME, null)

    fun fingerprint(ctx: Context): String? {
        val der = certDer(ctx) ?: return null
        return fingerprintOf(der)
    }

    /** The certificate's SHA-256, whole and in the form Android's certificate details show it. */
    fun fingerprintOf(der: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

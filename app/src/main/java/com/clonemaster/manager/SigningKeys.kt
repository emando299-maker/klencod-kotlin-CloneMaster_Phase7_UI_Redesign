package com.clonemaster.manager

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Persistent signing key. The same key signs every clone, so a newer build of a clone with the
 * same package name installs as an UPDATE over the older one. The key lives in app-private
 * storage: uninstalling Clone Master (or clearing its data) loses it.
 */
object SigningKeys {
    private fun dir(context: Context) = File(context.filesDir, "signing").apply { mkdirs() }
    private fun keyFile(context: Context) = File(dir(context), "key.pk8")
    private fun certFile(context: Context) = File(dir(context), "cert.der")

    private class Material(val key: PrivateKey, val cert: X509Certificate)

    @Synchronized
    private fun load(context: Context): Material {
        val kf = keyFile(context)
        val cf = certFile(context)
        if (kf.isFile && cf.isFile) {
            try {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cf.readBytes())) as X509Certificate
                return Material(key, cert)
            } catch (_: Exception) {
                // corrupted: fall through and create a new key
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=CloneMaster Local Signing")
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - 60_000L), Date(now + 25L * 365 * 24 * 60 * 60 * 1000), subject, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        kf.writeBytes(pair.private.encoded)
        cf.writeBytes(cert.encoded)
        return Material(pair.private, cert)
    }

    fun signerConfig(context: Context): ApkSigner.SignerConfig {
        val m = load(context)
        return ApkSigner.SignerConfig.Builder("CloneMaster", m.key, listOf(m.cert)).build()
    }

    fun fingerprint(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(load(context).cert.encoded)
        return digest.take(16).joinToString(":") { "%02X".format(it) }
    }

    @Synchronized
    fun reset(context: Context) {
        keyFile(context).delete()
        certFile(context).delete()
        load(context)
    }
}

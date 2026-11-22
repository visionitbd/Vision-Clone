package com.serialcloner.app.cloner

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

object KeystoreManager {

    private const val KEYSTORE_FILE = "serial_cloner_keystore.p12"
    private const val KEY_ALIAS = "serial_cloner_key"
    private val STORE_PASSWORD = "serial_cloner_local_pass".toCharArray()

    data class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    fun getOrCreateSigningMaterial(context: Context): SigningMaterial {
        val file = File(context.filesDir, KEYSTORE_FILE)
        val ks = KeyStore.getInstance("PKCS12")

        if (file.exists()) {
            FileInputStream(file).use { ks.load(it, STORE_PASSWORD) }
            val key = ks.getKey(KEY_ALIAS, STORE_PASSWORD) as PrivateKey
            val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
            return SigningMaterial(key, cert)
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 25L * 365L * 86_400_000L)
        val subject = X500Name("CN=SerialAppCloner, O=LocalDevice, C=US")
        val serial = BigInteger(64, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, STORE_PASSWORD, arrayOf(cert))
        FileOutputStream(file).use { ks.store(it, STORE_PASSWORD) }

        return SigningMaterial(keyPair.private, cert)
    }
}

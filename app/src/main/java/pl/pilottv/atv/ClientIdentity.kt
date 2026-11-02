package pl.pilottv.atv

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Certyfikat klienta dla Android TV. Dekoder zapamiętuje go przy parowaniu
 * i potem rozpoznaje telefon po nim. Klucz jest tworzony raz i zapisany w plikach aplikacji.
 */
class ClientIdentity private constructor(
    private val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {

    fun sslContext(trustManager: X509TrustManager): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, PASSWORD, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, PASSWORD)
        }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustManager), null)
        }
    }

    companion object {
        private val PASSWORD = CharArray(0)
        private const val KEY_FILE = "atv_client_key.der"
        private const val CERT_FILE = "atv_client_cert.der"

        /** Wczytuje albo tworzy tożsamość. Generowanie klucza RSA trwa chwilę: wywoływać poza wątkiem UI. */
        fun load(context: Context): ClientIdentity {
            val keyFile = File(context.filesDir, KEY_FILE)
            val certFile = File(context.filesDir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(certFile.inputStream()) as X509Certificate
                    return ClientIdentity(key, cert)
                }
            }
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = SelfSignedCertificate.create(keyPair, "Pilot TV")
            keyFile.writeBytes(keyPair.private.encoded)
            certFile.writeBytes(cert.encoded)
            return ClientIdentity(keyPair.private, cert)
        }
    }
}

/** Minimalny certyfikat X.509 v3 bez rozszerzeń, zakodowany ręcznie w DER (Android nie ma publicznego API). */
private object SelfSignedCertificate {
    private val OID_SHA256_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun create(keyPair: KeyPair, commonName: String): X509Certificate {
        val algorithm = seq(tlv(0x06, OID_SHA256_RSA), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(tlv(0x06, OID_COMMON_NAME), tlv(0x0C, commonName.toByteArray()))))
        val now = System.currentTimeMillis()
        // UTCTime obsługuje daty do 2049 r., 20 lat wystarczy.
        val validity = seq(utcTime(Date(now - DAY_MS)), utcTime(Date(now + 20 * 365 * DAY_MS)))
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, tlv(0x02, BigInteger.valueOf(2).toByteArray())),
            tlv(0x02, serial.toByteArray()),
            algorithm,
            name,
            validity,
            name,
            keyPair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    private fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        if (len < 0x80) {
            out.write(len)
        } else {
            val lenBytes = BigInteger.valueOf(len.toLong()).toByteArray().dropWhile { it == 0.toByte() }
            out.write(0x80 or lenBytes.size)
            lenBytes.forEach { out.write(it.toInt()) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

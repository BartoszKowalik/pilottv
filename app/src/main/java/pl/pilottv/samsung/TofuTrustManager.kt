package pl.pilottv.samsung

import android.util.Base64
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class PinMismatchException : CertificateException("Certyfikat TV się zmienił")

/**
 * Trust on first use. TV ma certyfikat self-signed, więc nie da się go zweryfikować przez CA.
 * Przy pierwszym połączeniu zapamiętujemy skrót SHA-256 certyfikatu, potem akceptujemy tylko jego.
 */
class TofuTrustManager(private val expectedPin: String?) : X509TrustManager {

    @Volatile
    var seenPin: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Brak certyfikatu")
        val pin = sha256(leaf.encoded)
        if (expectedPin != null && expectedPin != pin) throw PinMismatchException()
        seenPin = pin
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Nieobsługiwane")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun sha256(bytes: ByteArray): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
}

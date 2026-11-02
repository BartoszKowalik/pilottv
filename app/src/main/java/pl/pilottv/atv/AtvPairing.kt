package pl.pilottv.atv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.pilottv.net.TofuTrustManager
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class WrongCodeException : Exception("Błędny kod")

class PairingRejectedException(status: Long?) : IOException("Dekoder odrzucił parowanie (status $status)")

/**
 * Parowanie z Android TV (protokół „Polo”, port 6467).
 * [start] łączy i prosi o kod. Dekoder pokazuje 6 znaków (hex). [finish] wysyła skrót z kodem.
 * Błędny kod wykrywamy lokalnie (pierwszy bajt skrótu), więc można spróbować ponownie bez nowego parowania.
 */
class AtvPairing(
    private val identity: () -> ClientIdentity,
    private val ip: String,
    private val clientName: String,
) {
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Zwraca nazwę dekodera, jeśli ją podał. */
    suspend fun start(): String? = withContext(Dispatchers.IO) {
        val s = identity().sslContext(TofuTrustManager(null)).socketFactory.createSocket() as SSLSocket
        socket = s
        s.soTimeout = TIMEOUT_MS
        s.connect(InetSocketAddress(ip, PAIRING_PORT), TIMEOUT_MS)
        s.startHandshake()
        input = BufferedInputStream(s.inputStream)
        output = s.outputStream

        send(PAIRING_REQUEST) {
            string(1, SERVICE_NAME)
            string(2, clientName)
        }
        val serverName = expect(PAIRING_REQUEST_ACK).string(1)

        send(OPTIONS) {
            message(1) { hexEncoding() }
            int(3, ROLE_INPUT)
        }
        expect(OPTIONS)

        send(CONFIGURATION) {
            message(1) { hexEncoding() }
            int(2, ROLE_INPUT)
        }
        expect(CONFIGURATION_ACK)
        serverName?.takeIf { it.isNotBlank() }
    }

    suspend fun finish(code: String) = withContext(Dispatchers.IO) {
        val clean = code.trim().uppercase()
        if (clean.length != 6 || clean.any { it !in HEX_CHARS }) throw WrongCodeException()
        val s = socket ?: throw IOException("Parowanie nie jest rozpoczęte")

        val clientKey = identity().certificate.publicKey as RSAPublicKey
        val serverKey = (s.session.peerCertificates.first() as X509Certificate).publicKey as RSAPublicKey
        val hash = MessageDigest.getInstance("SHA-256").run {
            update(clientKey.modulus.unsignedBytes())
            update(clientKey.publicExponent.unsignedBytes())
            update(serverKey.modulus.unsignedBytes())
            update(serverKey.publicExponent.unsignedBytes())
            update(hexToBytes(clean.substring(2)))
            digest()
        }
        if (hash[0] != hexToBytes(clean.substring(0, 2))[0]) throw WrongCodeException()

        send(SECRET) { bytes(1, hash) }
        expect(SECRET_ACK)
        Unit
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(field: Int, body: ProtoWriter.() -> Unit) {
        val out = output ?: throw IOException("Brak połączenia")
        ProtoIo.write(out, proto {
            int(1, PROTOCOL_VERSION)
            int(2, STATUS_OK)
            message(field, body)
        })
    }

    private fun expect(field: Int): ProtoMessage {
        val inp = input ?: throw IOException("Brak połączenia")
        val frame = ProtoIo.read(inp) ?: throw IOException("Dekoder zamknął połączenie")
        val msg = ProtoMessage.parse(frame)
        val status = msg.int(2)
        if (status != STATUS_OK.toLong()) throw PairingRejectedException(status)
        return msg.message(field) ?: ProtoMessage.parse(ByteArray(0))
    }

    private fun ProtoWriter.hexEncoding() {
        int(1, ENCODING_HEXADECIMAL)
        int(2, CODE_LENGTH)
    }

    private companion object {
        const val PAIRING_PORT = 6467
        const val TIMEOUT_MS = 10_000
        const val SERVICE_NAME = "atvremote"
        const val PROTOCOL_VERSION = 2
        const val STATUS_OK = 200

        // Numery pól w OuterMessage.
        const val PAIRING_REQUEST = 10
        const val PAIRING_REQUEST_ACK = 11
        const val OPTIONS = 20
        const val CONFIGURATION = 30
        const val CONFIGURATION_ACK = 31
        const val SECRET = 40
        const val SECRET_ACK = 41

        const val ENCODING_HEXADECIMAL = 3
        const val CODE_LENGTH = 6
        const val ROLE_INPUT = 1
        const val HEX_CHARS = "0123456789ABCDEF"

        fun BigInteger.unsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}

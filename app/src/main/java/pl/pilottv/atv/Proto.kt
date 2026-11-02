package pl.pilottv.atv

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * Minimalny protobuf (tylko varint i length-delimited), wystarczający dla protokołu Android TV Remote v2.
 * Ręczna implementacja zamiast generatora kodu: kilka wiadomości nie uzasadnia wtyczki Gradle.
 */

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter = apply {
        tag(field, 0)
        varint(out, value)
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, 2)
        varint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = varint(out, ((field shl 3) or wireType).toLong())
}

fun proto(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()

class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun int(field: Int): Long? = fields[field]?.firstOrNull() as? Long

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = mutableMapOf<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Ucięty varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> fields.getOrPut(field) { mutableListOf() }.add(readVarint())
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("Zła długość pola")
                        fields.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IOException("Nieobsługiwany typ pola")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

/** Ramki na gnieździe: długość wiadomości (varint) + wiadomość. */
object ProtoIo {
    private const val MAX_FRAME = 1 shl 20

    fun write(out: OutputStream, message: ByteArray) {
        val frame = ByteArrayOutputStream()
        varint(frame, message.size.toLong())
        frame.write(message)
        out.write(frame.toByteArray())
        out.flush()
    }

    /** Zwraca null, gdy druga strona zamknęła połączenie. */
    fun read(input: InputStream): ByteArray? {
        var length = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (shift == 0) return null
                throw IOException("Połączenie przerwane")
            }
            length = length or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (length > MAX_FRAME) throw IOException("Za duża ramka")
        val buffer = ByteArray(length)
        DataInputStream(input).readFully(buffer)
        return buffer
    }
}

private fun varint(out: OutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

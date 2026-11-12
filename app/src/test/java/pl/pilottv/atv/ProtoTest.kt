package pl.pilottv.atv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ProtoTest {

    @Test
    fun writeAndParseFields() {
        val data = proto {
            int(1, 150)
            string(2, "zażółć 😀")
            bytes(3, byteArrayOf(1, 2, 3))
            message(4) {
                int(1, 7)
                string(2, "wewnątrz")
            }
        }
        val msg = ProtoMessage.parse(data)
        assertEquals(150L, msg.int(1))
        assertEquals("zażółć 😀", msg.string(2))
        assertArrayEquals(byteArrayOf(1, 2, 3), msg.bytes(3))
        val nested = msg.message(4)!!
        assertEquals(7L, nested.int(1))
        assertEquals("wewnątrz", nested.string(2))
        assertFalse(msg.has(5))
        assertNull(msg.int(5))
    }

    @Test
    fun multiByteVarint() {
        // 300 = 0xAC 0x02 (przykład z dokumentacji protobuf).
        assertArrayEquals(byteArrayOf(0x08, 0xAC.toByte(), 0x02), proto { int(1, 300) })
        val big = 1L shl 40
        assertEquals(big, ProtoMessage.parse(proto { int(1, big) }).int(1))
    }

    @Test
    fun skipsFixed64AndFixed32() {
        val data = byteArrayOf(
            0x09, 1, 2, 3, 4, 5, 6, 7, 8, // pole 1, typ 1 (8 bajtów)
            0x15, 1, 2, 3, 4, // pole 2, typ 5 (4 bajty)
            0x18, 0x05, // pole 3, varint 5
        )
        val msg = ProtoMessage.parse(data)
        assertFalse(msg.has(1))
        assertFalse(msg.has(2))
        assertEquals(5L, msg.int(3))
    }

    @Test
    fun truncatedDataThrows() {
        assertThrows(IOException::class.java) { ProtoMessage.parse(byteArrayOf(0x08, 0x80.toByte())) }
        assertThrows(IOException::class.java) { ProtoMessage.parse(byteArrayOf(0x12, 0x05, 0x01)) }
    }

    @Test
    fun frameWriteAndRead() {
        val small = proto { int(1, 1) }
        val large = proto { bytes(1, ByteArray(200) { it.toByte() }) }
        val out = ByteArrayOutputStream()
        ProtoIo.write(out, small)
        ProtoIo.write(out, large)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(small, ProtoIo.read(input))
        assertArrayEquals(large, ProtoIo.read(input))
        assertNull(ProtoIo.read(input))
    }

    @Test
    fun readReturnsNullAtEndOfStream() {
        assertNull(ProtoIo.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun readThrowsOnCutFrame() {
        // Długość 5, ale tylko 2 bajty danych.
        assertThrows(IOException::class.java) { ProtoIo.read(ByteArrayInputStream(byteArrayOf(0x05, 1, 2))) }
        // Varint długości urwany w środku.
        assertThrows(IOException::class.java) { ProtoIo.read(ByteArrayInputStream(byteArrayOf(0x80.toByte()))) }
    }
}

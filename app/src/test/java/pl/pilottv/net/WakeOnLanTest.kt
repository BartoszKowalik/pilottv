package pl.pilottv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {

    @Test
    fun colonsAndDashes() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalizeMac("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalizeMac("AA-BB-CC-DD-EE-FF"))
        assertEquals("01:23:45:67:89:AB", WakeOnLan.normalizeMac("  01:23:45:67:89:ab "))
    }

    @Test
    fun singleDigitParts() {
        assertEquals("0A:0B:0C:0D:0E:0F", WakeOnLan.normalizeMac("a:b:c:d:e:f"))
    }

    @Test
    fun wrongPartCount() {
        assertNull(WakeOnLan.normalizeMac(""))
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE"))
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE:FF:00"))
    }

    @Test
    fun notHex() {
        assertNull(WakeOnLan.normalizeMac("GG:BB:CC:DD:EE:FF"))
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE:ZZ"))
    }

    @Test
    fun partLongerThanTwoDigits() {
        assertNull(WakeOnLan.normalizeMac("1FF:BB:CC:DD:EE:FF"))
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE:0FF"))
    }

    @Test
    fun signIsRejected() {
        assertNull(WakeOnLan.normalizeMac("+A:BB:CC:DD:EE:FF"))
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE:+F"))
    }

    @Test
    fun withoutSeparators() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalizeMac("AABBCCDDEEFF"))
        assertEquals("01:23:45:67:89:AB", WakeOnLan.normalizeMac(" 0123456789ab "))
        assertNull(WakeOnLan.normalizeMac("AABBCCDDEEF"))
        assertNull(WakeOnLan.normalizeMac("AABBCCDDEEFF0"))
        assertNull(WakeOnLan.normalizeMac("AABBCCDDEEGG"))
    }
}

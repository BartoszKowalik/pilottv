package pl.pilottv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4Test {

    @Test
    fun validAddresses() {
        assertEquals("192.168.1.50", parseIpv4("192.168.1.50"))
        assertEquals("0.0.0.0", parseIpv4("0.0.0.0"))
        assertEquals("255.255.255.255", parseIpv4("255.255.255.255"))
        assertEquals("10.0.0.1", parseIpv4("  10.0.0.1 "))
    }

    @Test
    fun examplesFromBadInput() {
        assertNull(parseIpv4("192.168.1.50:8002"))
        assertNull(parseIpv4("http://192.168.1.50"))
        assertNull(parseIpv4("192.168. 1.50"))
    }

    @Test
    fun valuesAbove255() {
        assertNull(parseIpv4("256.1.1.1"))
        assertNull(parseIpv4("1.1.1.300"))
        assertNull(parseIpv4("999.999.999.999"))
    }

    @Test
    fun emptyAndWrongParts() {
        assertNull(parseIpv4(""))
        assertNull(parseIpv4("   "))
        assertNull(parseIpv4("1..1.1"))
        assertNull(parseIpv4("1.1.1."))
        assertNull(parseIpv4(".1.1.1"))
        assertNull(parseIpv4("1.1.1"))
        assertNull(parseIpv4("1.1.1.1.1"))
        assertNull(parseIpv4("1000.1.1.1"))
        assertNull(parseIpv4("-1.1.1.1"))
        assertNull(parseIpv4("+1.1.1.1"))
        assertNull(parseIpv4("a.b.c.d"))
        assertNull(parseIpv4("tv.local"))
        // Cyfry spoza ASCII (tu arabskie) nie są dozwolone.
        assertNull(parseIpv4("١.1.1.1"))
    }
}

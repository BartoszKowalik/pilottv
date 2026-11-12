package pl.pilottv.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppShortcutTest {

    @Test
    fun packageNameIsReturnedAsIs() {
        assertEquals("com.amazon.spiderpork", AppShortcut("Luna", "com.amazon.spiderpork").packageName)
    }

    @Test
    fun knownLinkGivesPackage() {
        assertEquals("com.netflix.ninja", AppShortcut("Netflix", "https://www.netflix.com/title").packageName)
        assertEquals("com.google.android.youtube.tv", AppShortcut("YouTube", "https://www.youtube.com").packageName)
    }

    @Test
    fun unknownLinkGivesNull() {
        assertNull(AppShortcut("Inna", "https://example.com/tv").packageName)
    }
}

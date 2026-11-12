package pl.pilottv.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditTest {

    @Test
    fun append() {
        assertEquals(TextEdit(0, 0, "d"), textEdit("abc", "abcd"))
    }

    @Test
    fun deleteFromEnd() {
        assertEquals(TextEdit(2, 2, ""), textEdit("abcd", "ab"))
    }

    @Test
    fun replaceMiddle() {
        // Wspólny jest tylko przedrostek, więc koniec idzie ponownie.
        assertEquals(TextEdit(3, 3, "Ycd"), textEdit("abXcd", "abYcd"))
    }

    @Test
    fun emptyText() {
        assertEquals(TextEdit(0, 0, ""), textEdit("", ""))
        assertEquals(TextEdit(0, 0, "abc"), textEdit("", "abc"))
        assertEquals(TextEdit(3, 3, ""), textEdit("abc", ""))
        assertEquals(TextEdit(0, 0, ""), textEdit("abc", "abc"))
    }

    @Test
    fun emojiSurrogatePair() {
        // 😀 to jeden znak Unicode, ale dwie jednostki UTF-16.
        assertEquals(TextEdit(2, 1, ""), textEdit("a😀", "a"))
        assertEquals(TextEdit(0, 0, "😀"), textEdit("a", "a😀"))
        // 😀 i 😃 mają ten sam pierwszy surogat. Pary nie można rozdzielić.
        assertEquals(TextEdit(2, 1, "😃"), textEdit("a😀", "a😃"))
    }
}

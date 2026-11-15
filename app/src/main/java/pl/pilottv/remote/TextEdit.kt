package pl.pilottv.remote

/** Zmiana pola tekstowego: ile znaków usunąć z końca i co dopisać. */
data class TextEdit(val deleteChars: Int, val deleteCodePoints: Int, val insert: String)

/**
 * Różnica między tekstem w polu ([previous]) a nowym tekstem ([next]).
 * [TextEdit.deleteChars] liczy jednostki UTF-16 (LG), [TextEdit.deleteCodePoints] liczy znaki Unicode (Backspace na dekoderze).
 */
fun textEdit(previous: String, next: String): TextEdit {
    val common = previous.commonPrefixWith(next).length
    return TextEdit(
        deleteChars = previous.length - common,
        deleteCodePoints = previous.codePointCount(common, previous.length),
        insert = next.substring(common),
    )
}

/** Czy tekst ma znaki, których pole jednowierszowe nie przyjmie (zwykle po wklejeniu). */
fun hasLineBreaks(text: String): Boolean = text.any { it == '\n' || it == '\r' || it == '\t' }

/**
 * Tekst ze schowka do pola jednowierszowego: nowe linie i tabulatory zamienia na spację,
 * usuwa spacje z początku i końca (np. adres skopiowany z wiadomości).
 */
fun cleanPastedText(text: String): String = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()

/** Najwięcej zapamiętanych tekstów w arkuszu klawiatury. */
const val RECENT_TEXTS_MAX = 6

/** Lista ostatnich tekstów z [text] na początku, bez powtórzeń i pustych tekstów. */
fun withRecentText(list: List<String>, text: String, max: Int = RECENT_TEXTS_MAX): List<String> {
    val value = text.trim()
    if (value.isEmpty()) return list
    return (listOf(value) + list.filter { it != value }).take(max)
}

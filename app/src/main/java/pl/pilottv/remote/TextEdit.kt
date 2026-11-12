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

package pl.pilottv.remote

/** Zdarzenia pola tekstowego (klawiatury ekranowej) na TV lub dekoderze. */
sealed interface ImeEvent {
    /** Na urządzeniu otworzyło się pole tekstowe. [text] = obecna zawartość, jeśli urządzenie ją podało. */
    data class Opened(val text: String?) : ImeEvent

    /** Zawartość pola na urządzeniu się zmieniła. */
    data class Updated(val text: String) : ImeEvent

    data object Closed : ImeEvent
}

/** Skrót do aplikacji na dekoderze: nazwa pakietu Android albo link. */
data class AppShortcut(val name: String, val app: String) {
    companion object {
        // Linki, gdzie to możliwe: nazwa pakietu wymaga aplikacji pomocniczej na dekoderze.
        // Bez niej dekoder nie ma obsługi linku i zrywa połączenie.
        val DEFAULTS = listOf(
            AppShortcut("YouTube", "https://www.youtube.com"),
            AppShortcut("Netflix", "https://www.netflix.com/title"),
            AppShortcut("Prime Video", "https://app.primevideo.com"),
            AppShortcut("HBO Max", "https://play.hbomax.com"),
            // Te dwie aplikacje nie obsługują linków: uruchamia je aplikacja pomocnicza po nazwie pakietu.
            AppShortcut("SkyShowtime", "com.skyshowtime.skyshowtime.google"),
            AppShortcut("Amazon Luna", "com.amazon.spiderpork"),
            AppShortcut("Google Play", "https://play.google.com/store/apps"),
        )
    }
}

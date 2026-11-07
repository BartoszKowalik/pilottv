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

    /** Pakiet aplikacji, którą otwiera skrót. Dla linku tylko, gdy serwis jest znany. */
    val packageName: String?
        get() {
            if ("://" !in app) return app
            val host = app.substringAfter("://").substringBefore('/').removePrefix("www.")
            return LINK_PACKAGES[host]
        }

    companion object {
        // Pakiety na Android TV dla domyślnych linków (do zaznaczenia otwartej aplikacji).
        private val LINK_PACKAGES = mapOf(
            "youtube.com" to "com.google.android.youtube.tv",
            "netflix.com" to "com.netflix.ninja",
            "app.primevideo.com" to "com.amazon.amazonvideo.livingroom",
            "play.hbomax.com" to "com.wbd.stream",
            "play.google.com" to "com.android.vending",
        )

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

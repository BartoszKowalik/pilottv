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
        // Linki zamiast nazw pakietów: nazwa pakietu idzie jako market://launch?id=…,
        // a dekodery bez obsługi tego linku zrywają połączenie i nie uruchamiają aplikacji.
        val DEFAULTS = listOf(
            AppShortcut("Netflix", "https://www.netflix.com/title"),
            AppShortcut("YouTube", "https://www.youtube.com"),
            AppShortcut("Disney+", "https://www.disneyplus.com"),
            AppShortcut("Prime Video", "https://app.primevideo.com"),
            AppShortcut("Max", "https://play.max.com"),
            AppShortcut("Spotify", "spotify://"),
        )
    }
}

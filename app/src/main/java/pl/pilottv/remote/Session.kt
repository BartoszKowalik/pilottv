package pl.pilottv.remote

/** Stan połączenia z urządzeniem. Wspólny dla Samsunga, LG i dekodera Android TV. */
sealed interface TvState {
    data object Idle : TvState
    data object Connecting : TvState
    data object AwaitingApproval : TvState
    data object Connected : TvState
    data class Failed(val reason: SessionEnd) : TvState
}

/** Przyczyna końca sesji. */
enum class SessionEnd { CLOSED, UNREACHABLE, TIMEOUT, DENIED, CERT_CHANGED, NOT_PAIRED, BAD_ADDRESS }

data class SessionResult(val end: SessionEnd, val wasConnected: Boolean)

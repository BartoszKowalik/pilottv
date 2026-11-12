package pl.pilottv.net

/** Zwraca adres bez spacji na brzegach albo null, gdy to nie jest adres IPv4 (4 liczby 0–255). */
fun parseIpv4(input: String): String? {
    val ip = input.trim()
    val parts = ip.split('.')
    if (parts.size != 4) return null
    for (part in parts) {
        if (part.length !in 1..3 || part.any { it !in '0'..'9' }) return null
        if (part.toInt() > 255) return null
    }
    return ip
}

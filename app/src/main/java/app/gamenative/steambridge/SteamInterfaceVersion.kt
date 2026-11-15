package app.gamenative.steambridge

/**
 * Разбор имени интерфейса Steam вида "SteamUtils011" на семейство и версию.
 *
 * Нужен селектору моста: сравнивать мосты приходится по максимальной версии
 * каждого семейства, а не по строке целиком.
 */
data class SteamInterfaceVersion(
    val family: String,
    val version: Int,
) {
    val token: String get() = family + version.toString().padStart(3, '0')

    companion object {
        // Версия всегда три цифры в конце: "SteamUtils011", "STEAMAPPS_INTERFACE_VERSION008".
        private val TRAILING_VERSION = Regex("^(.*?)(\\d{3})$")

        fun parse(token: String): SteamInterfaceVersion? {
            val match = TRAILING_VERSION.matchEntire(token) ?: return null
            val version = match.groupValues[2].toIntOrNull() ?: return null
            return SteamInterfaceVersion(match.groupValues[1], version)
        }

        /** Максимальная версия на каждое семейство — "свежесть" набора интерфейсов моста. */
        fun highestPerFamily(tokens: Set<String>): Map<String, Int> {
            val result = HashMap<String, Int>()
            for (token in tokens) {
                val parsed = parse(token) ?: continue
                val known = result[parsed.family]
                if (known == null || parsed.version > known) result[parsed.family] = parsed.version
            }
            return result
        }
    }
}

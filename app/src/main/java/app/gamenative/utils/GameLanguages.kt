package app.gamenative.utils

import java.util.Locale

/**
 * One list of game languages for everything that talks to a store: the container picker, the
 * install dialogs and the "Default game language" setting. Store integrations key their own code
 * tables on these names, so a language added here also needs its mapping there.
 */
object GameLanguages {
    /** Value used whenever the requested language cannot be mapped to a known one. */
    const val FALLBACK = "english"

    /** Steam container codes in picker order (matches the container language dropdown). */
    val CODES: List<String> = listOf(
        "arabic", "bulgarian", "schinese", "tchinese", "czech", "danish", "dutch", "english",
        "finnish", "french", "german", "greek", "hungarian", "italian", "japanese", "koreana",
        "norwegian", "polish", "portuguese", "brazilian", "romanian", "russian", "spanish",
        "latam", "swedish", "thai", "turkish", "ukrainian", "vietnamese",
    )

    // Codes are not self-explanatory to users (schinese/tchinese/latam), so the label is explicit.
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "arabic" to "Arabic",
        "bulgarian" to "Bulgarian",
        "schinese" to "Simplified Chinese",
        "tchinese" to "Traditional Chinese",
        "czech" to "Czech",
        "danish" to "Danish",
        "dutch" to "Dutch",
        "english" to "English",
        "finnish" to "Finnish",
        "french" to "French",
        "german" to "German",
        "greek" to "Greek",
        "hungarian" to "Hungarian",
        "italian" to "Italian",
        "japanese" to "Japanese",
        "koreana" to "Korean",
        "norwegian" to "Norwegian",
        "polish" to "Polish",
        "portuguese" to "Portuguese",
        "brazilian" to "Portuguese (Brazil)",
        "romanian" to "Romanian",
        "russian" to "Russian",
        "spanish" to "Spanish",
        "latam" to "Spanish (Latin America)",
        "swedish" to "Swedish",
        "thai" to "Thai",
        "turkish" to "Turkish",
        "ukrainian" to "Ukrainian",
        "vietnamese" to "Vietnamese",
    )

    /** Human-readable name for a code; unknown codes stay visible instead of disappearing. */
    fun displayName(code: String): String =
        DISPLAY_NAMES[code.lowercase(Locale.ROOT)] ?: code.replaceFirstChar { it.titlecase(Locale.ROOT) }

    /**
     * Language used when the user has not chosen one yet: the device locale, else English so an
     * unmapped language never reaches a store as a code it does not recognise.
     */
    fun defaultGameLanguage(locale: Locale = Locale.getDefault()): String {
        // Reuse the app's existing locale mapping instead of keeping a second table in sync;
        // it already handles the Steam-specific splits (koreana, latam, tchinese, brazilian).
        val mapped = SteamUtils.steamLanguageForAppLocale(locale)
        return if (mapped in CODES) mapped else FALLBACK
    }

    /**
     * Keeps picker order and drops languages the game does not ship. An empty result means the
     * supported list is unknown (for example the game keeps all languages in one depot), and the
     * caller should offer the full list instead.
     */
    fun filterSupported(availableCodes: Collection<String>): List<String> {
        if (availableCodes.isEmpty()) return emptyList()
        val normalized = availableCodes.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        return CODES.filter { it in normalized }
    }

    /**
     * Same as [filterSupported] for stores that speak their own codes: [availableStoreCodes] are
     * matched against each language's codes in [codeMap] (GOG or Epic table).
     */
    fun filterSupportedByStoreCodes(
        availableStoreCodes: Collection<String>,
        codeMap: Map<String, List<String>>,
    ): List<String> {
        if (availableStoreCodes.isEmpty()) return emptyList()
        // Store metadata mixes "pt-BR" and "pt_BR"; normalising both sides avoids near-misses.
        val normalized = availableStoreCodes.mapTo(mutableSetOf()) {
            it.lowercase(Locale.ROOT).replace('_', '-')
        }
        return CODES.filter { code ->
            codeMap[code].orEmpty().any { it.lowercase(Locale.ROOT).replace('_', '-') in normalized }
        }
    }

    /**
     * Нужно ли спросить про повторную загрузку после сохранения настроек контейнера.
     * Смена языка у неустановленной игры ничего не качает: файлов ещё нет.
     * Сравнение точное, как и раньше в saveContainerConfig: регистр кодов всегда нижний.
     */
    fun shouldPromptForDownload(
        installed: Boolean,
        previousLanguage: String,
        newLanguage: String,
    ): Boolean = installed && previousLanguage != newLanguage

    /**
     * Selection the picker should open with: the wanted language when the game ships it, else
     * English, else the first supported one. Without this the picker could show a language whose
     * files the store will silently replace with English.
     */
    fun preselected(desired: String, available: List<String>): String {
        if (available.isEmpty()) return desired.ifBlank { FALLBACK }
        val normalized = desired.lowercase(Locale.ROOT)
        return when {
            normalized in available -> normalized
            FALLBACK in available -> FALLBACK
            else -> available.first()
        }
    }
}

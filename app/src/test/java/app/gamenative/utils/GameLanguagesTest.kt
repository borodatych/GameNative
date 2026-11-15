package app.gamenative.utils

import app.gamenative.data.DepotInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.service.SteamService
import java.util.EnumSet
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class GameLanguagesTest {

    // -- Default game language --

    @Test
    fun `maps device locale to the matching steam code`() {
        assertEquals("english", GameLanguages.defaultGameLanguage(Locale("en", "US")))
        assertEquals("german", GameLanguages.defaultGameLanguage(Locale("de", "DE")))
        assertEquals("french", GameLanguages.defaultGameLanguage(Locale("fr", "FR")))
        assertEquals("russian", GameLanguages.defaultGameLanguage(Locale("ru", "RU")))
        assertEquals("ukrainian", GameLanguages.defaultGameLanguage(Locale("uk", "UA")))
        assertEquals("koreana", GameLanguages.defaultGameLanguage(Locale("ko", "KR")))
        assertEquals("japanese", GameLanguages.defaultGameLanguage(Locale("ja", "JP")))
    }

    @Test
    fun `splits the languages steam treats specially`() {
        assertEquals("latam", GameLanguages.defaultGameLanguage(Locale("es", "MX")))
        assertEquals("spanish", GameLanguages.defaultGameLanguage(Locale("es", "ES")))
        assertEquals("brazilian", GameLanguages.defaultGameLanguage(Locale("pt", "BR")))
        assertEquals("portuguese", GameLanguages.defaultGameLanguage(Locale("pt", "PT")))
        assertEquals("tchinese", GameLanguages.defaultGameLanguage(Locale("zh", "TW")))
        assertEquals("schinese", GameLanguages.defaultGameLanguage(Locale("zh", "CN")))
    }

    @Test
    fun `falls back to english for languages no store mapping knows`() {
        // Hebrew/Indonesian have display names but no Steam/container code.
        assertEquals("english", GameLanguages.defaultGameLanguage(Locale("he", "IL")))
        assertEquals("english", GameLanguages.defaultGameLanguage(Locale("id", "ID")))
        assertEquals("english", GameLanguages.defaultGameLanguage(Locale.ROOT))
    }

    // -- Supported language filtering --

    @Test
    fun `filterSupported keeps picker order and drops languages the game lacks`() {
        assertEquals(
            listOf("english", "german", "russian"),
            GameLanguages.filterSupported(listOf("russian", "german", "english")),
        )
        // No data must read as "unknown", not as "no languages".
        assertEquals(emptyList<String>(), GameLanguages.filterSupported(emptyList()))
    }

    @Test
    fun `filterSupportedByStoreCodes matches store codes such as en and de-DE`() {
        val gogLike = mapOf(
            "english" to listOf("english", "en-US", "en"),
            "german" to listOf("german", "de-DE", "de"),
            "russian" to listOf("russian", "ru-RU", "ru"),
            "brazilian" to listOf("brazilian", "pt-BR", "br"),
        )
        assertEquals(
            listOf("english", "german"),
            GameLanguages.filterSupportedByStoreCodes(listOf("en", "de-DE"), gogLike),
        )
        assertEquals(
            listOf("brazilian"),
            GameLanguages.filterSupportedByStoreCodes(listOf("pt_BR"), gogLike),
        )
        assertEquals(emptyList<String>(), GameLanguages.filterSupportedByStoreCodes(emptyList(), gogLike))
        // A store code that maps to no container language leaves the list unknown.
        assertEquals(emptyList<String>(), GameLanguages.filterSupportedByStoreCodes(listOf("xx"), gogLike))
    }

    @Test
    fun `preselected keeps a supported choice and otherwise prefers english`() {
        assertEquals("german", GameLanguages.preselected("german", listOf("english", "german")))
        assertEquals("english", GameLanguages.preselected("french", listOf("english", "german")))
        assertEquals("german", GameLanguages.preselected("french", listOf("german")))
        assertEquals("french", GameLanguages.preselected("french", emptyList()))
        assertEquals("english", GameLanguages.preselected("", emptyList()))
    }

    @Test
    fun `displayName spells out the steam specific codes`() {
        assertEquals("Simplified Chinese", GameLanguages.displayName("schinese"))
        assertEquals("Traditional Chinese", GameLanguages.displayName("tchinese"))
        assertEquals("Korean", GameLanguages.displayName("koreana"))
        assertEquals("Portuguese (Brazil)", GameLanguages.displayName("brazilian"))
        assertEquals("Klingon", GameLanguages.displayName("klingon"))
    }

    // -- Steam depots --

    private fun manifest() = ManifestInfo(name = "public", gid = 1L, size = 1000L, download = 800L)

    private fun depot(
        depotId: Int,
        language: String,
        dlcAppId: Int = SteamService.INVALID_APP_ID,
        osList: EnumSet<OS> = EnumSet.of(OS.windows),
    ) = DepotInfo(
        depotId = depotId,
        dlcAppId = dlcAppId,
        depotFromApp = 0,
        sharedInstall = false,
        osList = osList,
        osArch = OSArch.Arch64,
        manifests = mapOf("public" to manifest()),
        encryptedManifests = emptyMap(),
        language = language,
    )

    @Test
    fun `supported depot languages ignore neutral, DLC and non-windows depots`() {
        val depots = linkedMapOf(
            1 to depot(1, ""), // language-neutral main content: does not name a language
            2 to depot(2, "russian"),
            3 to depot(3, "german"),
            4 to depot(4, "french", dlcAppId = 4242), // DLC depot must not steer the base game
            5 to depot(5, "english", osList = EnumSet.of(OS.linux)), // not installable here
        )
        // The neutral depot is filtered out here; the dialog keeps showing the full list only
        // when this result is empty.
        val supported = GameLanguages.filterSupported(
            depots.values
                .filter { it.dlcAppId == SteamService.INVALID_APP_ID && it.isWindowsCompatible }
                .map { it.language },
        )
        assertEquals(listOf("german", "russian"), supported)
    }

    @Test
    fun `russian selection uses the russian depot and leaves the german one out`() {
        val depots = linkedMapOf(
            1 to depot(1, ""), // language-neutral content installs with any language
            2 to depot(2, "russian"),
            3 to depot(3, "german"),
        )
        assertEquals("russian", SteamUtils.effectiveDepotLanguage(depots, "russian", null, null))
        // The untagged depot satisfies any request, so Russian stays selected instead of drifting
        // to the tagged German localization.
        assertEquals("russian", SteamUtils.effectiveDepotLanguage(linkedMapOf(1 to depot(1, ""), 3 to depot(3, "german")), "russian", null, null))
    }
}

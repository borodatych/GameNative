package app.gamenative.steambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSelectorTest {
    private fun bridge(
        id: String,
        vararg interfaces: String,
        major: Int = 11,
        arch: BridgeArch = BridgeArch.ARM64EC,
        source: BridgeSource = BridgeSource.ARCHIVE,
    ) = BridgeDescriptor(
        id = id,
        protonMajor = major,
        arch = arch,
        source = source,
        location = "/wine/lib/wine/${arch.windowsDir}/lsteamclient.dll",
        interfaces = interfaces.toSet(),
        label = id,
    )

    private val hogwartsRequest = setOf("SteamUtils011")

    @Test
    fun compatibleOverrideWinsOverAutoSelection() {
        val bundled = bridge("bundled", "SteamUtils011", "SteamUser023", "SteamClient023", source = BridgeSource.BUNDLED_IN_PROTON)
        val archive = bridge("archive", "SteamUtils011", "SteamUser023")

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(bundled, archive), "archive")

        assertEquals("archive", result.chosen?.id)
        assertTrue(result.overrideApplied)
        assertNull(result.overrideIgnoredReason)
        assertTrue(result.missingInterfaces.isEmpty())
    }

    @Test
    fun compatibleOverrideReportsMissingInterfaces() {
        val archive = bridge("archive", "SteamUser023")

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(archive), "archive")

        assertEquals("archive", result.chosen?.id)
        assertTrue(result.overrideApplied)
        assertEquals(hogwartsRequest, result.missingInterfaces)
    }

    @Test
    fun overrideFromAnotherProtonMajorIsIgnored() {
        // Мажор передаём позиционно: позиционные аргументы идут строго по порядку параметров.
        val forProton10 = bridge("archive10", "SteamUtils011", major = 10, arch = BridgeArch.ARM64EC, source = BridgeSource.ARCHIVE)
        val forProton11 = bridge("archive11", "SteamUtils011", major = 11, arch = BridgeArch.ARM64EC, source = BridgeSource.ARCHIVE)

        val result = BridgeSelector.select(
            11, BridgeArch.ARM64EC, hogwartsRequest, listOf(forProton10, forProton11), "archive10",
        )

        assertFalse(result.overrideApplied)
        assertEquals("archive11", result.chosen?.id)
        assertNotNull(result.overrideIgnoredReason)
        assertTrue(result.overrideIgnoredReason!!.contains("Proton 10"))
    }

    @Test
    fun overrideFromAnotherArchitectureIsIgnored() {
        val x86 = bridge("x86", "SteamUtils011", major = 11, arch = BridgeArch.X86_64, source = BridgeSource.ARCHIVE)
        val arm = bridge("arm", "SteamUtils011", major = 11, arch = BridgeArch.ARM64EC, source = BridgeSource.ARCHIVE)

        val result = BridgeSelector.select(
            11, BridgeArch.ARM64EC, hogwartsRequest, listOf(x86, arm), "x86",
        )

        assertFalse(result.overrideApplied)
        assertEquals("arm", result.chosen?.id)
        assertNotNull(result.overrideIgnoredReason)
        assertTrue(result.overrideIgnoredReason!!.contains("arm64ec"))
    }

    @Test
    fun unknownOverrideIsIgnoredWithReason() {
        val bridge = BridgeDescriptor(
            id = "bundled",
            protonMajor = 11,
            arch = BridgeArch.ARM64EC,
            source = BridgeSource.BUNDLED_IN_PROTON,
            location = "/bundled/lsteamclient.dll",
            interfaces = hogwartsRequest,
        )

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(bridge), "gone")

        assertFalse(result.overrideApplied)
        assertEquals("bundled", result.chosen?.id)
        assertTrue(result.overrideIgnoredReason!!.contains("gone"))
    }

    @Test
    fun autoModeTreatsBlankAndAutoAsNoOverride() {
        val archiveBridge = bridge("archive", "SteamUtils011")
        for (value in listOf(null, "", "  ", "auto", "AUTO")) {
            val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(archiveBridge), value)
            assertFalse(result.overrideApplied)
            assertEquals("archive", result.chosen?.id)
            assertNull(result.overrideIgnoredReason)
        }
    }

    @Test
    fun prefersCandidateCoveringAllRequiredInterfaces() {
        val partialNewer = bridge("partial", "SteamUtils010", "SteamUser024", "SteamClient024")
        val covering = bridge("covering", "SteamUtils011")

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(partialNewer, covering))

        assertEquals("covering", result.chosen?.id)
        assertTrue(result.missingInterfaces.isEmpty())
    }

    @Test
    fun perfersNewerAmongFullyCoveringCandidates() {
        val older = bridge("older", "SteamUtils010", "SteamUtils011", "SteamUser022")
        val newer = bridge("newer", "SteamUtils010", "SteamUtils011", "SteamUser023", "SteamFriends017")

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(older, newer))

        assertEquals("newer", result.chosen?.id)
    }

    @Test
    fun bundledBridgeWinsTieWithArchive() {
        val archive = bridge("archive", "SteamUtils011", "SteamUser023")
        val bundled = bridge("bundled", "SteamUtils011", "SteamUser023", source = BridgeSource.BUNDLED_IN_PROTON)

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(archive, bundled))

        assertEquals("bundled", result.chosen?.id)
    }

    @Test
    fun equalCountIsDecidedByHighestVersionPerFamily() {
        val older = bridge("older", "SteamUtils010", "SteamFriends017")
        val newer = bridge("newer", "SteamUtils011", "SteamFriends017")

        // Оба покрывают запрос, интерфейсов поровну, SteamFriends совпадает — решает версия SteamUtils
        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, setOf("SteamFriends017"), listOf(older, newer))

        assertEquals("newer", result.chosen?.id)
    }

    @Test
    fun partialCoveragePicksFewestMissingAndReportsThem() {
        val twoMissing = bridge("two-missing", "SteamUser023")
        val oneMissing = bridge("one-missing", "SteamUtils011")

        val result = BridgeSelector.select(
            11,
            BridgeArch.ARM64EC,
            setOf("SteamUtils011", "SteamFriends017"),
            listOf(twoMissing, oneMissing),
        )

        assertEquals("one-missing", result.chosen?.id)
        assertEquals(setOf("SteamFriends017"), result.missingInterfaces)
    }

    @Test
    fun noCompatibleCandidatesYieldsNoChoice() {
        val forProton9 = bridge("old", "SteamUtils011", major = 9, arch = BridgeArch.ARM64EC, source = BridgeSource.ARCHIVE)

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, hogwartsRequest, listOf(forProton9))

        assertNull(result.chosen)
        assertEquals(hogwartsRequest, result.missingInterfaces)
        assertFalse(result.overrideApplied)
    }

    @Test
    fun emptyRequiredInterfacesPicksNewestCompatible() {
        val small = bridge("small", "SteamUtils011")
        val big = bridge("big", "SteamUtils011", "SteamUser023", source = BridgeSource.BUNDLED_IN_PROTON)

        val result = BridgeSelector.select(11, BridgeArch.ARM64EC, emptySet(), listOf(small, big))

        assertEquals("big", result.chosen?.id)
        assertTrue(result.missingInterfaces.isEmpty())
    }

    @Test
    fun archiveCatalogIsKeyedByMajorAndArchOnly() {
        assertEquals("lsteamclient-arm64ec-proton11.tzst", BridgeArchiveCatalog.archiveFor(11, BridgeArch.ARM64EC)?.archiveName)
        assertEquals("lsteamclient-x86_64-proton10.tzst", BridgeArchiveCatalog.archiveFor(10, BridgeArch.X86_64)?.archiveName)
        // Для незнакомого мажора архив соседнего не подставляем: ABI другой.
        assertNull(BridgeArchiveCatalog.archiveFor(12, BridgeArch.ARM64EC))
        assertNull(BridgeArchiveCatalog.archiveFor(8, BridgeArch.X86_64))
        assertEquals(
            BridgeArchiveCatalog.archiveFor(11, BridgeArch.ARM64EC),
            BridgeArchiveCatalog.byId("archive:proton11:arm64ec"),
        )
    }

    @Test
    fun archParsedFromWineVersion() {
        assertEquals(BridgeArch.ARM64EC, BridgeArch.fromWineVersion("proton-11.0-2-arm64ec-100"))
        assertEquals(BridgeArch.X86_64, BridgeArch.fromWineVersion("proton-11.0-2-x86_64-11"))
    }
}

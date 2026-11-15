package app.gamenative.steambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SteamInterfaceScannerTest {
    private fun bytes(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun nul() = byteArrayOf(0)

    @Test
    fun findsInterfaceNamesBetweenNulBytes() {
        val data = bytes(
            ascii("SteamUtils011"), nul(),
            ascii("SteamClient023"), nul(),
            ascii("SteamUser023"), nul(),
        )
        assertEquals(setOf("SteamUtils011", "SteamClient023", "SteamUser023"), SteamInterfaceScanner.scan(data))
    }

    @Test
    fun findsUpperSnakeInterfaceVersion() {
        val data = bytes(ascii("STEAMAPPS_INTERFACE_VERSION008"), nul())
        assertEquals(setOf("STEAMAPPS_INTERFACE_VERSION008"), SteamInterfaceScanner.scan(data))
    }

    @Test
    fun rejectsTokenWithExtraTrailingDigit() {
        // "SteamUtils0111" — это не имя интерфейса, а часть более длинной строки.
        val data = bytes(ascii("SteamUtils0111"), nul())
        assertTrue(SteamInterfaceScanner.scan(data).isEmpty())
    }

    @Test
    fun rejectsTokenGluedToPrecedingPrintableText() {
        val data = bytes(ascii("xSteamUtils011"), nul())
        assertTrue(SteamInterfaceScanner.scan(data).isEmpty())
    }

    @Test
    fun acceptsNonPrintableBoundariesOtherThanNul() {
        val data = bytes(
            byteArrayOf(0x01),
            ascii("SteamUtils011"),
            byteArrayOf(0x7F),
            ascii("SteamFriends017"),
        )
        assertEquals(setOf("SteamUtils011", "SteamFriends017"), SteamInterfaceScanner.scan(data))
    }

    @Test
    fun ignoresNamesWithoutVersionSuffix() {
        val data = bytes(ascii("SteamUtils"), nul(), ascii("SteamClient"), nul())
        assertTrue(SteamInterfaceScanner.scan(data).isEmpty())
    }

    @Test
    fun rejectsOverlongPrintableRuns() {
        val data = bytes(ByteArray(300) { 'A'.code.toByte() }, nul(), ascii("SteamUtils011"), nul())
        assertEquals(setOf("SteamUtils011"), SteamInterfaceScanner.scan(data))
    }

    @Test
    fun findsTokenAcrossChunkBoundaryInFile() {
        val file = File.createTempFile("scanner-boundary", ".dll")
        try {
            // Токен начинается за несколько байт до границы 64-КБ чанка.
            val prefix = ByteArray(64 * 1024 - 4)
            val data = bytes(prefix, ascii("SteamUtils011"), nul(), ascii("SteamUser023"))
            file.writeBytes(data)
            assertEquals(setOf("SteamUtils011", "SteamUser023"), SteamInterfaceScanner.scan(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun findsSteamApiDllsInsideGameTree() {
        val root = File.createTempFile("game-root", "")
        try {
            root.delete()
            val nested = File(root, "Engine/Binaries/ThirdParty/Steamworks/Steamv165/Win64")
            nested.mkdirs()
            val win64Dll = File(nested, "steam_api64.dll")
            win64Dll.writeBytes(bytes(ascii("SteamUtils011"), nul()))
            val rootDll = File(root, "steam_api.dll")
            rootDll.writeBytes(bytes(ascii("SteamUser023"), nul()))

            val found = SteamInterfaceScanner.findSteamApiDlls(root)
            assertEquals(setOf("steam_api64.dll", "steam_api.dll"), found.map { it.name }.toSet())
            assertEquals(
                setOf("SteamUtils011", "SteamUser023"),
                SteamInterfaceScanner.scanAll(found),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryYieldsNoDlls() {
        assertTrue(SteamInterfaceScanner.findSteamApiDlls(File("/definitely/not/here")).isEmpty())
    }

    @Test
    fun parsesInterfaceFamilyAndVersion() {
        assertEquals(SteamInterfaceVersion("SteamUtils", 11), SteamInterfaceVersion.parse("SteamUtils011"))
        assertEquals(
            SteamInterfaceVersion("STEAMAPPS_INTERFACE_VERSION", 8),
            SteamInterfaceVersion.parse("STEAMAPPS_INTERFACE_VERSION008"),
        )
        assertEquals(null, SteamInterfaceVersion.parse("SteamUtils"))
    }

    @Test
    fun keepsHighestVersionPerFamily() {
        val versions = SteamInterfaceVersion.highestPerFamily(
            setOf("SteamUtils010", "SteamUtils011", "SteamUser023"),
        )
        assertEquals(mapOf("SteamUtils" to 11, "SteamUser" to 23), versions)
    }
}

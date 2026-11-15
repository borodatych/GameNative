package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamesRootPathsTest {

    private val primary = "/storage/emulated/0"

    @Test
    fun `default root is Games on primary storage`() {
        assertEquals("/storage/emulated/0/Games", GamesRootPaths.defaultRoot(primary))
    }

    @Test
    fun `configured root wins over default`() {
        assertEquals("/storage/1A2B-3C4D/MyGames", GamesRootPaths.effectiveRoot("/storage/1A2B-3C4D/MyGames", "/storage/emulated/0/Games"))
    }

    @Test
    fun `blank configured root falls back to default`() {
        assertEquals("/storage/emulated/0/Games", GamesRootPaths.effectiveRoot("", "/storage/emulated/0/Games"))
        assertEquals("/storage/emulated/0/Games", GamesRootPaths.effectiveRoot("   ", "/storage/emulated/0/Games"))
    }

    @Test
    fun `steam install path lives under the games root`() {
        assertEquals(
            "/storage/emulated/0/Games/Steam/steamapps/common",
            GamesRootPaths.steamCommon("/storage/emulated/0/Games"),
        )
        assertEquals(
            "/storage/emulated/0/Games/Steam/steamapps/staging",
            GamesRootPaths.steamStaging("/storage/emulated/0/Games"),
        )
    }

    @Test
    fun `store paths keep their existing layout under the root`() {
        val root = "/storage/emulated/0/Games"
        assertEquals("$root/GOG/games/common", GamesRootPaths.gogCommon(root))
        assertEquals("$root/Epic/games", GamesRootPaths.epicGames(root))
        assertEquals("$root/Amazon/games", GamesRootPaths.amazonGames(root))
    }

    @Test
    fun `primary tree uri maps to internal storage path`() {
        assertEquals(
            "/storage/emulated/0/Games",
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/primary%3AGames",
                primary,
            ),
        )
    }

    @Test
    fun `tree uri with nested folder keeps every segment`() {
        assertEquals(
            "/storage/emulated/0/Games/Steam",
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/primary%3AGames%2FSteam",
                primary,
            ),
        )
    }

    @Test
    fun `tree uri with document suffix is accepted`() {
        assertEquals(
            "/storage/emulated/0/Games",
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/primary%3AGames/document/primary%3AGames",
                primary,
            ),
        )
    }

    @Test
    fun `sd card tree uri maps to storage uuid path`() {
        assertEquals(
            "/storage/1A2B-3C4D/MyGames",
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMyGames",
                primary,
            ),
        )
    }

    @Test
    fun `volume root without relative folder maps to volume`() {
        assertEquals(
            "/storage/1A2B-3C4D",
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A",
                primary,
            ),
        )
    }

    @Test
    fun `non local providers are rejected`() {
        assertNull(
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.google.android.apps.docs.storage/tree/abc",
                primary,
            ),
        )
        assertNull(GamesRootPaths.treeUriToAbsolutePath(null, primary))
        assertNull(GamesRootPaths.treeUriToAbsolutePath("", primary))
    }

    @Test
    fun `home volume is rejected`() {
        assertNull(
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/home%3AGames",
                primary,
            ),
        )
    }

    @Test
    fun `parent traversal is rejected`() {
        assertNull(
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/primary%3AGames%2F..%2F..%2Fetc",
                primary,
            ),
        )
    }

    @Test
    fun `percent encoded space is decoded`() {
        assertEquals(
            "/storage/emulated/0/My Games",
            GamesRootPaths.treeUriToAbsolutePath(
                "content://com.android.externalstorage.documents/tree/primary%3AMy%20Games",
                primary,
            ),
        )
    }
}

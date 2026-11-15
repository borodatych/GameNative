package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorResolverTest {

    @Test
    fun mapsPrimaryUrlToAllMirrorsInOrder() {
        assertEquals(
            listOf(
                "https://downloads.gamenative.app/container_files/extras.tzst",
                "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/container_files/extras.tzst",
                "https://github.com/borodatych/ThorHub/releases/download/mirror/container_files__extras.tzst",
            ),
            MirrorResolver.candidates("https://downloads.gamenative.app/container_files/extras.tzst"),
        )
    }

    @Test
    fun flattensEveryPathSegmentForThorHub() {
        val candidates = MirrorResolver.candidates(
            "https://downloads.gamenative.app/a/b/c/file.tzst",
        )

        assertEquals(
            "https://github.com/borodatych/ThorHub/releases/download/mirror/a__b__c__file.tzst",
            candidates[2],
        )
    }

    @Test
    fun keepsQueryOnlyForTheOriginalUrl() {
        val candidates = MirrorResolver.candidates(
            "https://downloads.gamenative.app/dir/file.zip?token=abc",
        )

        assertEquals("https://downloads.gamenative.app/dir/file.zip?token=abc", candidates[0])
        assertEquals(
            "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/dir/file.zip",
            candidates[1],
        )
        assertEquals(
            "https://github.com/borodatych/ThorHub/releases/download/mirror/dir__file.zip",
            candidates[2],
        )
    }

    @Test
    fun leavesForeignHostsAlone() {
        assertEquals(
            listOf("https://api.gamenative.app/api/update-check"),
            MirrorResolver.candidates("https://api.gamenative.app/api/update-check"),
        )
    }

    @Test
    fun leavesNonHttpUrlsAlone() {
        assertEquals(listOf("file:///tmp/file.tzst"), MirrorResolver.candidates("file:///tmp/file.tzst"))
    }

    @Test
    fun rootUrlHasNoMirrors() {
        assertEquals(
            listOf("https://downloads.gamenative.app/"),
            MirrorResolver.candidates("https://downloads.gamenative.app/"),
        )
    }

    @Test
    fun recognisesPrimaryHostRegardlessOfCaseAndPort() {
        val candidates = MirrorResolver.candidates("https://Downloads.GameNative.App:443/x.tzst")

        assertEquals(3, candidates.size)
        assertEquals(
            "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/x.tzst",
            candidates[1],
        )
    }

    @Test
    fun primaryUrlBuildsFromFileName() {
        assertEquals(
            "https://downloads.gamenative.app/container_files/extras.tzst",
            DownloadMirrors.primaryUrl("container_files/extras.tzst"),
        )
        assertEquals(
            "https://downloads.gamenative.app/steam.tzst",
            DownloadMirrors.primaryUrl("/steam.tzst"),
        )
    }
}

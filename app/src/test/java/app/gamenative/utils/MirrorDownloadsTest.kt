package app.gamenative.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MirrorDownloadsTest {

    private val candidates = listOf("https://primary/file", "https://r2/file", "https://github/file")

    @Test
    fun stopsAtFirstWorkingSource() = runBlocking {
        val tried = mutableListOf<String>()

        val result = MirrorDownloads.tryInOrder(candidates) { url ->
            tried += url
            "content of $url"
        }

        assertEquals("content of https://primary/file", result)
        assertEquals(listOf("https://primary/file"), tried)
    }

    @Test
    fun movesToNextSourceOnNetworkErrorAndReportsPartialFile() = runBlocking {
        val tried = mutableListOf<String>()
        var partialDeleted = 0

        val result = MirrorDownloads.tryInOrder(
            candidates,
            onAttemptFailed = { partialDeleted++ },
        ) { url ->
            tried += url
            if (url != "https://github/file") throw IOException("connect timed out")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(candidates, tried)
        assertEquals(2, partialDeleted)
    }

    @Test
    fun movesToNextSourceOnHttpError() = runBlocking {
        val tried = mutableListOf<String>()

        val result = MirrorDownloads.tryInOrder(candidates) { url ->
            tried += url
            if (url == "https://primary/file") throw DownloadHttpException(url, 404)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("https://primary/file", "https://r2/file"), tried)
    }

    @Test
    fun doesNotRetryOnNonNetworkFailure() = runBlocking {
        val tried = mutableListOf<String>()

        try {
            MirrorDownloads.tryInOrder(candidates) { url ->
                tried += url
                throw IllegalStateException("archive is corrupt")
            }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("archive is corrupt", e.message)
        }

        assertEquals(listOf("https://primary/file"), tried)
    }

    @Test
    fun throwsLastErrorWhenEverySourceFailed() = runBlocking {
        var attempts = 0

        try {
            MirrorDownloads.tryInOrder(candidates) { url ->
                attempts++
                throw DownloadHttpException(url, 500)
            }
            fail("expected DownloadHttpException")
        } catch (e: DownloadHttpException) {
            assertEquals("https://github/file", e.url)
            assertEquals(500, e.code)
        }

        assertEquals(3, attempts)
    }

    @Test
    fun firstNonNullReturnsFirstAvailableDocument() = runBlocking {
        val tried = mutableListOf<String>()

        val result = MirrorDownloads.firstNonNull("https://downloads.gamenative.app/dir/doc.json") { url ->
            tried += url
            if (url.contains("r2.dev")) "{doc}" else null
        }

        assertEquals("{doc}", result)
        assertEquals(2, tried.size)
        assertTrue(tried[0].startsWith("https://downloads.gamenative.app/"))
    }

    @Test
    fun firstNonNullSwallowsNetworkErrorsBetweenSources() = runBlocking {
        val result = MirrorDownloads.firstNonNull("https://downloads.gamenative.app/doc.json") { url ->
            if (url == "https://downloads.gamenative.app/doc.json") throw IOException("timeout")
            "{doc}"
        }

        assertEquals("{doc}", result)
    }

    @Test
    fun firstNonNullReturnsNullWhenEverySourceSilent() = runBlocking {
        assertEquals(
            null,
            MirrorDownloads.firstNonNull<String>("https://downloads.gamenative.app/doc.json") { null },
        )
    }
}

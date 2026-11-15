package app.gamenative.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class MirrorDownloadsTest {

    private val candidates = listOf("https://primary/file", "https://r2/file", "https://github/file")

    // JUnit создаёт новый экземпляр теста на каждый метод, поэтому состояние хостов не протекает между тестами.
    private val health = MirrorHostHealth()

    @Test
    fun stopsAtFirstWorkingSource() = runBlocking {
        val tried = mutableListOf<String>()

        val result = MirrorDownloads.tryInOrder(candidates, health = health) { url ->
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
            health = health,
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

        val result = MirrorDownloads.tryInOrder(candidates, health = health) { url ->
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
            MirrorDownloads.tryInOrder(candidates, health = health) { url ->
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
            MirrorDownloads.tryInOrder(candidates, health = health) { url ->
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

        val result = MirrorDownloads.firstNonNull(
            "https://downloads.gamenative.app/dir/doc.json",
            health = health,
        ) { url ->
            tried += url
            if (url.contains("r2.dev")) "{doc}" else null
        }

        assertEquals("{doc}", result)
        assertEquals(2, tried.size)
        assertTrue(tried[0].startsWith("https://downloads.gamenative.app/"))
    }

    @Test
    fun firstNonNullSwallowsNetworkErrorsBetweenSources() = runBlocking {
        val result = MirrorDownloads.firstNonNull(
            "https://downloads.gamenative.app/doc.json",
            health = health,
        ) { url ->
            if (url == "https://downloads.gamenative.app/doc.json") throw IOException("timeout")
            "{doc}"
        }

        assertEquals("{doc}", result)
    }

    @Test
    fun firstNonNullReturnsNullWhenEverySourceSilent() = runBlocking {
        assertEquals(
            null,
            MirrorDownloads.firstNonNull<String>(
                "https://downloads.gamenative.app/doc.json",
                health = health,
            ) { null },
        )
    }

    // --- Память о больных хостах ---

    @Test
    fun downHostMovesToTheEndKeepingOriginalOrder() {
        health.markDown("https://r2/file")

        assertEquals(
            listOf("https://primary/file", "https://github/file", "https://r2/file"),
            health.order(candidates),
        )
    }

    @Test
    fun cooldownExpiresWithFakeClock() {
        var now = 1_000L
        val timed = MirrorHostHealth(clock = { now })
        timed.markDown("https://primary/file")
        assertTrue(timed.isDown("https://primary/file"))

        // За миг до конца кулдауна хост ещё считается больным.
        now += MirrorHostHealth.DEFAULT_COOLDOWN_MS - 1
        assertTrue(timed.isDown("https://primary/file"))

        // Ровно в момент истечения — снова здоров.
        now += 1
        assertFalse(timed.isDown("https://primary/file"))
    }

    @Test
    fun repeatedFailureExtendsTheCooldown() {
        var now = 1_000L
        val timed = MirrorHostHealth(clock = { now })
        timed.markDown("https://primary/file")

        now += MirrorHostHealth.DEFAULT_COOLDOWN_MS - 1
        timed.markDown("https://primary/file")
        now += 1

        assertTrue(timed.isDown("https://primary/file"))
    }

    @Test
    fun http404DoesNotMarkHostDown() {
        health.recordFailure("https://primary/file", DownloadHttpException("https://primary/file", 404))

        assertFalse(health.isDown("https://primary/file"))
    }

    @Test
    fun http5xxMarksHostDown() {
        health.recordFailure("https://r2/file", DownloadHttpException("https://r2/file", 503))

        assertTrue(health.isDown("https://r2/file"))
    }

    @Test
    fun connectTimeoutMarksHostDown() {
        health.recordFailure("https://primary/file", SocketTimeoutException("connect timed out"))

        assertTrue(health.isDown("https://primary/file"))
    }

    @Test
    fun nonNetworkFailureDoesNotMarkHostDown() {
        health.recordFailure("https://primary/file", IllegalStateException("archive is corrupt"))

        assertFalse(health.isDown("https://primary/file"))
    }

    @Test
    fun healthIsRememberedPerHostNotPerFile() {
        health.markDown("https://primary/file")

        assertTrue(health.isDown("https://primary/another.bin"))
        assertFalse(health.isDown("https://r2/another.bin"))
    }

    @Test
    fun successClearsDownMark() {
        health.markDown("https://primary/file")
        health.markUp("https://primary/file")

        assertFalse(health.isDown("https://primary/file"))
    }

    @Test
    fun tryInOrderTriesDownHostLastButNeverSkipsIt() = runBlocking {
        health.markDown("https://primary/file")
        val tried = mutableListOf<String>()

        try {
            MirrorDownloads.tryInOrder(candidates, health = health) { url ->
                tried += url
                throw IOException("network down")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("network down", e.message)
        }

        // Больной хост в конце, но попытка всё равно делается.
        assertEquals(
            listOf("https://r2/file", "https://github/file", "https://primary/file"),
            tried,
        )
    }

    @Test
    fun tryInOrderClearsDownMarkOnSuccess() = runBlocking {
        health.markDown("https://primary/file")

        MirrorDownloads.tryInOrder(listOf("https://primary/file"), health = health) { "ok" }

        assertFalse(health.isDown("https://primary/file"))
    }

    @Test
    fun firstNonNullPutsDownHostLast() = runBlocking {
        health.markDown("https://downloads.gamenative.app/")
        val tried = mutableListOf<String>()

        val result = MirrorDownloads.firstNonNull(
            "https://downloads.gamenative.app/doc.json",
            health = health,
        ) { url ->
            tried += url
            if (url.startsWith("https://pub-")) "{doc}" else null
        }

        assertEquals("{doc}", result)
        assertEquals(
            listOf("https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/doc.json"),
            tried,
        )
    }

    @Test
    fun firstNonNullDoesNotMarkHostDownOn404() = runBlocking {
        val url = "https://downloads.gamenative.app/doc.json"

        MirrorDownloads.firstNonNull(url, health = health) { candidate ->
            if (candidate == url) throw DownloadHttpException(candidate, 404)
            null
        }

        assertFalse(health.isDown(url))
    }
}

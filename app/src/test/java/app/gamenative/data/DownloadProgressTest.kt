package app.gamenative.data

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `progress counts every depot of the run including dlc`() {
        val expected = mapOf(
            241931 to 18 * gb, // база
            241933 to 40 * gb, // второй крупный депот
            999 to 2 * gb, // DLC
        )
        val done = mapOf(241931 to 18 * gb)

        val progress = computeDownloadProgress(done, emptyMap(), expected, completed = false)

        // Первый депот закончен, но загрузка — нет: никаких 100%.
        assertEquals(18.0 / 60.0, progress.toDouble(), 0.0001)
        assertTrue(progress < 1f)
    }

    @Test
    fun `engine total replaces a bad appinfo estimate`() {
        // appinfo не знает размер депота (1 байт-заглушка), движок сообщил настоящий.
        val expected = mapOf(241931 to 18 * gb, 241933 to 1L)
        val done = mapOf(241931 to 18 * gb)
        val engineTotals = mapOf(241933 to 40 * gb)

        val progress = computeDownloadProgress(done, engineTotals, expected, completed = false)

        assertEquals(18.0 / 58.0, progress.toDouble(), 0.0001)
    }

    @Test
    fun `hundred percent only after completion`() {
        val totals = mapOf(1 to gb, 2 to gb)
        val done = mapOf(1 to gb, 2 to gb)

        val running = computeDownloadProgress(done, totals, emptyMap(), completed = false)
        val finished = computeDownloadProgress(done, totals, emptyMap(), completed = true)

        assertTrue(running < 1f)
        assertEquals(1.0, finished.toDouble(), 0.0)
    }

    @Test
    fun `progress is clamped when engine reports more bytes than the total`() {
        val progress = computeDownloadProgress(
            doneBytesByDepot = mapOf(1 to 3 * gb),
            totalBytesByDepot = mapOf(1 to gb),
            expectedBytesByDepot = emptyMap(),
            completed = false,
        )
        assertTrue(progress < 1f)
    }

    @Test
    fun `download info does not go back when the depot size is corrected`() {
        val info = DownloadInfo(jobCount = 2, gameId = 1, downloadingAppIds = CopyOnWriteArrayList())

        // Оценка appinfo занижена для второго депота: первый уже «закончен».
        info.setExpectedDepotBytes(mapOf(1 to 100L, 2 to 1L))
        info.reportDepotProgress(depotId = 1, doneBytes = 100L, totalBytes = 100L)
        val first = info.getProgress()
        assertTrue(first < 1f)

        // Движок уточнил размер второго депота — процент не должен откатиться назад.
        info.reportDepotProgress(depotId = 2, doneBytes = 50L, totalBytes = 1000L)
        val afterCorrection = info.getProgress()
        assertTrue(afterCorrection >= first)

        info.markProgressCompleted()
        assertEquals(1.0, info.getProgress().toDouble(), 0.0)
    }
}

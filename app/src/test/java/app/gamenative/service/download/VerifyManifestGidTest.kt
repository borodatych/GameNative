package app.gamenative.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * VERIFY не отказывается от депота без записанного установленного манифеста: у игры, перенесённой
 * из другого лаунчера, такой записи нет, и проверять её надо по текущему манифесту ветки (как
 * UPDATE). Записанный gid по-прежнему важнее текущего — иначе проверка тихо обновила бы игру.
 */
class VerifyManifestGidTest {

    @Test
    fun theInstalledManifestWinsAndTheBranchIsNotAsked() {
        var currentAsked = false
        val gid = verifyManifestGid(7uL) {
            currentAsked = true
            42L
        }
        assertEquals(7L, gid)
        assertFalse(currentAsked)
    }

    @Test
    fun aDepotWithNoInstalledRecordFallsBackToTheBranchManifest() {
        assertEquals(42L, verifyManifestGid(null) { 42L })
    }

    @Test
    fun aZeroRecordedGidIsNotAPin() {
        assertEquals(42L, verifyManifestGid(0uL) { 42L })
    }

    @Test
    fun aHighBitGidSurvivesAsTheSignedLongTheEngineExpects() {
        // gid — uint64: в Kotlin он живёт как Long с тем же битовым набором, а не как 0.
        assertEquals(-1L, verifyManifestGid(ULong.MAX_VALUE) { 42L })
    }

    @Test
    fun anUnresolvableBranchGidStaysZeroSoTheDepotIsSkipped() {
        assertEquals(0L, verifyManifestGid(null) { 0L })
    }
}

package app.gamenative.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ошибка загрузки не должна переводить установленную игру в «недокачано»: иначе UI показывает
 * «Возобновить» вместо «Играть», а повтор берёт сохранённый режим и падает тем же местом.
 * Незавершённая установка, наоборот, остаётся частичной — иначе пропадёт возобновление.
 */
class SteamFailureStateTest {

    @Test
    fun anInstalledGameStaysInstalledAfterAFailedVerifyOrUpdate() {
        assertTrue(keepsInstalledStateAfterFailure(wasInstalledBeforeRun = true))
    }

    @Test
    fun aFailedFreshInstallKeepsItsPartialRecord() {
        assertFalse(keepsInstalledStateAfterFailure(wasInstalledBeforeRun = false))
    }
}

package app.gamenative.steambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InterfaceScanCacheTest {
    private fun writeDll(dir: File, name: String, vararg tokens: String): File {
        val file = File(dir, name)
        val data = tokens.fold(ByteArray(0)) { acc, token ->
            acc + token.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        }
        file.writeBytes(data)
        return file
    }

    @Test
    fun cachesScanResultAndReusesItAfterReload() {
        val dir = File.createTempFile("bridge-cache", "").apply { delete(); mkdirs() }
        try {
            val dll = writeDll(dir, "steam_api64.dll", "SteamUtils011")
            val cacheFile = File(dir, "steambridges/cache.json")

            val first = InterfaceScanCache(cacheFile)
            assertEquals(setOf("SteamUtils011"), first.interfacesOf(dll))
            assertTrue(cacheFile.isFile)

            // Новый экземпляр читает кеш с диска; содержимое файла не пересчитывается.
            val second = InterfaceScanCache(cacheFile)
            assertEquals(setOf("SteamUtils011"), second.interfacesOf(dll))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rescansWhenFileChanges() {
        val dir = File.createTempFile("bridge-cache-change", "").apply { delete(); mkdirs() }
        try {
            val dll = writeDll(dir, "steam_api64.dll", "SteamUtils011")
            val cache = InterfaceScanCache(File(dir, "cache.json"))
            assertEquals(setOf("SteamUtils011"), cache.interfacesOf(dll))

            writeDll(dir, "steam_api64.dll", "SteamUser023")
            dll.setLastModified(dll.lastModified() + 5_000)
            assertEquals(setOf("SteamUser023"), cache.interfacesOf(dll))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingFileGivesEmptySet() {
        val dir = File.createTempFile("bridge-cache-missing", "").apply { delete(); mkdirs() }
        try {
            val cache = InterfaceScanCache(File(dir, "cache.json"))
            assertTrue(cache.interfacesOf(File(dir, "absent.dll")).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}

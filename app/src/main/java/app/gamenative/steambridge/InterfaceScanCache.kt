package app.gamenative.steambridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Кеш результатов скана интерфейсов в небольшом JSON.
 *
 * Скан steam_api*.dll и lsteamclient.dll идёт на каждом запуске, а файлы по
 * 20-50 МБ; кеш по пути + размеру + mtime убирает повторное чтение.
 */
class InterfaceScanCache(private val cacheFile: File) {
    private data class Entry(val size: Long, val modified: Long, val interfaces: Set<String>)

    private val entries = HashMap<String, Entry>()
    private var loaded = false
    private var dirty = false

    @Synchronized
    fun interfacesOf(dll: File): Set<String> {
        if (!dll.isFile) return emptySet()
        loadIfNeeded()

        val key = dll.absolutePath
        val size = dll.length()
        val modified = dll.lastModified()
        val cached = entries[key]
        if (cached != null && cached.size == size && cached.modified == modified) {
            return cached.interfaces
        }

        val scanned = SteamInterfaceScanner.scan(dll)
        entries[key] = Entry(size, modified, scanned)
        dirty = true
        save()
        return scanned
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        val root = JSONObject()
        root.put("version", VERSION)
        val items = JSONObject()
        for ((key, entry) in entries) {
            val item = JSONObject()
            item.put("size", entry.size)
            item.put("mtime", entry.modified)
            val array = JSONArray()
            for (token in entry.interfaces.sorted()) array.put(token)
            item.put("interfaces", array)
            items.put(key, item)
        }
        root.put("entries", items)

        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
            dirty = false
        } catch (e: IOException) {
            // Кеш — оптимизация: его потеря не должна мешать запуску.
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!cacheFile.isFile) return
        try {
            val root = JSONObject(cacheFile.readText())
            if (root.optInt("version", 0) != VERSION) return
            val items = root.optJSONObject("entries") ?: return
            for (key in items.keys()) {
                val item = items.optJSONObject(key) ?: continue
                val array = item.optJSONArray("interfaces") ?: continue
                val interfaces = HashSet<String>()
                for (i in 0 until array.length()) {
                    val token = array.optString(i, "")
                    if (token.isNotEmpty()) interfaces.add(token)
                }
                entries[key] = Entry(item.optLong("size", -1), item.optLong("mtime", -1), interfaces)
            }
        } catch (e: Exception) {
            // Битый JSON — просто начинаем кеш заново.
            entries.clear()
        }
    }

    private companion object {
        const val VERSION = 1
    }
}

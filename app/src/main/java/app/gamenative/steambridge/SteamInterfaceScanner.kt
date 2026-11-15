package app.gamenative.steambridge

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Достаёт из PE-библиотеки имена интерфейсов Steam, которые она упоминает.
 *
 * Имена лежат в файле как обычные ASCII-строки: для steam_api*.dll это запросы
 * игры, для lsteamclient.dll — то, что мост умеет отдавать. Поэтому скан
 * позволяет сравнить запросы игры с возможностями моста без запуска Wine.
 */
object SteamInterfaceScanner {
    // Читаем кусками, чтобы не держать в памяти 50-мегабайтную steamclient.dll.
    private const val CHUNK_SIZE = 64 * 1024

    // Максимальная осмысленная длина имени: семейство + "_INTERFACE_VERSION" + 3 цифры.
    private const val MAX_TOKEN_LENGTH = 96

    private const val PRINTABLE_MIN = 0x20
    private const val PRINTABLE_MAX = 0x7E

    // SteamClient* покрывается первой ветвью, отдельная альтернатива не нужна.
    private val TOKEN = Regex("^(?:Steam[A-Za-z]+|STEAM[A-Z_]+_INTERFACE_VERSION)\\d{3}$")

    private const val STEAM_API_64 = "steam_api64.dll"
    private const val STEAM_API_32 = "steam_api.dll"

    fun scan(bytes: ByteArray): Set<String> = scan(bytes.inputStream())

    fun scan(file: File): Set<String> =
        try {
            file.inputStream().buffered(CHUNK_SIZE).use { scan(it) }
        } catch (e: IOException) {
            // Битый или нечитаемый файл не должен ломать запуск игры.
            emptySet()
        }

    fun scanAll(files: Iterable<File>): Set<String> {
        val result = LinkedHashSet<String>()
        for (file in files) result.addAll(scan(file))
        return result
    }

    /** steam_api64.dll и steam_api.dll в каталоге игры; обход ограничен по глубине и количеству. */
    fun findSteamApiDlls(root: File, maxDepth: Int = 8, limit: Int = 16): List<File> {
        if (!root.isDirectory) return emptyList()
        val found = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && found.size < limit) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    if (depth < maxDepth) queue.addLast(child to depth + 1)
                } else if (
                    child.name.equals(STEAM_API_64, ignoreCase = true) ||
                    child.name.equals(STEAM_API_32, ignoreCase = true)
                ) {
                    found.add(child)
                    if (found.size >= limit) break
                }
            }
        }
        return found
    }

    /**
     * Потоковый автомат: накапливаем подряд идущие печатные ASCII-байты,
     * на любом непредставном байте (NUL и прочее) закрываем токен и проверяем.
     * Так границы строк проверяются без загрузки файла целиком.
     */
    private fun scan(input: InputStream): Set<String> {
        val found = LinkedHashSet<String>()
        val token = ByteArray(MAX_TOKEN_LENGTH)
        var length = 0
        var overflow = false

        fun finishToken() {
            if (!overflow && length > 0) {
                val candidate = String(token, 0, length, Charsets.US_ASCII)
                if (TOKEN.matches(candidate)) found.add(candidate)
            }
            length = 0
            overflow = false
        }

        val chunk = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            for (i in 0 until read) {
                val value = chunk[i].toInt() and 0xFF
                if (value in PRINTABLE_MIN..PRINTABLE_MAX) {
                    if (length < MAX_TOKEN_LENGTH) {
                        token[length] = chunk[i]
                        length++
                    } else {
                        // Слишком длинная строка не может быть именем интерфейса.
                        overflow = true
                    }
                } else {
                    finishToken()
                }
            }
        }
        finishToken()
        return found
    }
}

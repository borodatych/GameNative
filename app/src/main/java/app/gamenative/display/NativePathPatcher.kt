package app.gamenative.display

import java.io.ByteArrayOutputStream

/**
 * Правка C-строк внутри готовых бинарников payload'а.
 *
 * Gamescope собран под upstream-пакет `app.gamenative`, и абсолютные пути
 * (`/data/user/0/app.gamenative/...`, `/data/data/app.gamenative/...`) зашиты прямо в ELF-файлы.
 * У форка другой applicationId, поэтому пути надо переписать в самих файлах: иначе Xwayland
 * не находит xkb-данные и падает на старте с "Failed to compile keymap".
 */
object NativePathPatcher {

    /**
     * Заменяет [old] на [new] во всех C-строках (до ближайшего 0) массива [bytes] на месте.
     *
     * Размер массива не меняется: файлы уже разложены, и их размер/смещения трогать нельзя.
     * Хвост C-строки после укорочения забивается нулями, чтобы строка кончалась в новом месте.
     * Возвращает число заменённых вхождений; 0 означает, что искать было нечего.
     */
    fun patchCStrings(bytes: ByteArray, old: String, new: String): Int {
        // Более длинная замена не влезет в отведённые байты без сдвига остального файла,
        // поэтому это ошибка вызывающего, а не тихий no-op.
        require(new.length <= old.length) {
            "replacement \"$new\" is longer than \"$old\" (${new.length} > ${old.length})"
        }
        // Сборка под upstream-пакет — штатный случай: заменять нечего и файлы трогать незачем.
        if (old == new) return 0

        val oldBytes = old.encodeToByteArray()
        val newBytes = new.encodeToByteArray()
        if (oldBytes.isEmpty() || bytes.size < oldBytes.size) return 0

        var replaced = 0
        var searchFrom = 0
        while (searchFrom < bytes.size) {
            val hit = indexOf(bytes, oldBytes, searchFrom)
            if (hit < 0) break

            // Границы C-строки: от вхождения до нулевого терминатора (или до конца массива).
            var end = hit
            while (end < bytes.size && bytes[end] != 0.toByte()) end++

            val current = bytes.copyOfRange(hit, end)
            replaced += countOccurrences(current, oldBytes)
            val patched = replaceAll(current, oldBytes, newBytes)
            // Копируем укороченную строку и добиваем освободившийся хвост нулями,
            // чтобы байты после нового терминатора не читались как часть пути.
            patched.copyInto(bytes, hit)
            bytes.fill(0, hit + patched.size, end)

            // Внутри уже обработанной строки искать нечего: идём дальше терминатора.
            searchFrom = end + 1
        }
        return replaced
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from
        while (i <= haystack.size - needle.size) {
            if (matchesAt(haystack, needle, i)) return i
            i++
        }
        return -1
    }

    private fun matchesAt(haystack: ByteArray, needle: ByteArray, at: Int): Boolean {
        if (at < 0 || at + needle.size > haystack.size) return false
        for (j in needle.indices) {
            if (haystack[at + j] != needle[j]) return false
        }
        return true
    }

    private fun countOccurrences(source: ByteArray, needle: ByteArray): Int {
        var count = 0
        var i = 0
        while (i <= source.size - needle.size) {
            if (matchesAt(source, needle, i)) {
                count++
                i += needle.size
            } else {
                i++
            }
        }
        return count
    }

    private fun replaceAll(source: ByteArray, old: ByteArray, new: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(source.size)
        var i = 0
        while (i < source.size) {
            if (matchesAt(source, old, i)) {
                out.write(new, 0, new.size)
                i += old.size
            } else {
                out.write(source[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }
}

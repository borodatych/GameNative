package app.gamenative.utils

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Чистые правила "где лежат игры": без Android API, чтобы их можно было
 * покрыть обычными JVM-тестами.
 *
 * Один корень (games root) задаёт место для всех магазинов:
 * Steam -> <root>/Steam/steamapps/common, GOG -> <root>/GOG/games/common и т.д.
 */
object GamesRootPaths {

    /** Имя папки по умолчанию внутри основного внешнего хранилища. */
    const val DEFAULT_ROOT_DIR_NAME = "Games"

    /** Подпапки магазинов внутри выбранного корня. */
    const val STEAM_DIR = "Steam"
    const val GOG_DIR = "GOG"
    const val EPIC_DIR = "Epic"
    const val AMAZON_DIR = "Amazon"

    /** Папки магазинов, которые переносятся вместе с корнем. */
    val STORE_DIRS: List<String> = listOf(STEAM_DIR, GOG_DIR, EPIC_DIR, AMAZON_DIR)

    /** Корень по умолчанию: <primary external storage>/Games. */
    fun defaultRoot(primaryExternalStorage: String): String =
        File(primaryExternalStorage, DEFAULT_ROOT_DIR_NAME).absolutePath

    /** Реально используемый корень: выбранный пользователем либо значение по умолчанию. */
    fun effectiveRoot(configuredRoot: String, defaultRoot: String): String =
        configuredRoot.trim().ifBlank { defaultRoot }

    /** Каталог новых установок Steam. */
    fun steamCommon(root: String): String = steamSubDir(root, "common")

    /** Каталог промежуточных загрузок Steam. */
    fun steamStaging(root: String): String = steamSubDir(root, "staging")

    private fun steamSubDir(root: String, leaf: String): String =
        File(File(File(root, STEAM_DIR), "steamapps"), leaf).absolutePath

    /** Каталог новых установок GOG (та же раскладка, что была у внешнего пути). */
    fun gogCommon(root: String): String =
        File(File(File(root, GOG_DIR), "games"), "common").absolutePath

    /** Каталог новых установок Epic. */
    fun epicGames(root: String): String =
        File(File(root, EPIC_DIR), "games").absolutePath

    /** Каталог новых установок Amazon. */
    fun amazonGames(root: String): String =
        File(File(root, AMAZON_DIR), "games").absolutePath

    private const val TREE_URI_PREFIX = "content://com.android.externalstorage.documents/tree/"

    // Внешний том в SAF выглядит как UUID вида 1234-ABCD; primary — отдельное слово.
    private val VOLUME_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

    /**
     * Превращает tree-uri из ACTION_OPEN_DOCUMENT_TREE в обычный абсолютный путь.
     *
     * "primary:Games" -> <primary>/Games
     * "1234-ABCD:Games/Saves" -> /storage/1234-ABCD/Games/Saves
     *
     * Возвращает null для чужих провайдеров (облако, Downloads и т.п.)
     * и для путей с "..", чтобы нельзя было выйти за пределы тома.
     */
    fun treeUriToAbsolutePath(uri: String?, primaryExternalStorage: String): String? {
        if (uri.isNullOrBlank() || !uri.startsWith(TREE_URI_PREFIX)) return null
        // В uri после /tree/ бывает хвост /document/<тот же id> — он нам не нужен.
        val encodedDocId = uri
            .removePrefix(TREE_URI_PREFIX)
            .substringBefore("/document/")
            .substringBefore('?')
            .substringBefore('#')
        val docId = percentDecode(encodedDocId)
        if (docId.isBlank()) return null

        val volume = docId.substringBefore(':', missingDelimiterValue = docId)
        val relative = if (docId.contains(':')) docId.substringAfter(':') else ""

        val volumeRoot = when {
            volume == "primary" -> primaryExternalStorage
            // "home" — не физический путь, который нам нужен.
            volume == "home" -> return null
            VOLUME_ID.matches(volume) -> "/storage/$volume"
            else -> return null
        }

        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return segments.fold(volumeRoot) { acc, segment -> File(acc, segment).path }
    }

    // Декодируем только %XX: '+' в SAF — обычный символ, а не пробел.
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    index += 3
                    continue
                }
            }
            out.write(char.code and 0xFF)
            index++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}

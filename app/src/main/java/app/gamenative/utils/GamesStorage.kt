package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import timber.log.Timber

/**
 * Android-часть выбора игрового корня: проверка "All files access",
 * создание папки и переход в системные настройки.
 *
 * Сами правила путей живут в [GamesRootPaths] и тестируются на JVM.
 */
object GamesStorage {

    val STORE_DIRS: List<String> = GamesRootPaths.STORE_DIRS

    /** Корень по умолчанию: <основное внешнее хранилище>/Games. */
    fun defaultGamesRoot(): String =
        GamesRootPaths.defaultRoot(Environment.getExternalStorageDirectory().absolutePath)

    /**
     * Запись в папку верхнего уровня на основном хранилище с Android 11
     * требует MANAGE_EXTERNAL_STORAGE; до 11 хватает WRITE_EXTERNAL_STORAGE.
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Создаёт корень (если нужно) и проверяет, что в него реально можно писать. */
    fun ensureGamesRoot(path: String): Boolean {
        if (path.isBlank()) return false
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Timber.w("Cannot create games root: $path")
            return false
        }
        return dir.canWrite()
    }

    /** tree-uri из SAF -> абсолютный путь; null, если провайдер не локальный. */
    fun treeUriToAbsolutePath(uri: Uri?): String? =
        GamesRootPaths.treeUriToAbsolutePath(uri?.toString(), Environment.getExternalStorageDirectory().absolutePath)

    /** Открывает экран "Все файлы" для нашего пакета, с запасным общим экраном. */
    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val opened = runCatching { context.startActivity(appIntent) }.isSuccess
        if (!opened) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { Timber.w(it, "Cannot open all-files-access settings") }
        }
    }

    /** Есть ли в корне хоть одна папка магазина с играми. */
    fun hasGamesInRoot(root: String): Boolean =
        root.isNotBlank() && STORE_DIRS.any { File(root, it).isDirectory }
}

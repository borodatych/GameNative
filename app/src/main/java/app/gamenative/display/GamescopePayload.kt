package app.gamenative.display

import android.content.Context
import app.gamenative.service.SteamService
import app.gamenative.utils.AssetUtils
import com.winlator.core.TarCompressorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Versioned gamescope payload (gamescope, XWayland, scaling clients), fetched on the first gamescope launch. */
object GamescopePayload {
    const val VERSION = "20261105.4"
    const val ARCHIVE = "gamescope-gamenative-$VERSION.tzst"
    private const val STAMP = ".payload.version"

    // Upstream-пакет, под который собран payload: его абсолютные пути зашиты в ELF.
    private const val UPSTREAM_PKG = "app.gamenative"
    private const val UPSTREAM_USER_PREFIX = "/data/user/0/$UPSTREAM_PKG/"
    private const val UPSTREAM_DATA_PREFIX = "/data/data/$UPSTREAM_PKG/"

    fun directory(filesDir: File): File = GamescopeSettings.gamescopeDir(filesDir)

    /**
     * Метка готовности payload'а.
     *
     * Пакет входит в метку, потому что путь к данным зашит в бинарники: установка под другим
     * applicationId (и любая установка до появления правки) не готова, и `isReady` обязан
     * вернуть false, чтобы payload переустановился и пути в файлах переписались.
     */
    internal fun stamp(packageName: String): String = "$VERSION+pkg:$packageName"

    fun isReady(filesDir: File, packageName: String): Boolean {
        val dir = directory(filesDir)
        return File(dir, "bin").isDirectory &&
            runCatching { File(dir, STAMP).readText().trim() }.getOrNull() == stamp(packageName)
    }

    suspend fun ensure(context: Context, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        if (isReady(filesDir, context.packageName)) return@withContext directory(filesDir)
        val archive = File(filesDir, ARCHIVE)
        if (!archive.isFile || archive.length() == 0L) {
            archive.delete()
            // A payload shipped inside the APK wins over the download: it is the
            // build that was exercised with this app, and it needs no network.
            // Only when the asset is absent does the published archive get
            // fetched.
            if (copyFromAssets(context, archive)) {
                onProgress(1.0f)
            } else {
                try {
                    SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
                } catch (e: Exception) {
                    archive.delete()
                    throw e
                }
            }
        }
        install(archive, filesDir, context.packageName)
    }

    /** Whether this APK carries the payload, and if so, copy it out to [dest]. */
    private fun copyFromAssets(context: Context, dest: File): Boolean {
        val assets = context.assets
        val present = runCatching { assets.open(ARCHIVE).close() }.isSuccess
        if (!present) return false
        return runCatching {
            assets.open(ARCHIVE).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
            .onFailure {
                Timber.w(it, "Failed to copy $ARCHIVE from assets")
                dest.delete()
            }
            .isSuccess
    }

    @Synchronized
    internal fun install(archive: File, filesDir: File, packageName: String): File {
        val target = directory(filesDir)
        if (isReady(filesDir, packageName)) return target
        val tempDir = File(target.parentFile, "${target.name}.tmp")
        AssetUtils.deleteTree(tempDir)
        tempDir.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, tempDir)) {
            AssetUtils.deleteTree(tempDir)
            archive.delete()
            error("Failed to extract $ARCHIVE")
        }
        // Пути в бинарниках указывают на upstream-пакет, поэтому до записи метки
        // их надо переписать под наш applicationId.
        patchNativePaths(tempDir, packageName)
        File(tempDir, STAMP).writeText(stamp(packageName))
        AssetUtils.deleteTree(target)
        if (!tempDir.renameTo(target)) {
            AssetUtils.deleteTree(tempDir)
            error("Failed to promote $ARCHIVE")
        }
        archive.delete()
        Timber.i("Gamescope payload $VERSION ready at ${target.absolutePath}")
        return target
    }

    /**
     * Переписывает зашитые пути `/data/.../app.gamenative/` на текущий applicationId.
     *
     * Xwayland ищет xkb-данные и бинарники по абсолютному пути, поэтому правка нужна
     * во всех исполняемых файлах и библиотеках, а не только в gamescope.
     */
    private fun patchNativePaths(dir: File, packageName: String) {
        // Обходим только то, что реально запускается: bin/ и разделяемые библиотеки.
        val binaries = buildList {
            File(dir, "bin").listFiles()?.filterTo(this) { it.isFile }
            File(dir, "lib").listFiles()?.filterTo(this) { it.isFile && it.name.endsWith(".so") }
        }
        var replaced = 0
        binaries.forEach { file ->
            val bytes = runCatching { file.readBytes() }.getOrElse {
                Timber.w(it, "Cannot read ${file.name}; its embedded paths stay untouched")
                return@forEach
            }
            val count = NativePathPatcher.patchCStrings(
                bytes,
                UPSTREAM_USER_PREFIX,
                "/data/user/0/$packageName/",
            ) + NativePathPatcher.patchCStrings(
                bytes,
                UPSTREAM_DATA_PREFIX,
                "/data/data/$packageName/",
            )
            if (count == 0) return@forEach
            // Пишем поверх того же файла: его права и executable-бит при этом сохраняются.
            val executable = file.canExecute()
            file.writeBytes(bytes)
            // На случай, если ФС не сохранила бит при перезаписи, возвращаем его явно.
            if (executable && !file.canExecute()) file.setExecutable(true, false)
            replaced += count
        }
        Timber.i("Patched $replaced upstream path(s) in gamescope payload for $packageName")
    }
}

package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import timber.log.Timber

object UbisoftConnectStep : PreInstallStep {
    private const val TAG = "UbisoftConnectStep"
    private val INSTALLER_NAMES = listOf("UbisoftConnectInstaller.exe", "UplayInstaller.exe")
    private const val COMMON_REDIST_SUBDIR = "_CommonRedist/UbisoftConnect"

    override val marker: Marker = Marker.UBISOFT_CONNECT_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
         if (MarkerUtils.hasMarker(gameDirPath, Marker.UBISOFT_CONNECT_INSTALLED)) return false

        return true
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val commonRedistDir = File(gameDir, COMMON_REDIST_SUBDIR)
        val installerName =
            INSTALLER_NAMES.firstOrNull { installer ->
                ensureInstallerAtCommonRedist(
                    rootInstaller = File(gameDir, installer),
                    commonRedistDir = commonRedistDir,
                    commonRedistInstaller = File(commonRedistDir, installer),
                )
            }

        if (installerName == null) {
            Timber.tag(TAG).i(
                "Ubisoft installer not present at expected _CommonRedist path for game at %s",
                gameDirPath,
            )
            return null
        }

        val wineInstallerPath = "A:\\_CommonRedist\\UbisoftConnect\\$installerName"
        val command = "$wineInstallerPath /S"
        Timber.tag(TAG).i("Using Ubisoft installer (silent): %s", command)

        return command
    }

    /**
     * IMPORTANT: Ubisoft Connect installer cannot be run from the game root directory.
     * Ensures the Ubisoft Connect installer is present at the expected _CommonRedist path.
     * If it's only present in the game root, links it there, or copies it where links are unsupported.
     */
    private fun ensureInstallerAtCommonRedist(
        rootInstaller: File,
        commonRedistDir: File,
        commonRedistInstaller: File,
    ): Boolean {
        if (commonRedistInstaller.isFile) return true
        if (!rootInstaller.isFile) return false

        commonRedistDir.mkdirs()
        try {
            Files.createSymbolicLink(commonRedistInstaller.toPath(), rootInstaller.toPath())
            if (commonRedistInstaller.exists()) return true
        } catch (t: Throwable) {
            // Общая память Android (/storage/emulated) символьных ссылок не умеет — там установщик копируется
            Timber.tag(TAG).i("Symlink unsupported here (%s), copying Ubisoft installer", t.message)
        }
        // Копия идёт во временный файл и переименовывается целой: прерванная копия не сойдёт за готовый установщик
        val partial = File(commonRedistDir, commonRedistInstaller.name + ".part")
        return try {
            Files.copy(rootInstaller.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial.toPath(), commonRedistInstaller.toPath(), StandardCopyOption.REPLACE_EXISTING)
            commonRedistInstaller.isFile
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed copying Ubisoft installer")
            partial.delete()
            false
        }
    }
}

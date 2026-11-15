package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.steambridge.BridgeArch
import app.gamenative.steambridge.BridgeArchiveCatalog
import app.gamenative.steambridge.BridgeDescriptor
import app.gamenative.steambridge.BridgeSelector
import app.gamenative.steambridge.BridgeSource
import app.gamenative.steambridge.InterfaceScanCache
import app.gamenative.steambridge.SteamInterfaceScanner
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Pre-downloads and extracts assets required for the experimental bionic-Steam
 * launch path:
 *   - steam.exe (cached in filesDir; the Wine-side copy is refreshed each boot
 *     by extractSteamFiles in XServerScreen.kt)
 *   - мост lsteamclient: если дерево Proton поставляет свой, он не трогается;
 *     иначе архив GameNative распаковывается в files/steambridges/<имя>/ и
 *     копируется в <winepath>/lib/wine/, где Wine найдёт unix-.so рядом с PE.
 *     PE-часть затем копируется в system32 / syswow64 префикса.
 *   - bionic Android libsteamclient.so (steam-androidarm64.tzst), extracted
 *     relative to the imagefs root so it lands at imagefs/usr/lib/.
 */
object BionicSteamAssetsDependency : LaunchDependency {
    private const val STEAM_EXE = "steam.exe"
    private const val STEAM_EXE_PROTON11 = "steam-proton11.exe"
    private const val BIONIC_STEAM_VERSION = "20260709"
    private const val BIONIC_STEAM_ARCHIVE = "steam-androidarm64-$BIONIC_STEAM_VERSION.tzst"
    private const val BIONIC_STEAM_MARKER = ".bionic_steam_version"
    private const val STEAMCLIENT_DLLS_ARCHIVE = "steamclient-dlls-20260619.tzst"
    private const val LSTEAMCLIENT_DLL = "lsteamclient.dll"
    private const val LIBSTEAMCLIENT_SO = "libsteamclient.so"
    private const val CACERT_PEM = "cacert.pem"
    private const val BRIDGE_CACHE_DIR = "steambridges"
    private const val BRIDGE_CACHE_FILE = "cache.json"
    private const val STEAMBRIDGE_MARKER = ".steambridge"
    private const val EXTRA_STEAM_BRIDGE = "steamBridge"

    /**
     * Wine versions that ship a dedicated steam.exe helper. steam.exe is
     * otherwise version-independent, so all other versions use the default.
     */
    private val STEAM_EXE_BY_WINE = mapOf(
        "proton-11.0-1-x86_64-1" to STEAM_EXE_PROTON11,
        "proton-11.0-1-arm64ec-1" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-1" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-1" to STEAM_EXE_PROTON11,
        "proton-11.0-2.3-arm64ec-1" to STEAM_EXE_PROTON11,
        "proton-11.0-2.4-arm64ec-1" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-6" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-6" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-7" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-7" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-8" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-8" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-9" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-9" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-10" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-10" to STEAM_EXE_PROTON11,
        "proton-11.0-2-x86_64-11" to STEAM_EXE_PROTON11,
        "proton-11.0-2-arm64ec-11" to STEAM_EXE_PROTON11,
    )

    /** Filename of the steam.exe helper (server asset + filesDir cache) for this container. */
    fun steamExeAssetFor(container: Container): String =
        STEAM_EXE_BY_WINE[container.wineVersion] ?: STEAM_EXE

    /** All steam.exe cache names we may have installed, for bionic-vs-real detection. */
    fun bionicSteamExeNames(): List<String> = listOf(STEAM_EXE, STEAM_EXE_PROTON11)

    /** Мажор Proton: unixlib ABI привязан именно к нему, а не к точной сборке. */
    private fun protonMajorOf(container: Container): Int? =
        Regex("^proton-(\\d+)").find(container.wineVersion)?.groupValues?.get(1)?.toIntOrNull()

    private fun bridgeArchOf(container: Container): BridgeArch =
        BridgeArch.fromWineVersion(container.wineVersion)

    /**
     * Архив, которым можно закрыть отсутствие моста в дереве Proton.
     * null — для этого мажора архива нет, а подставлять соседний нельзя.
     */
    private fun archiveBridgeFor(container: Container): BridgeArchiveCatalog.ArchiveBridge? =
        protonMajorOf(container)?.let { BridgeArchiveCatalog.archiveFor(it, bridgeArchOf(container)) }

    private fun system32SrcArchDir(container: Container): String =
        if (container.wineVersion.contains("arm64ec")) "aarch64-windows" else "x86_64-windows"

    /** `lib/wine/` активного дерева Proton: сюда кладётся PE и unix-часть моста. */
    private fun wineLibDir(context: Context, container: Container): File =
        File(wineInstallDir(context, container), "lib/wine")

    private fun treeSystem32DllIn(wineLibDir: File, container: Container): File =
        File(wineLibDir, "${system32SrcArchDir(container)}/$LSTEAMCLIENT_DLL")

    private fun treeSyswow64DllIn(wineLibDir: File): File =
        File(wineLibDir, "i386-windows/$LSTEAMCLIENT_DLL")

    private fun steambridgeMarker(wineLibDir: File): File = File(wineLibDir, STEAMBRIDGE_MARKER)

    /**
     * Дерево само поставляет мост, если lsteamclient.dll лежит в нём и не помечен
     * как распакованный нами. Проверка по файлу, а не по списку id: так любой
     * Proton (включая импортированный) сохраняет свою более новую сборку.
     */
    private fun treeShipsOwnBridge(wineLibDir: File, container: Container): Boolean =
        treeSystem32DllIn(wineLibDir, container).exists() && !steambridgeMarker(wineLibDir).exists()

    private fun bridgeCacheDir(context: Context): File =
        File(ImageFs.find(context).filesDir, BRIDGE_CACHE_DIR)

    /** Каталог, куда распакован архив: мост живёт отдельно от дерева Proton. */
    private fun bridgeStashDir(context: Context, archiveName: String): File =
        File(bridgeCacheDir(context), archiveName.removeSuffix(".tzst"))

    private fun bridgeStashDll(context: Context, container: Container, archiveName: String): File =
        File(bridgeStashDir(context, archiveName), "${bridgeArchOf(container).windowsDir}/$LSTEAMCLIENT_DLL")

    private fun interfaceCache(context: Context): InterfaceScanCache =
        InterfaceScanCache(File(bridgeCacheDir(context), BRIDGE_CACHE_FILE))

    /**
     * Распаковывает архив в собственный кеш один раз.
     * Распаковка идёт именно туда, а не в чужое дерево: так архив не может
     * затереть более новый встроенный мост.
     */
    private fun ensureBridgeStashed(context: Context, container: Container, archiveName: String): File? {
        val stashDir = bridgeStashDir(context, archiveName)
        val stashDll = bridgeStashDll(context, container, archiveName)
        if (stashDll.exists()) return stashDir

        val archiveCache = File(ImageFs.find(context).filesDir, archiveName)
        if (!archiveCache.exists()) {
            Timber.e("lsteamclient archive $archiveName not downloaded; extract skipped")
            return null
        }
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archiveCache, stashDir)) {
            Timber.e("Failed to extract $archiveName into ${stashDir.absolutePath}")
            return null
        }
        if (!stashDll.exists()) {
            Timber.e("$archiveName has no ${stashDll.name} for ${container.wineVersion}")
            return null
        }
        return stashDir
    }

    /**
     * Копирует unix-часть и PE-часть моста рядом с Wine.
     * Wine грузит unixlib по пути <arch>-unix/lsteamclient.so рядом с PE,
     * поэтому убрать архивный мост из дерева целиком нельзя.
     * Маркер фиксирует, что мост в дереве наш, а не из .wcp.
     */
    private fun installArchiveBridgeIntoTree(
        context: Context,
        container: Container,
        wineLibDir: File,
        archive: BridgeArchiveCatalog.ArchiveBridge,
    ): Boolean {
        val stashDir = ensureBridgeStashed(context, container, archive.archiveName) ?: return false
        wineLibDir.mkdirs()
        if (!FileUtils.copy(stashDir, wineLibDir)) {
            Timber.e("Failed to copy ${archive.archiveName} bridge into ${wineLibDir.absolutePath}")
            return false
        }
        steambridgeMarker(wineLibDir).writeText(
            "archive=${archive.archiveName}\nmajor=${archive.protonMajor}\narch=${archive.arch.wineName}\n",
        )
        return true
    }

    /** Встроенный мост дерева либо уже распакованный архивный — что сейчас реально лежит в lib/wine. */
    private fun bridgeDescriptorFor(
        context: Context,
        container: Container,
        wineLibDir: File,
    ): BridgeDescriptor? {
        val major = protonMajorOf(container) ?: return null
        val arch = bridgeArchOf(container)
        val treeDll = treeSystem32DllIn(wineLibDir, container)
        if (!treeDll.exists()) return null

        val archive = archiveBridgeFor(container)
        val managedArchive = if (steambridgeMarker(wineLibDir).exists()) archive else null
        val source = if (managedArchive != null) BridgeSource.ARCHIVE else BridgeSource.BUNDLED_IN_PROTON
        val id = managedArchive?.id ?: "bundled:proton$major:${arch.wineName}"
        val label = managedArchive?.label ?: "Proton ${container.wineVersion} built-in bridge"
        return BridgeDescriptor(
            id = id,
            protonMajor = major,
            arch = arch,
            source = source,
            location = treeDll.absolutePath,
            interfaces = interfaceCache(context).interfacesOf(treeDll),
            label = label,
        )
    }

    /** Интерфейсы, которые запрашивает сама игра (steam_api*.dll в её каталоге). */
    private fun requiredInterfacesOf(context: Context, gameDir: File?): Set<String> {
        if (gameDir == null || !gameDir.isDirectory) return emptySet()
        val dlls = SteamInterfaceScanner.findSteamApiDlls(gameDir)
        if (dlls.isEmpty()) return emptySet()
        val cache = interfaceCache(context)
        val result = HashSet<String>()
        for (dll in dlls) result.addAll(cache.interfacesOf(dll))
        return result
    }

    /** Единственное место, где решается, какой мост получит игра; UI позже будет менять только override. */
    private fun logBridgeDecision(context: Context, container: Container, gameDir: File?) {
        val libDir = wineLibDir(context, container)
        val arch = bridgeArchOf(container)
        val major = protonMajorOf(container) ?: run {
            Timber.w("Steam bridge: ${container.wineVersion} has no Proton major, selection skipped")
            return
        }
        val candidates = listOfNotNull(bridgeDescriptorFor(context, container, libDir))
        val required = requiredInterfacesOf(context, gameDir)
        val selection = BridgeSelector.select(
            protonMajor = major,
            arch = arch,
            requiredInterfaces = required,
            candidates = candidates,
            overrideId = container.getExtra(EXTRA_STEAM_BRIDGE, ""),
        )
        if (selection.overrideIgnoredReason != null) {
            Timber.w("Steam bridge: override ignored: ${selection.overrideIgnoredReason}")
        }
        val chosen = selection.chosen
        if (chosen == null) {
            Timber.w("Steam bridge: none chosen for Proton $major ${arch.wineName} (${selection.reason})")
            return
        }
        Timber.i(
            "Steam bridge: chosen ${chosen.id} [${chosen.source}] label=${chosen.label} " +
                "required=${required.size} supported=${chosen.interfaces.size} reason=${selection.reason}",
        )
        if (selection.missingInterfaces.isNotEmpty()) {
            Timber.w("Steam bridge: missing interfaces ${selection.missingInterfaces.sorted()}")
        }
    }

    private fun copyBridgeDllsIntoPrefix(container: Container, wineLibDir: File) {
        val sys32Src = treeSystem32DllIn(wineLibDir, container)
        val sysWowSrc = treeSyswow64DllIn(wineLibDir)
        val dstSystem32 = system32Dll(container)
        val dstSyswow64 = syswow64Dll(container)
        dstSystem32.parentFile?.mkdirs()
        dstSyswow64.parentFile?.mkdirs()
        if (!FileUtils.copy(sys32Src, dstSystem32)) {
            Timber.e("Failed to copy ${sys32Src.absolutePath} to ${dstSystem32.absolutePath}")
        }
        if (!FileUtils.copy(sysWowSrc, dstSyswow64)) {
            Timber.e("Failed to copy ${sysWowSrc.absolutePath} to ${dstSyswow64.absolutePath}")
        }
    }

    /**
     * Resolves the actual Wine/Proton install directory for the container.
     * imageFs.winePath is not initialized yet at dependency-install time
     * (it's set later in XServerScreen via setWinePath), so we resolve it
     * the same way XServerScreen does — through WineInfo.fromIdentifier.
     */
    private fun wineInstallDir(context: Context, container: Container): File {
        val contentsManager = ContentsManager(context).also { it.syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, container.wineVersion)
        val path = wineInfo.path
        return if (!path.isNullOrEmpty()) {
            File(path)
        } else {
            File(ImageFs.find(context).rootDir, "opt/wine")
        }
    }

    private fun system32Dll(container: Container): File =
        File(container.rootDir, ".wine/drive_c/windows/system32/" + LSTEAMCLIENT_DLL)

    private fun syswow64Dll(container: Container): File =
        File(container.rootDir, ".wine/drive_c/windows/syswow64/" + LSTEAMCLIENT_DLL)

    private fun libsteamclientSo(imageFs: ImageFs): File =
        File(imageFs.libDir, LIBSTEAMCLIENT_SO)

    private fun bionicSteamMarker(imageFs: ImageFs): File =
        File(imageFs.filesDir, BIONIC_STEAM_MARKER)

    private fun bionicSteamInstalled(imageFs: ImageFs): Boolean {
        val marker = bionicSteamMarker(imageFs)
        return marker.exists() &&
            marker.readText().trim() == BIONIC_STEAM_VERSION &&
            libsteamclientSo(imageFs).exists()
    }

    /**
     * Готовит мост к запуску: подкладывает архивный мост, только если дерево
     * Proton не поставляет свой, копирует PE-часть в префикс, затем выбирает
     * мост под интерфейсы игры и логирует решение.
     * Запускается каждый boot, чтобы смена Proton не оставила чужой ABI.
     */
    fun applyLsteamclientForLaunch(context: Context, container: Container, gameDir: File?) {
        val libDir = wineLibDir(context, container)
        if (treeShipsOwnBridge(libDir, container)) {
            // Встроенный мост не трогаем: архив того же мажора может быть старше.
            Timber.i("Steam bridge: ${container.wineVersion} ships its own lsteamclient, archive skipped")
        } else {
            val archive = archiveBridgeFor(container)
            if (archive == null) {
                Timber.e("Steam bridge: no archive for ${container.wineVersion}, bridge not installed")
                return
            }
            if (!installArchiveBridgeIntoTree(context, container, libDir, archive)) return
        }
        copyBridgeDllsIntoPrefix(container, libDir)
        logBridgeDecision(context, container, gameDir)
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        container.isLaunchBionicSteam

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val imageFs = ImageFs.find(context)
        val filesDir = imageFs.filesDir
        if (!File(filesDir, steamExeAssetFor(container)).exists()) return false
        if (!File(filesDir, CACERT_PEM).exists()) return false
        if (!File(filesDir, STEAMCLIENT_DLLS_ARCHIVE).exists()) return false
        if (!bionicSteamInstalled(imageFs)) return false
        // Архив нужен только дереву без своего моста; распаковка и копирование
        // в префикс выполняются каждый boot в applyLsteamclientForLaunch.
        val libDir = wineLibDir(context, container)
        if (!treeShipsOwnBridge(libDir, container)) {
            val archive = archiveBridgeFor(container)
            if (archive != null && !File(filesDir, archive.archiveName).exists()) return false
        }
        return true
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Preparing real Steam assets"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        val imageFs = withContext(Dispatchers.IO) { ImageFs.find(context) }
        val filesDir = imageFs.filesDir

        // 1. steam.exe — cache only; XServerScreen.extractSteamFiles copies into the prefix each boot.
        val steamExeAsset = steamExeAssetFor(container)
        val steamExeCache = File(filesDir, steamExeAsset)
        if (!withContext(Dispatchers.IO) { steamExeCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $steamExeAsset")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = steamExeAsset,
                ).await()
            }
        }

        val steamclientDllsCache = File(filesDir, STEAMCLIENT_DLLS_ARCHIVE)
        if (!withContext(Dispatchers.IO) { steamclientDllsCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $STEAMCLIENT_DLLS_ARCHIVE")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = STEAMCLIENT_DLLS_ARCHIVE,
                ).await()
            }
        }

        val cacertCache = File(filesDir, CACERT_PEM)
        if (!withContext(Dispatchers.IO) { cacertCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $CACERT_PEM")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = CACERT_PEM,
                ).await()
            }
        }

        // 2/3. Архив качаем только дереву без своего моста, иначе он затрёт
        // встроенный мост Proton при распаковке.
        val treeHasOwnBridge = treeShipsOwnBridge(wineLibDir(context, container), container)
        val lsteamclientArchive = if (treeHasOwnBridge) null else archiveBridgeFor(container)?.archiveName
        if (lsteamclientArchive != null) {
            val archiveCache = File(filesDir, lsteamclientArchive)
            if (!withContext(Dispatchers.IO) { archiveCache.exists() }) {
                callbacks.setLoadingMessage("Downloading $lsteamclientArchive")
                withContext(Dispatchers.IO) {
                    SteamService.downloadFile(
                        onDownloadProgress = { callbacks.setLoadingProgress(it) },
                        parentScope = this@coroutineScope,
                        context = context,
                        fileName = lsteamclientArchive,
                    ).await()
                }
            }
        }

        // 4. bionic Android libsteamclient.so (+ sibling libs), version-gated.
        val bionicArchiveCache = File(filesDir, BIONIC_STEAM_ARCHIVE)
        if (!withContext(Dispatchers.IO) { bionicSteamInstalled(imageFs) }) {
            suspend fun fetchBionicArchive() {
                callbacks.setLoadingMessage("Downloading $BIONIC_STEAM_ARCHIVE")
                withContext(Dispatchers.IO) {
                    SteamService.downloadFile(
                        onDownloadProgress = { callbacks.setLoadingProgress(it) },
                        parentScope = this@coroutineScope,
                        context = context,
                        fileName = BIONIC_STEAM_ARCHIVE,
                    ).await()
                }
            }
            if (!withContext(Dispatchers.IO) { bionicArchiveCache.exists() }) fetchBionicArchive()

            callbacks.setLoadingMessage("Extracting bionic Steam client")
            callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
            var extracted = withContext(Dispatchers.IO) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, bionicArchiveCache, imageFs.rootDir)
            }
            if (!extracted) {
                withContext(Dispatchers.IO) { bionicArchiveCache.delete() }
                fetchBionicArchive()
                extracted = withContext(Dispatchers.IO) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, bionicArchiveCache, imageFs.rootDir)
                }
            }
            if (!extracted) {
                throw IllegalStateException("Failed to extract $BIONIC_STEAM_ARCHIVE into ${imageFs.rootDir.absolutePath}")
            }

            withContext(Dispatchers.IO) {
                val marker = bionicSteamMarker(imageFs)
                val tmp = File(marker.parentFile, "$BIONIC_STEAM_MARKER.tmp")
                tmp.writeText(BIONIC_STEAM_VERSION)
                if (!tmp.renameTo(marker)) {
                    tmp.copyTo(marker, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}

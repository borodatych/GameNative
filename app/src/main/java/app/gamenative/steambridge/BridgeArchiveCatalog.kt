package app.gamenative.steambridge

/**
 * Реестр архивов lsteamclient от GameNative: какие сборки вообще существуют.
 *
 * Ключ — мажор Proton + архитектура, а не точный id wine-версии: unixlib
 * совместим внутри одного мажора, и привязка к id ломалась на любом
 * импортированном или пересобранном Proton.
 */
object BridgeArchiveCatalog {
    data class ArchiveBridge(
        val protonMajor: Int,
        val arch: BridgeArch,
        val archiveName: String,
    ) {
        val id: String get() = "archive:proton$protonMajor:${arch.wineName}"

        val label: String get() = "GameNative archive, Proton $protonMajor ${arch.wineName}"

        /** Имя каталога-кеша, куда распаковывается архив. */
        val stashDirName: String get() = archiveName.removeSuffix(".tzst")
    }

    private val entries = listOf(
        ArchiveBridge(9, BridgeArch.X86_64, "lsteamclient-x86_64-proton9.tzst"),
        ArchiveBridge(9, BridgeArch.ARM64EC, "lsteamclient-arm64ec-proton9.tzst"),
        ArchiveBridge(10, BridgeArch.X86_64, "lsteamclient-x86_64-proton10.tzst"),
        ArchiveBridge(10, BridgeArch.ARM64EC, "lsteamclient-arm64ec-proton10.tzst"),
        ArchiveBridge(11, BridgeArch.X86_64, "lsteamclient-x86_64-proton11.tzst"),
        ArchiveBridge(11, BridgeArch.ARM64EC, "lsteamclient-arm64ec-proton11.tzst"),
    )

    fun all(): List<ArchiveBridge> = entries

    /**
     * Архив для мажора и архитектуры.
     * Для незнакомого мажора возвращает null: подставлять архив соседнего
     * мажора нельзя, ABI unixlib у них разный.
     */
    fun archiveFor(protonMajor: Int, arch: BridgeArch): ArchiveBridge? =
        entries.firstOrNull { it.protonMajor == protonMajor && it.arch == arch }

    fun byId(id: String?): ArchiveBridge? = entries.firstOrNull { it.id == id }
}

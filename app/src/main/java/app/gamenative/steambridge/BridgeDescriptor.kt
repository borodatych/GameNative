package app.gamenative.steambridge

/** Архитектура, на которую собран мост. */
enum class BridgeArch(
    /** Как архитектура называется в идентификаторе wine-версии. */
    val wineName: String,
    /** Каталог PE-библиотек в lib/wine. */
    val windowsDir: String,
    /** Каталог unix-части (unixlib) в lib/wine. */
    val unixDir: String,
) {
    X86_64("x86_64", "x86_64-windows", "x86_64-unix"),
    ARM64EC("arm64ec", "aarch64-windows", "aarch64-unix"),
    ;

    companion object {
        fun fromWineVersion(wineVersion: String): BridgeArch =
            if (wineVersion.contains(ARM64EC.wineName)) ARM64EC else X86_64
    }
}

/** Откуда взят мост — влияет на приоритет при равном покрытии. */
enum class BridgeSource {
    /** Лежит в дереве Proton из .wcp — тот, с которым он и собран. */
    BUNDLED_IN_PROTON,

    /** Скачан архивом GameNative и распакован в кеш / дерево. */
    ARCHIVE,

    /** Принесён пользователем (импортированный Proton или своя сборка). */
    IMPORTED,
}

/**
 * Описание одного моста lsteamclient.
 *
 * ABI unixlib жёстко привязан к мажору Proton и архитектуре, поэтому оба поля
 * обязательны для совместимости при выборе.
 */
data class BridgeDescriptor(
    val id: String,
    val protonMajor: Int,
    val arch: BridgeArch,
    val source: BridgeSource,
    /** Путь к lsteamclient.dll (PE), по нему же кешируются результаты скана. */
    val location: String,
    val interfaces: Set<String> = emptySet(),
    val label: String = id,
) {
    fun isCompatibleWith(protonMajor: Int, arch: BridgeArch): Boolean =
        this.protonMajor == protonMajor && this.arch == arch
}

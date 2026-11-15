package app.gamenative.steambridge

/** Результат выбора моста: кого выбрали и чего ему не хватает под игру. */
data class BridgeSelection(
    val chosen: BridgeDescriptor?,
    /** Интерфейсы, которые просит игра, а выбранный мост их не содержит. */
    val missingInterfaces: Set<String>,
    val overrideApplied: Boolean,
    /** Почему ручной выбор проигнорирован (null — override не задавался или применён). */
    val overrideIgnoredReason: String?,
    val reason: String,
)

/**
 * Выбирает мост под конкретную игру.
 *
 * Правило "новее" — это не дата сборки, а покрытие: Valve-мост содержит все
 * версии интерфейсов до своей новейшей, поэтому больше интерфейсов и более
 * высокие версии по семействам означают более новый мост.
 */
object BridgeSelector {
    private const val AUTO_OVERRIDE = "auto"

    /**
     * @param overrideId id из настроек контейнера; пустое значение и "auto" — авторежим.
     */
    fun select(
        protonMajor: Int,
        arch: BridgeArch,
        requiredInterfaces: Set<String>,
        candidates: List<BridgeDescriptor>,
        overrideId: String? = null,
    ): BridgeSelection {
        val requestedOverride = overrideId?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(AUTO_OVERRIDE, ignoreCase = true)
        }

        if (requestedOverride != null) {
            val override = candidates.firstOrNull { it.id == requestedOverride }
            when {
                override == null -> {
                    // Не падаем в ошибку: контейнер мог пережить удаление моста.
                    val reason = "override '$requestedOverride' not found among candidates"
                    return selectAuto(protonMajor, arch, requiredInterfaces, candidates, reason)
                }
                !override.isCompatibleWith(protonMajor, arch) -> {
                    val reason = "override '${override.id}' is for Proton ${override.protonMajor} ${override.arch.wineName}, " +
                        "container needs Proton $protonMajor ${arch.wineName}"
                    return selectAuto(protonMajor, arch, requiredInterfaces, candidates, reason)
                }
                else -> return BridgeSelection(
                    chosen = override,
                    missingInterfaces = requiredInterfaces - override.interfaces,
                    overrideApplied = true,
                    overrideIgnoredReason = null,
                    reason = "user override",
                )
            }
        }

        return selectAuto(protonMajor, arch, requiredInterfaces, candidates, null)
    }

    private fun selectAuto(
        protonMajor: Int,
        arch: BridgeArch,
        requiredInterfaces: Set<String>,
        candidates: List<BridgeDescriptor>,
        ignoredOverrideReason: String?,
    ): BridgeSelection {
        val compatible = candidates.filter { it.isCompatibleWith(protonMajor, arch) }
        if (compatible.isEmpty()) {
            return BridgeSelection(
                chosen = null,
                missingInterfaces = requiredInterfaces,
                overrideApplied = false,
                overrideIgnoredReason = ignoredOverrideReason,
                reason = "no compatible bridge for Proton $protonMajor ${arch.wineName}",
            )
        }

        val covering = compatible.filter { requiredInterfaces.all(it.interfaces::contains) }
        if (covering.isNotEmpty()) {
            val chosen = covering.minWith(newestFirst)
            return BridgeSelection(
                chosen = chosen,
                missingInterfaces = emptySet(),
                overrideApplied = false,
                overrideIgnoredReason = ignoredOverrideReason,
                reason = "auto: covers all required interfaces",
            )
        }

        // Полного покрытия нет ни у кого — берём минимальный дефицит, чтобы
        // игра получила максимум рабочих интерфейсов.
        val chosen = compatible.minWith(
            compareBy<BridgeDescriptor> { (requiredInterfaces - it.interfaces).size }.then(newestFirst),
        )
        return BridgeSelection(
            chosen = chosen,
            missingInterfaces = requiredInterfaces - chosen.interfaces,
            overrideApplied = false,
            overrideIgnoredReason = ignoredOverrideReason,
            reason = "auto: best partial coverage",
        )
    }

    /** Свежесть моста: покрытие, затем версии по семействам, затем источник. */
    private val newestFirst = Comparator<BridgeDescriptor> { a, b ->
        val byCount = b.interfaces.size.compareTo(a.interfaces.size)
        if (byCount != 0) return@Comparator byCount

        val aVersions = SteamInterfaceVersion.highestPerFamily(a.interfaces)
        val bVersions = SteamInterfaceVersion.highestPerFamily(b.interfaces)
        for (family in (aVersions.keys + bVersions.keys).sorted()) {
            val av = aVersions[family] ?: -1
            val bv = bVersions[family] ?: -1
            if (av != bv) return@Comparator bv.compareTo(av)
        }

        // При равном покрытии встроенный мост безопаснее: он собран с этим unixlib.
        val bySource = sourceRank(a.source).compareTo(sourceRank(b.source))
        if (bySource != 0) return@Comparator bySource

        // Детерминированный порядок для тестов и стабильных логов.
        a.id.compareTo(b.id)
    }

    private fun sourceRank(source: BridgeSource): Int = when (source) {
        BridgeSource.BUNDLED_IN_PROTON -> 0
        BridgeSource.ARCHIVE -> 1
        BridgeSource.IMPORTED -> 2
    }
}

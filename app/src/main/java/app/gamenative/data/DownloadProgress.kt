package app.gamenative.data

/**
 * Потолок процента до фактического завершения: 100% показываем только после onComplete,
 * иначе округление и оценка размеров дают «100%, а загрузка ещё идёт».
 */
internal const val MAX_PROGRESS_BEFORE_COMPLETION = 0.999f

/**
 * Прогресс по депотам одного прогона загрузки.
 *
 * - [doneBytesByDepot] — high-water движка по депоту. Он включает и уже лежащие на диске
 *   байты (перепроверенные и «доверенные» при докачке), поэтому учитывать их надо здесь
 *   один раз, а не добавлять к снимку прошлого прогона.
 * - [totalBytesByDepot] — размер депота из отчёта движка; он точнее appinfo.
 * - [expectedBytesByDepot] — оценка размера из appinfo для депотов, по которым движок ещё
 *   не отчитался.
 *
 * Считаем по ВСЕМ депотам прогона (включая DLC), а не по одному: иначе бар доходит до 100%
 * на первом депоте, пока следующие ещё качаются.
 */
internal fun computeDownloadProgress(
    doneBytesByDepot: Map<Int, Long>,
    totalBytesByDepot: Map<Int, Long>,
    expectedBytesByDepot: Map<Int, Long>,
    completed: Boolean,
): Float {
    if (completed) return 1f

    val depotIds = expectedBytesByDepot.keys + totalBytesByDepot.keys + doneBytesByDepot.keys
    if (depotIds.isEmpty()) return 0f

    var done = 0L
    var total = 0L
    for (depotId in depotIds) {
        done += (doneBytesByDepot[depotId] ?: 0L).coerceAtLeast(0L)
        // Размер от движка важнее оценки: по нему он реально считает done.
        total += totalBytesByDepot[depotId]?.takeIf { it > 0L }
            ?: (expectedBytesByDepot[depotId] ?: 0L).coerceAtLeast(0L)
    }
    if (total <= 0L) return 0f

    val progress = done.toDouble() / total.toDouble()
    return progress.coerceIn(0.0, MAX_PROGRESS_BEFORE_COMPLETION.toDouble()).toFloat()
}

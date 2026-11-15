package app.gamenative.utils

import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

/**
 * Все хосты, с которых приложение скачивает собственные файлы.
 * Зачем в одном месте: список зеркал — это деталь развёртывания, и менять его нужно в одном файле.
 */
object DownloadMirrors {
    const val PRIMARY_HOST = "downloads.gamenative.app"
    const val R2_HOST = "pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev"
    const val THORHUB_MIRROR_BASE = "https://github.com/borodatych/ThorHub/releases/download/mirror/"
    const val PRIMARY_BASE = "https://$PRIMARY_HOST/"

    fun primaryUrl(fileName: String): String = PRIMARY_BASE + fileName.trimStart('/')
}

/**
 * Разворачивает ссылку на основной хост в упорядоченный список зеркал.
 * Зачем: основной хост недоступен с части сетей, а файлы на зеркалах лежат побайтово те же.
 */
object MirrorResolver {

    /** Хост ссылки без схемы и порта; нужен, чтобы помнить здоровье хоста, а не конкретный файл. */
    fun hostOf(url: String): String? = ParsedUrl.parse(url)?.host

    fun candidates(url: String): List<String> {
        val parsed = ParsedUrl.parse(url) ?: return listOf(url)
        if (!parsed.host.equals(DownloadMirrors.PRIMARY_HOST, ignoreCase = true)) return listOf(url)
        if (parsed.path.isEmpty() || parsed.path == "/") return listOf(url)

        val samePath = "https://${DownloadMirrors.R2_HOST}${parsed.path}"
        // На GitHub вложения не умеют содержать путь, поэтому путь склеивается через "__".
        val flattened = parsed.path.trimStart('/').replace("/", "__")
        val onThorHub = DownloadMirrors.THORHUB_MIRROR_BASE + flattened

        // distinct сохраняет порядок: первым всегда идёт оригинал.
        return listOf(url, samePath, onThorHub).distinct()
    }

    private data class ParsedUrl(val host: String, val path: String) {
        companion object {
            fun parse(url: String): ParsedUrl? {
                val schemeEnd = url.indexOf("://")
                if (schemeEnd <= 0) return null
                val scheme = url.substring(0, schemeEnd).lowercase()
                if (scheme != "http" && scheme != "https") return null

                val rest = url.substring(schemeEnd + 3)
                val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
                val tail = if (authorityEnd < 0) "" else rest.substring(authorityEnd)

                val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
                if (host.isEmpty()) return null

                val pathEnd = tail.indexOfFirst { it == '?' || it == '#' }
                val path = if (pathEnd < 0) tail else tail.substring(0, pathEnd)
                return ParsedUrl(host, path)
            }
        }
    }
}

/** HTTP-ответ, который не даёт файла: такой источник нужно пропустить и взять следующий. */
class DownloadHttpException(val url: String, val code: Int) : IOException("HTTP $code: $url")

/**
 * Здоровье хостов за время жизни процесса (одна сессия приложения).
 * Зачем: с части сетей первые зеркала стабильно не отвечают, и без памяти каждый файл снова ждёт их таймаут.
 * Класс чистый: без сети и Android, время берётся из [clock], поэтому поведение проверяется тестами.
 */
class MirrorHostHealth(
    private val clock: () -> Long = System::currentTimeMillis,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    // Хост считается больным до этого момента; храним только тех, кто уже падал.
    private val downUntil = HashMap<String, Long>()
    // Перебор источников идёт из нескольких корутин, поэтому карту меняем под общим замком.
    private val lock = Any()

    /** Помечает хост упавшим на [cooldownMs]; повторные падения лишь продлевают срок. */
    fun markDown(url: String) {
        val host = MirrorResolver.hostOf(url) ?: return
        synchronized(lock) {
            downUntil[host] = clock() + cooldownMs
        }
    }

    /** Успех снимает метку сразу: хост снова рабочий, ждать кулдаун незачем. */
    fun markUp(url: String) {
        val host = MirrorResolver.hostOf(url) ?: return
        synchronized(lock) {
            downUntil.remove(host)
        }
    }

    /** Лежит ли хост этой ссылки прямо сейчас; истёкшая метка удаляется при проверке. */
    fun isDown(url: String): Boolean {
        val host = MirrorResolver.hostOf(url) ?: return false
        return isHostDown(host)
    }

    fun isHostDown(host: String): Boolean {
        val key = host.lowercase()
        synchronized(lock) {
            val until = downUntil[key] ?: return false
            // Кулдаун истёк — хост снова считается здоровым.
            if (until <= clock()) {
                downUntil.remove(key)
                return false
            }
            return true
        }
    }

    /**
     * Ставит здоровые хосты вперёд, больные — в конец.
     * Зачем не выбрасывать больных: последняя надежда на скачивание важнее экономии времени.
     */
    fun order(candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        // partition стабилен: внутри обеих групп исходный порядок зеркал сохраняется.
        val (down, healthy) = candidates.partition { isDown(it) }
        return healthy + down
    }

    /** Реакция на ошибку одного источника: обновляет память о хосте по типу сбоя. */
    fun recordFailure(url: String, error: Throwable) {
        if (isHostFailure(error)) markDown(url)
    }

    companion object {
        // Десяти минут хватает, чтобы не биться в мёртвый хост всю сессию, но и не забыть его навсегда.
        const val DEFAULT_COOLDOWN_MS = 10 * 60 * 1000L

        /**
         * Что считать сбоем хоста.
         * HTTP<500 — ответ хоста (404 значит «нет файла», хост при этом жив).
         * IOException — сеть/таймаут/соединение, то есть проблема именно хоста или пути до него.
         */
        fun isHostFailure(error: Throwable): Boolean = when (error) {
            is DownloadHttpException -> error.code >= 500
            is IOException -> true
            else -> false
        }
    }
}

/** Общая на процесс память о хостах: так кулдаун переживает отдельные загрузки файлов. */
object MirrorHostHealthSession {
    val instance = MirrorHostHealth()
}

/**
 * Перебор источников одного файла.
 * Зачем отдельно от загрузки: порядок кандидатов и правила повтора тестируются без сети.
 */
object MirrorDownloads {

    /**
     * Пробует кандидатов по порядку, пока [fetch] не вернёт результат.
     * Переходит к следующему только на сетевой ошибке или HTTP>=400:
     * ошибка разбора/распаковки означает, что источник отдал файл, и повтор её не лечит.
     */
    suspend fun <T> tryInOrder(
        candidates: List<String>,
        onAttemptFailed: () -> Unit = {},
        health: MirrorHostHealth = MirrorHostHealthSession.instance,
        fetch: suspend (String) -> T,
    ): T {
        var lastError: Exception? = null
        // Больные хосты уходят в конец, но не пропадают: рабочий источник может оказаться только там.
        for ((index, url) in health.order(candidates).withIndex()) {
            try {
                val result = fetch(url)
                health.markUp(url)
                Timber.i("Download source #${index + 1} succeeded: $url")
                return result
            } catch (e: IOException) {
                lastError = e
                health.recordFailure(url, e)
                // Недокачанный файл нельзя оставлять: следующий источник начнёт с нуля.
                onAttemptFailed()
                Timber.w(e, "Download source #${index + 1} failed: $url")
            }
        }
        throw lastError ?: IOException("No download candidate for $candidates")
    }

    /**
     * То же для небольших документов (манифесты, списки), где загрузчик сигналит null вместо исключения.
     * Зачем null как «попробуй следующий»: у сетевых ошибок и HTTP-отказов здесь одна реакция.
     */
    suspend fun <T : Any> firstNonNull(
        url: String,
        health: MirrorHostHealth = MirrorHostHealthSession.instance,
        fetch: suspend (String) -> T?,
    ): T? {
        for ((index, candidate) in health.order(MirrorResolver.candidates(url)).withIndex()) {
            val result = try {
                fetch(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                health.recordFailure(candidate, e)
                Timber.w(e, "Document source #${index + 1} failed: $candidate")
                null
            }
            if (result != null) {
                health.markUp(candidate)
                Timber.i("Document source #${index + 1} succeeded: $candidate")
                return result
            }
            // null без исключения — это может быть 404 (нет файла), а не отказ хоста: метку не ставим.
            Timber.w("Document source #${index + 1} returned nothing: $candidate")
        }
        return null
    }
}

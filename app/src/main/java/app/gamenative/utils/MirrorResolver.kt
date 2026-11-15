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
        fetch: suspend (String) -> T,
    ): T {
        var lastError: Exception? = null
        for ((index, url) in candidates.withIndex()) {
            try {
                val result = fetch(url)
                Timber.i("Download source #${index + 1} succeeded: $url")
                return result
            } catch (e: IOException) {
                lastError = e
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
    suspend fun <T : Any> firstNonNull(url: String, fetch: suspend (String) -> T?): T? {
        for ((index, candidate) in MirrorResolver.candidates(url).withIndex()) {
            val result = try {
                fetch(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Document source #${index + 1} failed: $candidate")
                null
            }
            if (result != null) {
                Timber.i("Document source #${index + 1} succeeded: $candidate")
                return result
            }
            Timber.w("Document source #${index + 1} returned nothing: $candidate")
        }
        return null
    }
}

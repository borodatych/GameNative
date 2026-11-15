package app.gamenative.ui.component.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.DownloadService
import app.gamenative.utils.GamesStorage
import app.gamenative.utils.StorageUtils
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import kotlinx.coroutines.launch

/**
 * Выбор папки для игр: первый запуск и настройки используют один и тот же диалог.
 *
 * Порядок шагов важен:
 * 1. пользователь выбирает путь (по умолчанию или через SAF);
 * 2. без "Всех файлов" путь не сохранится, поэтому сначала ведём в настройки;
 * 3. после возврата проверяем доступ и только тогда пишем настройку;
 * 4. если в старом корне есть игры — предлагаем их перенести.
 */
@Composable
fun GamesFolderDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val defaultRoot = remember { GamesStorage.defaultGamesRoot() }
    val currentRoot = remember { PrefManager.effectiveGamesRootPath }

    // Путь, ожидающий разрешения "Все файлы": сохраняем только после его выдачи
    var pendingPath by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    var moveRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moving by remember { mutableStateOf(false) }
    var movePercent by remember { mutableFloatStateOf(0f) }
    var moveDetail by remember { mutableStateOf("") }

    // Сохраняем корень и решаем, предлагать ли перенос старых игр
    fun applyPath(path: String) {
        if (!GamesStorage.ensureGamesRoot(path)) {
            SnackbarManager.show(context.getString(R.string.games_folder_cannot_write, path))
            return
        }
        val oldRoot = PrefManager.gamesRootPath
        if (oldRoot.isNotBlank() && oldRoot != path) {
            // Пока игры не перенесены, старый корень должен оставаться видимым для библиотеки
            PrefManager.previousGamesRootPath = oldRoot
        }
        PrefManager.gamesRootPath = path
        // Кэш списка загрузок помнит старые пути — сбрасываем, чтобы библиотека пересканировала
        DownloadService.invalidateCache()
        if (oldRoot.isNotBlank() && oldRoot != path && GamesStorage.hasGamesInRoot(oldRoot)) {
            moveRequest = oldRoot to path
        } else {
            onDismiss()
        }
    }

    fun requestPath(path: String) {
        if (GamesStorage.hasAllFilesAccess()) {
            applyPath(path)
        } else {
            // Сначала объясняем и открываем настройки; applyPath вызовется на onResume
            pendingPath = path
            showPermissionDialog = true
        }
    }

    fun startMove(oldRoot: String, newRoot: String) {
        moving = true
        scope.launch {
            val stores = GamesStorage.STORE_DIRS.filter { File(oldRoot, it).isDirectory }
            var failed = false
            stores.forEachIndexed { index, store ->
                val result = StorageUtils.moveDirectory(
                    sourceDir = File(oldRoot, store).absolutePath,
                    targetDir = File(newRoot, store).absolutePath,
                ) { currentFile, fileProgress, movedFiles, totalFiles ->
                    // Общий прогресс по всем магазинам, а не по одному файлу
                    movePercent = ((index + fileProgress) / stores.size.coerceAtLeast(1) * 100f).coerceIn(0f, 100f)
                    moveDetail = "$store/$currentFile ($movedFiles/$totalFiles)"
                }
                if (result.isFailure) failed = true
            }
            moving = false
            moveRequest = null
            if (!failed) {
                // Всё перенесено — старый корень больше не нужен
                PrefManager.previousGamesRootPath = ""
            }
            SnackbarManager.show(
                context.getString(
                    if (failed) R.string.games_folder_move_failed else R.string.games_folder_move_done,
                ),
            )
            onDismiss()
        }
    }

    // Возврат из системных настроек: разрешение могло появиться, тогда сохраняем отложенный путь
    DisposableEffect(lifecycleOwner, pendingPath) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val path = pendingPath
                if (path != null && GamesStorage.hasAllFilesAccess()) {
                    pendingPath = null
                    showPermissionDialog = false
                    applyPath(path)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = GamesStorage.treeUriToAbsolutePath(uri)
        if (path == null) {
            // Облачные и прочие нелокальные провайдеры не дают обычный путь к папке
            SnackbarManager.show(context.getString(R.string.games_folder_unsupported_provider))
            return@rememberLauncherForActivityResult
        }
        requestPath(path)
    }

    when {
        moving -> MessageDialog(
            visible = true,
            title = stringResource(R.string.games_folder_move_title),
            message = stringResource(
                R.string.games_folder_move_progress,
                movePercent.toInt(),
                moveDetail,
            ),
        )

        moveRequest != null -> {
            val (oldRoot, newRoot) = moveRequest!!
            MessageDialog(
                visible = true,
                title = stringResource(R.string.games_folder_move_title),
                message = stringResource(R.string.games_folder_move_message, oldRoot, newRoot),
                confirmBtnText = stringResource(R.string.games_folder_move_confirm),
                onConfirmClick = { startMove(oldRoot, newRoot) },
                dismissBtnText = stringResource(R.string.games_folder_move_later),
                onDismissClick = {
                    moveRequest = null
                    onDismiss()
                },
                onDismissRequest = {
                    moveRequest = null
                    onDismiss()
                },
            )
        }

        showPermissionDialog -> MessageDialog(
            visible = true,
            title = stringResource(R.string.games_folder_permission_title),
            message = stringResource(R.string.games_folder_permission_message),
            confirmBtnText = stringResource(R.string.games_folder_open_settings),
            onConfirmClick = { GamesStorage.openAllFilesAccessSettings(context) },
            dismissBtnText = stringResource(R.string.games_folder_cancel),
            onDismissClick = {
                showPermissionDialog = false
                pendingPath = null
            },
            onDismissRequest = {
                showPermissionDialog = false
                pendingPath = null
            },
        )

        else -> MessageDialog(
            visible = true,
            title = stringResource(R.string.games_folder_dialog_title),
            message = stringResource(R.string.games_folder_dialog_message, defaultRoot, currentRoot),
            confirmBtnText = stringResource(R.string.games_folder_use_default),
            onConfirmClick = { requestPath(defaultRoot) },
            actionBtnText = stringResource(R.string.games_folder_choose),
            onActionClick = { treeLauncher.launch(null) },
            dismissBtnText = stringResource(R.string.games_folder_skip),
            onDismissClick = onDismiss,
            onDismissRequest = onDismiss,
        )
    }
}

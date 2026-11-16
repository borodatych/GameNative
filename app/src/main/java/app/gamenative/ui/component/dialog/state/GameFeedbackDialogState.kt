package app.gamenative.ui.component.dialog.state

import androidx.compose.runtime.saveable.mapSaver
import app.gamenative.R

data class GameFeedbackDialogState(
    val visible: Boolean,
    val appId: String = "",
    val rating: Int = 0, // 0-5 stars, 0 means no selection
    val selectedTags: Set<String> = emptySet(),
    val feedbackText: String = "",
    // null — подпись берётся из строковых ресурсов
    val confirmBtnText: String? = null,
    val dismissBtnText: String? = null,
) {
    companion object {
        // Available tags for game issues
        val AVAILABLE_TAGS = listOf(
            "controller_issues",
            "no_graphics",
            "cloud_saves_missing",
            "graphics_glitches",
            "audio_glitches",
            "does_not_open",
            "directx_error"
        )

        // Ключи меток не переводятся (уходят на сервер), переводится только подпись
        val TAG_LABEL_RES: Map<String, Int> = mapOf(
            "controller_issues" to R.string.game_feedback_tag_controller_issues,
            "no_graphics" to R.string.game_feedback_tag_no_graphics,
            "cloud_saves_missing" to R.string.game_feedback_tag_cloud_saves_missing,
            "graphics_glitches" to R.string.game_feedback_tag_graphics_glitches,
            "audio_glitches" to R.string.game_feedback_tag_audio_glitches,
            "does_not_open" to R.string.game_feedback_tag_does_not_open,
            "directx_error" to R.string.game_feedback_tag_directx_error,
        )

        val Saver = mapSaver(
            save = { state ->
                mapOf(
                    "visible" to state.visible,
                    "appId" to state.appId,
                    "rating" to state.rating,
                    "selectedTags" to state.selectedTags.toList(),
                    "feedbackText" to state.feedbackText,
                    "confirmBtnText" to state.confirmBtnText,
                    "dismissBtnText" to state.dismissBtnText,
                )
            },
            restore = { savedMap ->
                GameFeedbackDialogState(
                    visible = savedMap["visible"] as Boolean,
                    appId = savedMap["appId"] as String,
                    rating = savedMap["rating"] as Int,
                    selectedTags = (savedMap["selectedTags"] as List<String>).toSet(),
                    feedbackText = savedMap["feedbackText"] as String,
                    confirmBtnText = savedMap["confirmBtnText"] as String?,
                    dismissBtnText = savedMap["dismissBtnText"] as String?,
                )
            },
        )
    }
}

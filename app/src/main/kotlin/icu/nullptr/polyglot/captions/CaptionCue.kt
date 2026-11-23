package icu.nullptr.polyglot.captions

import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.moduleInitialized

data class CaptionCue(
    val videoId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val windowId: Int = UNKNOWN_WINDOW_ID,
) {
    fun cacheKey(): String = "${videoId}|${windowId}|${startMs}|${endMs}|${normalizedText()}"

    fun normalizedText(): String = clean(text)

    companion object {
        const val UNKNOWN_TIME_MS = -1L
        const val UNKNOWN_WINDOW_ID = -1

        private val whitespace = Regex("\\s+")

        fun normalize(text: String): String =
            text.replace(whitespace, " ").trim()

        /**
         * Normalizes a caption and, when enabled in settings, strips SDH
         * annotations wrapped in square brackets.
         */
        fun clean(text: String): String =
            if (moduleInitialized && module.config.removeSdhTags) {
                normalize(CaptionTextCleaner.stripSdhTags(text))
            } else {
                normalize(text)
            }
    }
}

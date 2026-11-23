package icu.nullptr.polyglot.captions

import icu.nullptr.polyglot.core.ConfigManager
import icu.nullptr.polyglot.module

object BilingualFormatter {
    fun format(original: CharSequence?, translated: String?): CharSequence {
        val originalText = CaptionCue.clean(original?.toString().orEmpty())
        val translatedText = CaptionCue.normalize(translated.orEmpty())
        if (originalText.isBlank()) {
            return ""
        }
        if (translatedText.isBlank() || translatedText == originalText) {
            return originalText
        }
        return when (module.config.subtitleMode) {
            ConfigManager.SUBTITLE_ORIGINAL_FIRST -> "$originalText\n$translatedText"
            ConfigManager.SUBTITLE_TRANSLATION_FIRST -> "$translatedText\n$originalText"
            ConfigManager.SUBTITLE_TRANSLATION_ONLY -> translatedText
            else -> translatedText
        }
    }
}

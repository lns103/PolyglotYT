package icu.nullptr.polyglot.captions

/**
 * Removes SDH (subtitles for the deaf and hard of hearing) annotations that are
 * wrapped in square brackets, such as "[music]", "[applause]" or "[掌声]".
 */
object CaptionTextCleaner {
    private val sdhTag = Regex("\\[[^\\[\\]]*\\]")
    private val whitespace = Regex("\\s+")

    fun stripSdhTags(text: String): String =
        text.replace(sdhTag, " ")
            .replace(whitespace, " ")
            .trim()
}

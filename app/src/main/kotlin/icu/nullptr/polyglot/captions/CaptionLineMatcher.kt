package icu.nullptr.polyglot.captions

/**
 * Helpers for multi-line captions. YouTube renders every visual line of a cue
 * through its own render view, so a cue can be spread across several renderer
 * objects even though the stored cue text is a single normalized line.
 */
object CaptionLineMatcher {
    fun lineStart(cue: String, line: String): Int? {
        if (line.isEmpty()) return null
        var index = cue.indexOf(line)
        while (index >= 0) {
            val startsLine = index == 0 || cue[index - 1] == ' '
            val end = index + line.length
            val endsLine = end == cue.length || cue[end] == ' '
            if (startsLine && endsLine) return index
            index = cue.indexOf(line, index + 1)
        }
        return null
    }

    fun isLineOf(cue: String, line: String): Boolean =
        line.isNotEmpty() && line != cue && lineStart(cue, line) != null

    fun isEndOf(cue: String, line: String): Boolean {
        if (line.isEmpty()) return false
        val start = lineStart(cue, line) ?: return false
        return start + line.length == cue.length
    }

    /**
     * Returns the indexes of [lines], ordered by their position in [cue], when
     * those lines exactly reconstruct the cue. Returns null when they do not,
     * so callers never treat unrelated caption views as siblings.
     */
    fun orderedLineIndexes(cue: String, lines: List<String>): List<Int>? {
        val starts = sortedMapOf<Int, Int>()
        for ((index, line) in lines.withIndex()) {
            if (line.isEmpty() || line == cue || line.length > cue.length) continue
            val start = lineStart(cue, line) ?: continue
            starts.putIfAbsent(start, index)
        }
        if (starts.size < 2) return null

        val ordered = starts.entries.sortedBy { it.key }
        var cursor = 0
        for ((start, index) in ordered) {
            if (start != cursor) return null
            cursor += lines[index].length
            if (cursor < cue.length) {
                if (cue[cursor] != ' ') return null
                cursor++
            }
        }
        if (cursor < cue.length) return null

        return ordered.map { it.value }
    }
}

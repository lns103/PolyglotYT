package icu.nullptr.polyglot.translate

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Parses the response of a batch translation request.
 *
 * Accepts either:
 * - `{"translations":[{"id":1,"text":"..."}]}`
 * - `[{"id":1,"text":"..."}, ...]`
 * - `["translated line 1", "translated line 2"]`
 *
 * Missing or malformed entries are returned as empty strings so the caller can
 * fall back to individual requests.
 */
object BatchResponseParser {
    private val THINK_BLOCK = Regex("(?is)<think\\b[^>]*>.*?</think\\s*>")
    private val CODE_FENCE = Regex("(?s)```(?:json)?\\s*(.*?)```")
    private val NUMBERED_LINE_PREFIX = Regex("^(\\d{1,3})\\s*[.、):：]\\s*(.*)$")
    private val TEXT_KEYS = listOf("text", "translation", "content", "translated", "value")

    data class ParsedBatch(
        val translations: List<String>,
        val receivedCount: Int,
        val expectedCount: Int,
    ) {
        val isValid: Boolean
            get() = receivedCount == expectedCount &&
                translations.size == expectedCount &&
                translations.all { it.isNotBlank() }
    }

    fun parse(content: String, expectedCount: Int): List<String> =
        parseBatch(content, expectedCount).translations

    fun parseBatch(content: String, expectedCount: Int): ParsedBatch {
        if (expectedCount <= 0) {
            return ParsedBatch(emptyList(), 0, expectedCount)
        }

        val cleaned = clean(content)
        val json = extractJson(cleaned)
        if (json != null) {
            val parsed = runCatching { parseJson(json, expectedCount) }.getOrNull()
            if (parsed != null && parsed.translations.any { it.isNotBlank() }) return parsed
        }
        return parseNumberedLines(cleaned, expectedCount)
    }

    fun clean(content: String): String {
        val withoutThink = THINK_BLOCK.replace(content, " ")
        val fenced = CODE_FENCE.find(withoutThink)
        return (fenced?.groupValues?.getOrNull(1) ?: withoutThink).trim()
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null

        val opener = text[start]
        val closer = if (opener == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                opener -> depth++
                closer -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun parseJson(json: String, expectedCount: Int): ParsedBatch {
        val element = JsonParser.parseString(json)
        val entries = when {
            element.isJsonObject -> element.asJsonObject.get("translations")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray

            element.isJsonArray -> element.asJsonArray
            else -> null
        } ?: return ParsedBatch(emptyTranslations(expectedCount), 0, expectedCount)

        val result = MutableList(expectedCount) { "" }
        var cursor = 0
        var receivedCount = 0

        for (entry in entries) {
            val (id, rawText) = readEntry(entry) ?: continue
            val text = sanitize(rawText)
            if (text.isEmpty()) continue
            receivedCount++

            val index = if (id != null && id in 1..expectedCount) {
                id - 1
            } else {
                nextEmptyIndex(result, cursor)
            }
            if (index !in result.indices) continue

            if (result[index].isEmpty()) {
                result[index] = text
                cursor = index + 1
            }
        }
        return ParsedBatch(result, receivedCount, expectedCount)
    }

    private fun readEntry(element: JsonElement): Pair<Int?, String>? {
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            return if (primitive.isString) null to primitive.asString else null
        }
        if (element.isJsonArray) {
            // Some models return a line-wrapped translation as an array of lines.
            val text = element.asJsonArray.mapNotNull { it.asTextOrNull() }
                .joinToString(" ")
                .trim()
            return if (text.isEmpty()) null else null to text
        }
        if (!element.isJsonObject) return null

        val entry = element.asJsonObject
        val id = entry.get("id")?.let { value ->
            if (value.isJsonPrimitive) value.asString.toIntOrNull() else null
        }
        val text = TEXT_KEYS.firstNotNullOfOrNull { key ->
            entry.get(key)?.let { value -> value.asTranslationText() }
        } ?: return null

        return id to text
    }

    private fun JsonElement.asTranslationText(): String? = when {
        isJsonPrimitive -> asString
        isJsonArray -> asJsonArray.mapNotNull { it.asTextOrNull() }
            .joinToString(" ")
            .ifBlank { null }

        else -> null
    }

    private fun JsonElement.asTextOrNull(): String? =
        if (isJsonPrimitive) asString else null

    private fun nextEmptyIndex(result: List<String>, from: Int): Int {
        for (index in from until result.size) {
            if (result[index].isEmpty()) return index
        }
        return result.indexOfFirst { it.isEmpty() }
    }

    private fun sanitize(text: String): String =
        text.trim().removeSurrounding("\"").trim()

    private fun parseNumberedLines(content: String, expectedCount: Int): ParsedBatch {
        val result = MutableList(expectedCount) { "" }
        var currentIndex = -1

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                currentIndex = -1
                continue
            }

            val match = NUMBERED_LINE_PREFIX.matchEntire(line)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                currentIndex = if (id != null && id in 1..expectedCount) id - 1 else -1
                if (currentIndex < 0) continue
                appendLine(result, currentIndex, sanitize(match.groupValues[2]))
            } else if (currentIndex >= 0 && !isStructuredLine(line)) {
                // The model wrapped one translation onto continuation lines,
                // which is common when the subtitle itself contains line breaks.
                appendLine(result, currentIndex, sanitize(line))
            }
        }

        return ParsedBatch(
            translations = result,
            receivedCount = result.count { it.isNotBlank() },
            expectedCount = expectedCount,
        )
    }

    private fun appendLine(result: MutableList<String>, index: Int, text: String) {
        if (text.isEmpty()) return
        val existing = result[index]
        result[index] = if (existing.isEmpty()) text else "$existing $text"
    }

    private fun isStructuredLine(line: String): Boolean =
        line.startsWith("{") ||
            line.startsWith("}") ||
            line.startsWith("[") ||
            line.startsWith("]") ||
            line.startsWith("```")

    private fun emptyTranslations(count: Int): List<String> = MutableList(count) { "" }
}

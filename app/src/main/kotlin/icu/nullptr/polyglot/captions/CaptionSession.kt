package icu.nullptr.polyglot.captions

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CaptionSession {
    private val translations = ConcurrentHashMap<String, String>()
    private val observedCues = ConcurrentHashMap<String, ObservedCue>()
    private val renderedTexts = ConcurrentHashMap<String, Long>()
    private val observedCueCounter = AtomicInteger(0)
    private val observationSequence = AtomicLong(0L)

    /**
     * Cue texts from the most recent caption window update. Used only to prefer
     * the subtitle that is actually on screen when several whole-track cues
     * share the same trailing fragment.
     */
    @Volatile
    private var activeCueTexts: List<String> = emptyList()

    fun translationFor(original: String?): String? {
        if (original == null) return null
        return translations[normalize(original)]
    }

    /**
     * Resolves the cue a rendered line belongs to.
     *
     * Only cues that are part of the current caption window are considered:
     * matching against the whole track picked up identical trailing fragments
     * from other moments in the video. Within the window a rendered fragment may
     * be the head, the tail or a middle line of a cue. When no window is known
     * this returns null so the caller keeps YouTube's original text; exact
     * whole-cue matches are handled separately by the caller.
     */
    fun translatedCueContaining(fragment: String?): CaptionTranslation? {
        val normalized = normalize(fragment.orEmpty())
        if (normalized.isEmpty()) return null

        val active = activeCueTexts
        if (active.isEmpty()) return null
        val activeTexts = active.toHashSet()

        val candidates = ArrayList<Candidate>()
        for (observed in observedCues.values) {
            val cueText = observed.cue.normalizedText()
            if (cueText == normalized || cueText !in activeTexts) continue
            if (!translations.containsKey(cueText)) continue
            if (!CaptionLineMatcher.isLineOf(cueText, normalized)) continue

            candidates += Candidate(
                text = cueText,
                sequence = observed.sequence,
                activeRank = activeRank(cueText, active),
            )
        }
        if (candidates.isEmpty()) return null

        val chosen = candidates.minWithOrNull(CANDIDATE_ORDER) ?: return null
        val translated = translations[chosen.text] ?: return null
        return CaptionTranslation(chosen.text, translated)
    }

    fun putTranslation(original: String?, translated: String?) {
        if (original == null || translated == null) return
        translations[normalize(original)] = translated
    }

    fun clear() {
        translations.clear()
        observedCues.clear()
        renderedTexts.clear()
        activeCueTexts = emptyList()
        observedCueCounter.set(0)
        observationSequence.set(0L)
    }

    fun observeCue(cue: CaptionCue): Boolean {
        if (cue.normalizedText().isEmpty()) return false
        val key = cue.cacheKey()
        if (observedCues.containsKey(key)) return false

        val observed = ObservedCue(cue, observationSequence.incrementAndGet())
        if (observedCues.putIfAbsent(key, observed) != null) return false

        observedCueCounter.incrementAndGet()
        return true
    }

    fun observeNewCues(cues: Iterable<CaptionCue>): List<CaptionCue> {
        val newCues = ArrayList<CaptionCue>()
        for (cue in cues) {
            if (observeCue(cue)) {
                newCues += cue
            }
        }
        return newCues
    }

    /**
     * Records the cues of the latest overlay window. Very large lists are not a
     * window (some builds pass the whole track) and are ignored so they cannot
     * bias cue resolution.
     */
    fun updateActiveCues(cues: Iterable<CaptionCue>) {
        val texts = ArrayList<String>()
        for (cue in cues) {
            val text = cue.normalizedText()
            if (text.isNotEmpty()) texts += text
        }
        activeCueTexts = if (texts.size <= MAX_ACTIVE_CUES) texts else emptyList()
    }

    fun observeRenderedText(text: CharSequence?): Boolean {
        val normalized = normalize(text?.toString().orEmpty())
        if (normalized.isEmpty()) return false
        val now = System.currentTimeMillis()
        val previous = renderedTexts.put(normalized, now)
        return previous == null || now - previous > RENDERED_TEXT_LOG_INTERVAL_MS
    }

    fun observedCueCount(): Int = observedCueCounter.get()

    private fun activeRank(text: String, active: List<String>): Int {
        if (active.isEmpty()) return Int.MAX_VALUE
        val index = active.indexOfLast { it == text }
        // Prefer the newest entry of the window, i.e. the largest index.
        return if (index < 0) Int.MAX_VALUE else -index
    }

    private fun normalize(text: String): String =
        CaptionCue.clean(text)

    private data class ObservedCue(
        val cue: CaptionCue,
        val sequence: Long,
    )

    private data class Candidate(
        val text: String,
        val sequence: Long,
        val activeRank: Int,
    )

    private companion object {
        const val RENDERED_TEXT_LOG_INTERVAL_MS = 30_000L
        const val MAX_ACTIVE_CUES = 8

        val CANDIDATE_ORDER: Comparator<Candidate> =
            compareBy<Candidate> { it.activeRank }
                .thenBy { it.text.length }
                .thenByDescending { it.sequence }
    }

    data class CaptionTranslation(
        val original: String,
        val translated: String,
    )
}

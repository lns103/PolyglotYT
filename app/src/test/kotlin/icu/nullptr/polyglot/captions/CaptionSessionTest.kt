package icu.nullptr.polyglot.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionTest {
    @Test
    fun requiresTheActiveWindow() {
        val session = CaptionSession()
        session.observeCue(cue("we should go now", startMs = 1_000))
        session.putTranslation("we should go now", "我们该走了")

        // Without a known window the caller must keep YouTube's own text.
        assertNull(session.translatedCueContaining("go now"))
    }

    @Test
    fun resolvesLinesInsideTheWindow() {
        val session = CaptionSession()
        val full = cue("we should go now", startMs = 1_000)
        session.observeCue(full)
        session.putTranslation(full.text, "我们该走了")
        session.updateActiveCues(listOf(full))

        assertEquals(full.text, session.translatedCueContaining("go now")?.original)
        assertEquals(full.text, session.translatedCueContaining("we should")?.original)
        assertEquals(full.text, session.translatedCueContaining("should go")?.original)
    }

    @Test
    fun ignoresFragmentsOutsideTheWindow() {
        val session = CaptionSession()
        val current = cue("tell me what you know", startMs = 2_000)
        val other = cue("I don't know", startMs = 90_000)
        session.observeCue(current)
        session.observeCue(other)
        session.putTranslation(current.text, "告诉我你知道什么")
        session.putTranslation(other.text, "我不知道")

        session.updateActiveCues(listOf(current))

        // "know" ends both cues; only the one on screen may be used.
        assertEquals(current.text, session.translatedCueContaining("know")?.original)
        assertEquals(current.text, session.translatedCueContaining("what you know")?.original)
    }

    @Test
    fun ignoresPartialWords() {
        val session = CaptionSession()
        val full = cue("we should go now", startMs = 1_000)
        session.observeCue(full)
        session.putTranslation(full.text, "我们该走了")
        session.updateActiveCues(listOf(full))

        assertNull(session.translatedCueContaining("hould g"))
    }

    @Test
    fun prefersTheNewestCueInTheWindow() {
        val session = CaptionSession()
        val previous = cue("and then we should go now", startMs = 1_000)
        val current = cue("we should go now", startMs = 2_000)
        session.observeCue(previous)
        session.observeCue(current)
        session.putTranslation(previous.text, "然后我们应该现在走")
        session.putTranslation(current.text, "我们应该现在走")

        session.updateActiveCues(listOf(previous, current))

        assertEquals(current.text, session.translatedCueContaining("go now")?.original)
    }

    @Test
    fun ignoresOversizedActiveWindow() {
        val session = CaptionSession()
        val full = cue("we should go now", startMs = 1_000)
        session.observeCue(full)
        session.putTranslation(full.text, "我们该走了")

        val oversized = (1..9).map { cue("filler $it", startMs = 10_000L + it) }
        session.updateActiveCues(oversized)

        // A whole-track list is not a caption window.
        assertNull(session.translatedCueContaining("go now"))
    }

    private fun cue(text: String, startMs: Long): CaptionCue =
        CaptionCue(
            videoId = "",
            startMs = startMs,
            endMs = startMs + 1_000,
            text = text,
        )
}

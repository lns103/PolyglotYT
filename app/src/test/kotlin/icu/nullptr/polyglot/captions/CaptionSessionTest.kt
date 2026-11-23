package icu.nullptr.polyglot.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSessionTest {
    @Test
    fun ignoresCueThatOnlyContainsFragment() {
        val session = CaptionSession()
        session.observeCue(cue("we should go now", startMs = 1_000))
        session.putTranslation("we should go now", "我们该走了")

        // "should go" is contained but is not the rendered tail of the cue.
        assertNull(session.translatedCueContaining("should go"))
    }

    @Test
    fun resolvesRenderedTailFragment() {
        val session = CaptionSession()
        session.observeCue(cue("we should go now", startMs = 1_000))
        session.putTranslation("we should go now", "我们该走了")

        val resolved = session.translatedCueContaining("go now")

        assertEquals("we should go now", resolved?.original)
        assertEquals("我们该走了", resolved?.translated)
    }

    @Test
    fun resolvesHeadFragmentWithoutWindow() {
        val session = CaptionSession()
        val full = cue("we should go now", startMs = 1_000)
        session.observeCue(full)
        session.putTranslation(full.text, "我们该走了")

        // The head line of a multi-line cue is rendered as a prefix.
        assertEquals(full.text, session.translatedCueContaining("we should")?.original)
    }

    @Test
    fun ignoresShortHeadFragmentWithoutWindow() {
        val session = CaptionSession()
        val full = cue("we should go now", startMs = 1_000)
        session.observeCue(full)
        session.putTranslation(full.text, "我们该走了")

        assertNull(session.translatedCueContaining("we"))

        session.updateActiveCues(listOf(full))
        assertEquals(full.text, session.translatedCueContaining("we")?.original)
    }

    @Test
    fun prefersTheTightestSuffixMatch() {
        val session = CaptionSession()
        session.observeCue(cue("and then we should go now", startMs = 1_000))
        session.observeCue(cue("we should go now", startMs = 2_000))
        session.putTranslation("and then we should go now", "然后我们应该现在走")
        session.putTranslation("we should go now", "我们应该现在走")

        assertEquals("we should go now", session.translatedCueContaining("go now")?.original)
    }

    @Test
    fun prefersCueFromTheActiveWindow() {
        val session = CaptionSession()
        val longer = cue("and then we should go now", startMs = 1_000)
        val shorter = cue("we should go now", startMs = 2_000)
        session.observeCue(longer)
        session.observeCue(shorter)
        session.putTranslation(longer.text, "然后我们应该现在走")
        session.putTranslation(shorter.text, "我们应该现在走")

        session.updateActiveCues(listOf(longer))

        assertEquals(longer.text, session.translatedCueContaining("go now")?.original)
    }

    @Test
    fun prefersMostRecentlyObservedOnEqualLengths() {
        val session = CaptionSession()
        session.observeCue(cue("say again", startMs = 1_000))
        session.observeCue(cue("go again", startMs = 2_000))
        session.putTranslation("say again", "再说一次")
        session.putTranslation("go again", "再说一遍")

        assertEquals("go again", session.translatedCueContaining("again")?.original)
    }

    @Test
    fun ignoresOversizedActiveWindow() {
        val session = CaptionSession()
        val longer = cue("and then we should go now", startMs = 1_000)
        val shorter = cue("we should go now", startMs = 2_000)
        session.observeCue(longer)
        session.observeCue(shorter)
        session.putTranslation(longer.text, "然后我们应该现在走")
        session.putTranslation(shorter.text, "我们应该现在走")

        val oversized = (1..9).map { cue("filler $it go now", startMs = 10_000L + it) }
        session.updateActiveCues(oversized + longer)

        // The oversized list is not a window, so the tightest match wins.
        assertEquals(shorter.text, session.translatedCueContaining("go now")?.original)
    }

    private fun cue(text: String, startMs: Long): CaptionCue =
        CaptionCue(
            videoId = "",
            startMs = startMs,
            endMs = startMs + 1_000,
            text = text,
        )
}

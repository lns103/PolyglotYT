package icu.nullptr.polyglot.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLineMatcherTest {
    @Test
    fun ordersLinesByTheirPositionInTheCue() {
        val cue = "Oh, he wants to know if his roommate can come for dinner with us."

        val ordered = CaptionLineMatcher.orderedLineIndexes(
            cue,
            listOf("come for dinner with us.", "Oh, he wants to know if his roommate can"),
        )

        assertEquals(listOf(1, 0), ordered)
    }

    @Test
    fun rejectsLinesThatDoNotCoverTheWholeCue() {
        val cue = "Oh, he wants to know if his roommate can come for dinner with us."

        assertNull(
            CaptionLineMatcher.orderedLineIndexes(
                cue,
                listOf("Oh, he wants to know if his roommate can"),
            ),
        )
    }

    @Test
    fun rejectsUnrelatedLines() {
        val cue = "Oh, he wants to know if his roommate can come for dinner with us."

        assertNull(
            CaptionLineMatcher.orderedLineIndexes(
                cue,
                listOf("completely different", "text"),
            ),
        )
    }

    @Test
    fun onlyMatchesWholeWords() {
        assertFalse(CaptionLineMatcher.isLineOf("we should go now", "should g"))
        assertTrue(CaptionLineMatcher.isLineOf("we should go now", "should go"))
        assertTrue(CaptionLineMatcher.isLineOf("we should go now", "we should"))
        assertTrue(CaptionLineMatcher.isLineOf("we should go now", "go now"))
        assertFalse(CaptionLineMatcher.isLineOf("we should go now", "we should go now"))
    }

    @Test
    fun detectsTheLastLine() {
        val cue = "Oh, he wants to know if his roommate can come for dinner with us."

        assertTrue(CaptionLineMatcher.isEndOf(cue, "come for dinner with us."))
        assertTrue(CaptionLineMatcher.isEndOf(cue, cue))
        assertFalse(CaptionLineMatcher.isEndOf(cue, "Oh, he wants to know if his roommate can"))
        assertFalse(CaptionLineMatcher.isEndOf(cue, "for dinner"))
    }
}

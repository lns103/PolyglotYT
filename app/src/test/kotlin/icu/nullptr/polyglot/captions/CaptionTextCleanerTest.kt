package icu.nullptr.polyglot.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionTextCleanerTest {

    @Test
    fun removesSdhTagLine() {
        assertEquals("", CaptionTextCleaner.stripSdhTags("[Music]"))
        assertEquals("", CaptionTextCleaner.stripSdhTags("  [掌声]  "))
    }

    @Test
    fun removesInlineTags() {
        assertEquals(
            "Hello everyone",
            CaptionTextCleaner.stripSdhTags("[Music] Hello [applause] everyone"),
        )
    }

    @Test
    fun removesMultipleAndMultilineTags() {
        assertEquals(
            "First line Second line",
            CaptionTextCleaner.stripSdhTags("[music]\nFirst line\n[laughs] Second line [door slams]"),
        )
    }

    @Test
    fun keepsUnclosedBrackets() {
        assertEquals("Hello [music", CaptionTextCleaner.stripSdhTags("Hello [music"))
    }

    @Test
    fun keepsTextWithoutTags() {
        assertEquals("Just a sentence", CaptionTextCleaner.stripSdhTags("  Just   a sentence "))
    }
}

package com.example.pcoverlaycontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {

    @Test
    fun matchesWholeEnglishWordIgnoringCase() {
        assertTrue(matchesTarget("Leave", "Leave"))
        assertTrue(matchesTarget("leave meeting", "Leave"))
        assertTrue(matchesTarget("End Meeting for All", "End"))
    }

    @Test
    fun doesNotMatchSubstringOfAnotherWord() {
        assertFalse(matchesTarget("Calendar", "End"))
        assertFalse(matchesTarget("Send", "End"))
        assertFalse(matchesTarget("Attendees", "End"))
        assertFalse(matchesTarget("Pending", "End"))
    }

    @Test
    fun matchesThaiBySubstring() {
        assertTrue(matchesTarget("ออกจากการประชุม", "ออกจากการประชุม"))
        assertTrue(matchesTarget("กดเพื่อออกจากการประชุมนี้", "ออกจากการประชุม"))
    }

    @Test
    fun handlesNullAndBlank() {
        assertFalse(matchesTarget(null, "Leave"))
        assertFalse(matchesTarget("  ", "Leave"))
        assertFalse(matchesTarget("Leave", ""))
    }
}

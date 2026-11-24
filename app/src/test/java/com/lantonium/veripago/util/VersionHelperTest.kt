package com.lantonium.veripago.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VersionHelperTest {
    @Test
    fun `build labels progress through Greek then Latin alphabets before deities`() {
        assertEquals("alfa", VersionHelper.getBuildLabel(1))
        assertEquals("omega", VersionHelper.getBuildLabel(24))
        assertEquals("a", VersionHelper.getBuildLabel(25))
        assertEquals("z", VersionHelper.getBuildLabel(50))
        assertEquals("Zeus", VersionHelper.getBuildLabel(51))
        assertEquals("Dionysus", VersionHelper.getBuildLabel(62))
        assertEquals("Jupiter", VersionHelper.getBuildLabel(63))
        assertEquals("Janus", VersionHelper.getBuildLabel(74))
        assertEquals("Zeus-2", VersionHelper.getBuildLabel(75))
    }
}

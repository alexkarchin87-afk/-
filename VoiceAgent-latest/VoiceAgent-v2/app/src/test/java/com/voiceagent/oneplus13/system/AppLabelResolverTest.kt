package com.voiceagent.oneplus13.system
import org.junit.Assert.*
import org.junit.Test
class AppLabelResolverTest {
    @Test fun exactLabelWinsButAmbiguousPartialDoesNotLaunchRandomApp() {
        val apps=listOf(LaunchableApp("one","Telegram"),LaunchableApp("two","Telegram X"))
        assertEquals("one",resolveAppLabel(" telegram ",apps));assertNull(resolveAppLabel("Tele",apps))
    }
    @Test fun duplicateActivitiesInOnePackageAreNotAmbiguous() {
        assertEquals("one",resolveAppLabel("Maps",listOf(LaunchableApp("one","Maps"),LaunchableApp("one","Maps"))))
        assertNull(resolveAppLabel(" ",emptyList()));assertNull(resolveAppLabel("missing",emptyList()))
    }
}

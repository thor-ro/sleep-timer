package com.tlr.sleeptimer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers parsing of ENABLED_ACCESSIBILITY_SERVICES. This is the check that decides whether the
 * app shows "start timer" or "enable accessibility", so a false negative makes the app appear
 * broken to a user who has already switched the service on.
 */
class AccessibilityServiceDetectionTest {

    private val pkg = "com.tlr.sleeptimer"
    private val cls = "com.tlr.sleeptimer.TimerService"

    private fun enabled(setting: String?) =
        AccessibilityServiceDetection.isServiceEnabled(setting, pkg, cls)

    @Test
    fun `short form entry is recognised`() {
        assertTrue(enabled("com.tlr.sleeptimer/.TimerService"))
    }

    @Test
    fun `long form entry is recognised`() {
        assertTrue(enabled("com.tlr.sleeptimer/com.tlr.sleeptimer.TimerService"))
    }

    @Test
    fun `entry is found among several other enabled services`() {
        assertTrue(
            enabled(
                "com.google.android.marvin.talkback/.TalkBackService:" +
                    "com.tlr.sleeptimer/.TimerService:" +
                    "com.other.app/com.other.app.SomeService"
            )
        )
    }

    @Test
    fun `entry is found in first and last position`() {
        assertTrue(enabled("com.tlr.sleeptimer/.TimerService:com.other/.S"))
        assertTrue(enabled("com.other/.S:com.tlr.sleeptimer/.TimerService"))
    }

    @Test
    fun `null and blank settings mean not enabled`() {
        assertFalse(enabled(null))
        assertFalse(enabled(""))
        assertFalse(enabled("   "))
    }

    @Test
    fun `a different app is not a match`() {
        assertFalse(enabled("com.google.android.marvin.talkback/.TalkBackService"))
    }

    @Test
    fun `a package that merely contains ours as a prefix is not a match`() {
        // Regression test for the original implementation, which used String.contains and so
        // reported "enabled" for any component name containing ours as a substring.
        assertFalse(enabled("com.tlr.sleeptimer2/.TimerService"))
        assertFalse(enabled("com.evil.com.tlr.sleeptimer/.TimerService"))
    }

    @Test
    fun `a different service in our own package is not a match`() {
        assertFalse(enabled("com.tlr.sleeptimer/.SomeOtherService"))
    }

    @Test
    fun `surrounding whitespace around an entry is tolerated`() {
        assertTrue(enabled("com.other/.S : com.tlr.sleeptimer/.TimerService "))
    }
}

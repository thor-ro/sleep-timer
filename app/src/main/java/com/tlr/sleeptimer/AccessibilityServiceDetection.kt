package com.tlr.sleeptimer

/**
 * Pure parsing of the `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` value.
 *
 * That setting is a colon-separated list of flattened ComponentNames, written in either the
 * short form (`com.example.app/.MyService`) or the long form
 * (`com.example.app/com.example.app.MyService`) depending on how the entry was created. It is
 * the raw record of what the *user* switched on, which is why it is checked alongside
 * [android.view.accessibility.AccessibilityManager.getEnabledAccessibilityServiceList]: that
 * API only reports services the system has actually bound, and on some OEM builds a service
 * that requests no event types never shows up there even though it is enabled and working.
 *
 * Kept free of Android types so it can be covered by plain JVM unit tests.
 */
object AccessibilityServiceDetection {

    /**
     * True when [packageName]/[className] appears in [settingValue] as a whole entry.
     *
     * Entries are compared exactly, never as substrings: `com.tlr.sleeptimer2/.TimerService`
     * must not count as a match for `com.tlr.sleeptimer/.TimerService`.
     */
    fun isServiceEnabled(settingValue: String?, packageName: String, className: String): Boolean {
        if (settingValue.isNullOrBlank()) return false

        val longForm = "$packageName/$className"
        val shortForm = if (className.startsWith("$packageName.")) {
            "$packageName/${className.removePrefix(packageName)}"
        } else {
            longForm
        }

        return settingValue.split(':').any { entry ->
            val trimmed = entry.trim()
            // Component names are case-sensitive on Android; compare them as written.
            trimmed == longForm || trimmed == shortForm
        }
    }
}

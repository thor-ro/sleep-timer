package com.tlr.sleeptimer

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: verifies the manifest actually declares what the runtime
 * permission flow / alarm scheduling in [MainActivity] and [TimerService] depend on.
 */
@RunWith(AndroidJUnit4::class)
class ManifestPermissionsInstrumentedTest {

    @Test
    fun postNotificationsPermissionIsDeclared() {
        assertTrue(
            "POST_NOTIFICATIONS must be declared so MainActivity can request it at runtime",
            declaredPermissions().contains(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    @Test
    fun useExactAlarmPermissionIsDeclared() {
        assertTrue(
            "USE_EXACT_ALARM must be declared so TimerService can schedule an exact alarm",
            declaredPermissions().contains("android.permission.USE_EXACT_ALARM")
        )
    }

    @Test
    fun obsoleteForegroundServiceAndWakeLockPermissionsAreGone() {
        val declared = declaredPermissions()
        assertTrue(!declared.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(!declared.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(!declared.contains(Manifest.permission.WAKE_LOCK))
    }

    private fun declaredPermissions(): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        return packageInfo.requestedPermissions?.toList().orEmpty()
    }
}

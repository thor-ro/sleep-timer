package com.tlr.sleeptimer

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 force-enables edge-to-edge for apps targeting SDK 35+, with no opt-out.
        // Declaring it here makes that explicit; the content column below consumes the
        // resulting insets while the background image deliberately stays full-bleed.
        enableEdgeToEdge()

        // Populate the shared state flow from persisted prefs immediately, so the very first
        // frame is correct even if this activity is opened before the accessibility service
        // has connected (which is also where restore() runs).
        SleepTimerRepository.restore(applicationContext)

        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* Denied is fine: the timer still works, just without a notification. */ }
            LaunchedEffect(Unit) {
                val alreadyGranted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!alreadyGranted) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        // The user may have just come back from the accessibility settings screen.
                        accessibilityEnabled = isAccessibilityServiceEnabled()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val timerState by SleepTimerRepository.state.collectAsStateWithLifecycle()

            MaterialTheme(colorScheme = darkColorScheme(background = colorResource(id = R.color.night_background))) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SleepTimerScreen(
                        timerState = timerState,
                        isAccessibilityServiceEnabled = accessibilityEnabled,
                        onStartTimer = { minutes ->
                            val intent = Intent(this, TimerService::class.java).apply {
                                action = TimerService.ACTION_START
                                putExtra(TimerService.EXTRA_DURATION_MINUTES, minutes)
                            }
                            startService(intent)
                        },
                        onAbortTimer = {
                            val intent = Intent(this, TimerService::class.java).apply {
                                action = TimerService.ACTION_ABORT
                            }
                            startService(intent)
                        },
                        onRequestEnableAccessibility = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    /**
     * Whether the user has switched this app's accessibility service on.
     *
     * Two independent sources are consulted, because neither alone is reliable everywhere:
     *
     *  - [android.view.accessibility.AccessibilityManager.getEnabledAccessibilityServiceList]
     *    is the semantically correct API, but it only reports services the system has already
     *    *bound*. That misses the window right after the user flips the switch, and on some
     *    OEM builds (Xiaomi HyperOS in particular) a service declaring no accessibility event
     *    types never appears in it at all -- which this service deliberately does, since it
     *    only needs performGlobalAction and no event delivery.
     *  - ENABLED_ACCESSIBILITY_SERVICES is the raw record of what the user switched on, so it
     *    is correct even when the service is not (yet) bound. It is parsed with exact
     *    component matching by [AccessibilityServiceDetection]; the naive `contains` check
     *    this code used originally would also match another app whose component name merely
     *    contains ours as a substring.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, TimerService::class.java)

        val boundAccordingToManager = runCatching {
            (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info -> ComponentName.unflattenFromString(info.id) == expected }
        }.getOrDefault(false)
        if (boundAccordingToManager) return true

        val setting = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        return AccessibilityServiceDetection.isServiceEnabled(
            settingValue = setting,
            packageName = packageName,
            className = TimerService::class.java.name
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

@Composable
fun SleepTimerScreen(
    timerState: TimerState,
    isAccessibilityServiceEnabled: Boolean,
    onStartTimer: (Int) -> Unit,
    onAbortTimer: () -> Unit,
    onRequestEnableAccessibility: () -> Unit
) {
    // rememberSaveable, not remember: a rotation must not silently reset the user's choice.
    var sliderValue by rememberSaveable { mutableFloatStateOf(30f) }

    val moonlightCream = colorResource(id = R.color.moonlight_cream)
    val softTeal = colorResource(id = R.color.soft_teal)
    val dustyRose = colorResource(id = R.color.dusty_rose)
    val glassBackground = Color.White.copy(alpha = 0.15f)

    // Ticks the visible countdown at 1 Hz, and only while the lifecycle is at least STARTED —
    // there is nothing to tick while the activity is stopped, and no timer to tick while idle.
    var nowElapsedRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(timerState, lifecycleOwner) {
        if (timerState is TimerState.Running) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    nowElapsedRealtime = SystemClock.elapsedRealtime()
                    delay(1000)
                }
            }
        }
    }

    val isTimerRunning = timerState is TimerState.Running
    val remainingMillis = TimerStateLogic.remainingMillis(timerState, nowElapsedRealtime)

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glassBackground, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isTimerRunning) {
                        val minutes = (remainingMillis / 1000) / 60
                        val seconds = (remainingMillis / 1000) % 60
                        val locale = LocalLocale.current.platformLocale
                        // "12:30" alone reads as bare digits. Note this is deliberately NOT a
                        // liveRegion: the value changes every second, and a polite live region
                        // would make TalkBack interrupt itself once a second for up to an hour.
                        val remainingDescription = stringResource(
                            R.string.timer_remaining_content_description,
                            pluralStringResource(R.plurals.minutes, minutes.toInt(), minutes.toInt()),
                            pluralStringResource(R.plurals.seconds, seconds.toInt(), seconds.toInt())
                        )
                        Text(
                            text = String.format(locale, "%02d:%02d", minutes, seconds),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Light,
                            color = moonlightCream,
                            modifier = Modifier.semantics {
                                contentDescription = remainingDescription
                            }
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.duration_minutes_label, sliderValue.toInt()),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Light,
                            color = moonlightCream
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        val sliderStateDescription = pluralStringResource(
                            R.plurals.minutes,
                            sliderValue.toInt(),
                            sliderValue.toInt()
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 1f..60f,
                            // Without this the slider announces a bare percentage.
                            modifier = Modifier.semantics {
                                stateDescription = sliderStateDescription
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = softTeal,
                                activeTrackColor = softTeal,
                                inactiveTrackColor = moonlightCream.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Without accessibility access the app cannot lock the screen, so say so rather
            // than letting the start button silently bounce the user into system settings.
            // The running case is the more urgent one: a timer is already counting down that
            // will pause media but fail to lock, so it is called out in the warning colour.
            if (!isAccessibilityServiceEnabled) {
                Text(
                    text = stringResource(
                        if (isTimerRunning) R.string.accessibility_lost_warning
                        else R.string.accessibility_required_hint
                    ),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = if (isTimerRunning) dustyRose else moonlightCream.copy(alpha = 0.75f)
                )
                if (isTimerRunning) {
                    // The primary button below stays ABORT while a timer runs, so the route to
                    // the accessibility settings gets its own control rather than displacing it.
                    TextButton(onClick = onRequestEnableAccessibility) {
                        Text(
                            text = stringResource(R.string.enable_accessibility_button),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = softTeal
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = {
                    if (isTimerRunning) {
                        onAbortTimer()
                    } else if (isAccessibilityServiceEnabled) {
                        onStartTimer(sliderValue.toInt())
                    } else {
                        onRequestEnableAccessibility()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTimerRunning) dustyRose else softTeal,
                    contentColor = colorResource(id = R.color.button_label)
                )
            ) {
                Text(
                    text = stringResource(
                        when {
                            isTimerRunning -> R.string.abort_timer_button
                            !isAccessibilityServiceEnabled -> R.string.enable_accessibility_button
                            else -> R.string.start_timer_button
                        }
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SleepTimerScreenIdlePreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SleepTimerScreen(
            timerState = TimerState.Idle,
            isAccessibilityServiceEnabled = true,
            onStartTimer = {},
            onAbortTimer = {},
            onRequestEnableAccessibility = {}
        )
    }
}

@Preview(showBackground = true, name = "Running")
@Composable
private fun SleepTimerScreenRunningPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SleepTimerScreen(
            timerState = TimerState.Running(SystemClock.elapsedRealtime() + 5 * 60_000L),
            isAccessibilityServiceEnabled = true,
            onStartTimer = {},
            onAbortTimer = {},
            onRequestEnableAccessibility = {}
        )
    }
}

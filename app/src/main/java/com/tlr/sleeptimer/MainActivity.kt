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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, TimerService::class.java)
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> ComponentName.unflattenFromString(info.id) == expected }
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
    var sliderValue by remember { mutableFloatStateOf(30f) }

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
                        Text(
                            text = String.format(locale, "%02d:%02d", minutes, seconds),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Light,
                            color = moonlightCream
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.duration_minutes_label, sliderValue.toInt()),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Light,
                            color = moonlightCream
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 1f..60f,
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
            if (!isTimerRunning && !isAccessibilityServiceEnabled) {
                Text(
                    text = stringResource(R.string.accessibility_required_hint),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = moonlightCream.copy(alpha = 0.75f)
                )
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

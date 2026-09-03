package com.tlr.sleeptimer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SleepTimerScreen(
                        onStartTimer = { mins ->
                            val intent = Intent(this, TimerService::class.java).apply {
                                action = TimerService.ACTION_START
                                putExtra("duration_minutes", mins)
                            }
                            try {
                                // Versuche den Service zu starten
                                startForegroundService(intent)
                            } catch (e: Exception) {
                                // Falls das System den Start verweigert (z.B. wegen fehlender Permission)
                                // leiten wir den User zur Sicherheit nochmal in das Menü
                                openAccessibilitySettings()
                            }
                        },
                        onAbortTimer = {
                            val intent = Intent(this, TimerService::class.java).apply {
                                action = TimerService.ACTION_ABORT
                            }
                            startService(intent)
                        }
                    )
                }
            }
        }
    }

    internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, TimerService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedComponentName.flattenToString())
    }

    internal fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}

@Composable
fun SleepTimerScreen(
    onStartTimer: (Int) -> Unit,
    onAbortTimer: () -> Unit
) {
    val context = LocalContext.current
    var isTimerRunning by remember { mutableStateOf(false) }
    var remainingMillis by remember { mutableLongStateOf(0L) }
    var sliderValue by remember { mutableFloatStateOf(30f) }

    val moonlightCream = Color(0xFFFDF5E6)
    val softTeal = Color(0xFFAEECEF)
    val dustyRose = Color(0xFFF08080)
    val glassBackground = Color(0xFFFFFFFF).copy(alpha = 0.15f)

    // Diese Schleife prüft regelmäßig die statische Instanz des Services
    LaunchedEffect(Unit) {
        while (true) {
            val service = TimerService.instance
            if (service != null) {
                isTimerRunning = true
                val end = service.endTimeMillis
                val now = SystemClock.elapsedRealtime()
                remainingMillis = (end - now).coerceAtLeast(0L)
            } else {
                isTimerRunning = false
                remainingMillis = 0L
            }
            delay(500)
        }
    }

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
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Light,
                            color = moonlightCream
                        )
                    } else {
                        Text(
                            text = "${sliderValue.toInt()} min",
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

            Button(
                // Im Button onClick in MainActivity.kt
                onClick = {
                    val activity = context as? MainActivity
                    if (isTimerRunning) {
                        onAbortTimer()
                        isTimerRunning = false // Sofortiges UI Feedback
                    } else {
                        if (activity?.isAccessibilityServiceEnabled(context) == true) {
                            onStartTimer(sliderValue.toInt())
                            // Wir erzwingen hier kein isTimerRunning = true,
                            // da die LaunchedEffect-Schleife das übernimmt, sobald der Service lebt.
                        } else {
                            activity?.openAccessibilitySettings()
                        }
                    }
                }       ,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTimerRunning) dustyRose else softTeal,
                    contentColor = Color(0xFF1A1A1A)
                )
            ) {
                Text(
                    text = if (isTimerRunning) "ABORT TIMER" else "START SLEEP TIMER",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
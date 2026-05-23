package io.github.tffinder.mirrocast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MirrocastTheme {
                StandbyScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    nativeVersion = runCatching { NativeBridge.version() }
                        .getOrDefault("(native unavailable)")
                )
            }
        }
    }
}

@Composable
private fun MirrocastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0E1117),
            surface = Color(0xFF0E1117),
            primary = Color(0xFF7AA2F7),
            onBackground = Color(0xFFEAEAEA),
            onSurface = Color(0xFFEAEAEA),
        ),
        content = content,
    )
}

@Composable
private fun StandbyScreen(versionName: String, nativeVersion: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Mirrocast",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 80.sp,
                )
                Text(
                    text = "Android TV 投屏接收端",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                )
                Text(
                    text = "v$versionName · native=$nativeVersion",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    fontSize = 18.sp,
                )
            }
        }
    }
}

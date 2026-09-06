package io.github.tffinder.mirrocast

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import io.github.tffinder.mirrocast.service.MirrorService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动接收服务，保证设备在后台也能被 DLNA 发现
        startService(Intent(this, MirrorService::class.java))
        setContent {
            MirrocastTheme {
                RootScreen()
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
private fun RootScreen() {
    val state by MediaPlayback.uiState.collectAsState()

    // 一旦开始过投屏，就一直停留在播放画面，避免切换视频时闪回待机首页
    var hasPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.active) {
        if (state.active) hasPlayed = true
    }

    if (hasPlayed) {
        PlayerScreen()
    } else {
        StandbyScreen(
            versionName = BuildConfig.VERSION_NAME,
            nativeVersion = runCatching { NativeBridge.version() }
                .getOrDefault("(native unavailable)")
        )
    }
}

/** 投屏播放画面：纯黑背景 + 全屏 PlayerView，不带进度条/水印。 */
@Composable
private fun PlayerScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // 默认不显示进度条
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { view ->
                view.player = MediaPlayback.currentPlayer()
            }
        )
    }
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
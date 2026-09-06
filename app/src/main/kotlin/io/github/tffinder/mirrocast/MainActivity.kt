package io.github.tffinder.mirrocast

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import io.github.tffinder.mirrocast.service.MirrorService
import kotlinx.coroutines.launch

/**
 * 主界面：待机首页与投屏播放画面均来自 [activity_main.xml]，
 * 这里只负责按投屏状态切换两套 XML 界面并响应遥控器按键。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var standbyRoot: View
    private lateinit var playerView: PlayerView

    /** 一旦开始过投屏，就一直停留在播放画面，避免切换视频时闪回待机首页。 */
    private var hasPlayed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动接收服务，保证设备在后台也能被 DLNA 发现
        startService(Intent(this, MirrorService::class.java))

        setContentView(R.layout.activity_main)
        standbyRoot = findViewById(R.id.standby_root)
        playerView = findViewById<PlayerView>(R.id.player_view).apply {
            useController = false // 默认不显示进度条
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }

        findViewById<TextView>(R.id.text_meta).text = getString(
            R.string.home_version_format,
            BuildConfig.VERSION_NAME,
            runCatching { NativeBridge.version() }
                .getOrDefault("(native unavailable)")
        )

        observeUiState()
    }

    /** 监听投屏状态，在“待机首页”和“播放画面”之间切换。 */
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaPlayback.uiState.collect { state ->
                    if (state.active) hasPlayed = true
                    if (hasPlayed) showPlayerScreen() else showStandbyScreen()
                }
            }
        }
    }

    private fun showStandbyScreen() {
        if (standbyRoot.visibility != View.VISIBLE) standbyRoot.visibility = View.VISIBLE
        if (playerView.visibility != View.GONE) {
            playerView.player = null
            playerView.visibility = View.GONE
        }
    }

    private fun showPlayerScreen() {
        if (standbyRoot.visibility != View.GONE) standbyRoot.visibility = View.GONE
        if (playerView.visibility != View.VISIBLE) playerView.visibility = View.VISIBLE
        playerView.player = MediaPlayback.currentPlayer()
    }

    /**
     * 遥控器方向键（下）：
     * 把当前视频进度拨到片尾前 1s 并继续播放，实现“快速结束当前视频 → 自动播下一个”。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            event.action == KeyEvent.ACTION_DOWN &&
            MediaPlayback.quickFinishToNext()
        ) {
            Log.i(TAG, "DPAD_DOWN handled: jump to end and play next")
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

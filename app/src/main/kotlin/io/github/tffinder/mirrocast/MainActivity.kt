package io.github.tffinder.mirrocast

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
        /** 播放画面状态保存用的 key。 */
        private const val KEY_ON_PLAYER_SCREEN = "on_player_screen"
    }

    private lateinit var standbyRoot: View
    private lateinit var playerView: PlayerView

    /**
     * 是否停留在“投屏播放画面”：
     * 开始过投屏即置 true，直到用户按“返回”回到等待连接；
     * 进程重建时经 onSaveInstanceState 恢复，避免重建后误回待机页。
     */
    private var onPlayerScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onPlayerScreen = savedInstanceState?.getBoolean(KEY_ON_PLAYER_SCREEN, false) ?: false
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackPressed()
        })

        observeUiState()
    }

    /**
     * 返回键两级处理：
     * 1) 播放中：停止当前投屏播放，回到“等待连接”首页（不退出）；
     * 2) 等待连接中：退出应用并停掉接收服务，做到不后台运行。
     */
    private fun handleBackPressed() {
        if (onPlayerScreen) {
            Log.i(TAG, "BACK: stop playback, back to standby")
            onPlayerScreen = false
            MediaPlayback.stop()
            showStandbyScreen()
        } else {
            // 退出：只 finish()，停服务统一交给 onDestroy(isFinishing)，避免重复逻辑
            Log.i(TAG, "BACK: exit app")
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ON_PLAYER_SCREEN, onPlayerScreen)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 界面被真正关闭时（非配置变更/进程后台），停掉接收服务，做到“不后台运行”
        if (isFinishing) {
            stopService(Intent(this, MirrorService::class.java))
        }
    }

    /** 监听投屏状态，在“待机首页”和“播放画面”之间切换。 */
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaPlayback.uiState.collect { state ->
                    if (state.active) onPlayerScreen = true
                    if (onPlayerScreen) showPlayerScreen() else showStandbyScreen()
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

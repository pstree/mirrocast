package io.github.tffinder.mirrocast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局媒体播放单例。
 *
 * 由 MirrorService 驱动（DLNA 回调 → load/play/pause/stop/seek...），
 * MainActivity 通过 [uiState] 感知当前是否有投屏会话并挂载 PlayerView。
 * 所有 ExoPlayer 操作统一 post 到主线程，保证线程安全。
 */
object MediaPlayback {

    private const val TAG = "MediaPlayback"
    private val handler = Handler(Looper.getMainLooper())

    data class UiState(
        val active: Boolean = false,
        val title: String = "",
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val buffering: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var player: ExoPlayer? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(playing = isPlaying, buffering = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.value = when (playbackState) {
                Player.STATE_BUFFERING -> _uiState.value.copy(buffering = true)
                Player.STATE_READY, Player.STATE_IDLE, Player.STATE_ENDED ->
                    _uiState.value.copy(buffering = false)
                else -> _uiState.value
            }
        }
    }

    /** 当前播放器实例（可能为 null），供 PlayerView 挂载。 */
    fun currentPlayer(): ExoPlayer? = player

    /**
     * DLNA SetAVTransportURI：加载媒体源（暂不自动播放，等 Play 指令）。
     * @param uri 当前 URI（已解码）
     * @param playlist 播放列表（可能只含单个 uri）
     * @param startIndex 起始下标
     * @param title 用于 UI 显示
     */
    fun load(context: Context, uri: String, playlist: List<String>, startIndex: Int, title: String) {
        handler.post {
            val p = ensurePlayer(context)
            val items = if (playlist.size > 1) playlist.map { MediaItem.fromUri(it) }
            else listOf(MediaItem.fromUri(uri))
            p.clearMediaItems()
            p.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
            p.prepare()
            _uiState.value = UiState(active = true, title = title)
            Log.i(TAG, "Loaded ${items.size} item(s), start at $startIndex")
        }
    }

    fun play() {
        handler.post {
            player?.let {
                it.prepare()
                it.play()
                Log.i(TAG, "play()")
            }
        }
    }

    fun pause() {
        handler.post {
            player?.pause()
            _uiState.value = _uiState.value.copy(playing = false)
        }
    }

    fun stop() {
        handler.post {
            player?.stop()
            player?.clearMediaItems()
            _uiState.value = UiState()
            Log.i(TAG, "stop() -> idle")
        }
    }

    fun seekTo(positionMs: Long) {
        handler.post { player?.seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun setVolume(volume: Int) {
        handler.post { player?.volume = volume.coerceIn(0, 100) / 100f }
    }

    fun setSpeed(speed: Float) {
        handler.post { player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4f)) }
    }

    /** 当前播放进度，供 DLNA GetPositionInfo 反馈。 */
    fun position(): Long = player?.currentPosition ?: 0L
    fun duration(): Long = player?.duration?.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L

    /** 服务销毁时释放播放器。 */
    fun release() {
        handler.post {
            player?.removeListener(playerListener)
            player?.release()
            player = null
            _uiState.value = UiState()
        }
    }

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        return ExoPlayer.Builder(context).build().also {
            it.addListener(playerListener)
            player = it
        }
    }
}
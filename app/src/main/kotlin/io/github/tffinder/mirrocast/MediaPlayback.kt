package io.github.tffinder.mirrocast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局媒体播放单例。
 *
 * 由 MirrorService 驱动（DLNA 回调 → load/play/pause/stop/seek...），
 * MainActivity 通过 [uiState] 感知当前是否有投屏会话并挂载 PlayerView。
 * 所有 ExoPlayer 操作统一 post 到主线程，保证线程安全。
 *
 * 清晰度策略（默认 1080p，但支持 2K/4K）：
 * - 每个新片源默认把多码率流限制在 FHD(1080p) 级以内选择；
 * - 若源只提供 2K/4K 轨道（例如单文件 4K），1080p 约束下无视频可选中时，
 *   自动放开档位上限，保证 2K/4K 也能正常播放；
 * - 手机端切清晰度本质是重新 SetAVTransportURI 推流，加载中若原视频正在播放
 *   会自动续播，实现无缝切换。
 */
object MediaPlayback {

    private const val TAG = "MediaPlayback"
    private val handler = Handler(Looper.getMainLooper())

    /** 按下遥控器“下键”时，把进度拨到片尾前这段距离，让视频快速自然播完。 */
    private const val FINISH_SEEK_BACK_MS = 1000L

    /** 默认档位上限：长边不超过 FHD（1080p 级），同时兼容竖屏 1080×1920。 */
    private const val DEFAULT_MAX_LONG_SIDE = 1920
    /** 自动放开后的档位上限：不限，交给设备/源端能力（可到 2K/4K）。 */
    private const val LONG_SIDE_UNLIMITED = Int.MAX_VALUE

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

    /** 当前生效的清晰度上限（长边像素）。 */
    private var maxLongSide = DEFAULT_MAX_LONG_SIDE
    private var trackSelector: DefaultTrackSelector? = null

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

        override fun onTracksChanged(tracks: Tracks) {
            maybeEscalateResolutionIfNeeded()
        }
    }

    /** 当前播放器实例（可能为 null），供 PlayerView 挂载。 */
    fun currentPlayer(): ExoPlayer? = player

    /**
     * DLNA SetAVTransportURI：加载媒体源。
     * 首次投屏不自动播放（等 DLNA Play 指令）；
     * 若加载前正处于播放中（手机端切清晰度会不清停地重推 URI），加载后自动续播，实现无缝切换。
     * 每个新片源都会先回到“默认 1080p”档位，再由播放器判断是否需放开到 2K/4K。
     * @param uri 当前 URI（已解码）
     * @param playlist 播放列表（可能只含单个 uri）
     * @param startIndex 起始下标
     * @param title 用于 UI 显示
     */
    fun load(context: Context, uri: String, playlist: List<String>, startIndex: Int, title: String) {
        handler.post {
            val p = ensurePlayer(context)
            val resumePlaying = _uiState.value.active && _uiState.value.playing
            resetResolutionCap()

            val items = if (playlist.size > 1) playlist.map { MediaItem.fromUri(it) }
            else listOf(MediaItem.fromUri(uri))
            p.clearMediaItems()
            p.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), 0L)
            p.prepare()

            if (resumePlaying) {
                p.play()
                _uiState.value = UiState(active = true, title = title, playing = true)
            } else {
                _uiState.value = UiState(active = true, title = title)
            }
            Log.i(TAG, "Loaded ${items.size} item(s), start at $startIndex (resumePlaying=$resumePlaying)")
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

    /**
     * 快速收尾：把当前视频进度拨到“片尾前 1s”并继续播放，
     * 让它自然播完后自动接续播放列表中的下一段（DLNA 连播时用于快速跳过）。
     * @return true 表示已执行（存在可跳转的媒体时长），false 表示当前无可跳过内容
     */
    fun quickFinishToNext(): Boolean {
        val durationMs = duration()
        if (durationMs <= 0L) return false
        handler.post {
            player?.let { p ->
                p.seekTo((durationMs - FINISH_SEEK_BACK_MS).coerceAtLeast(0L))
                p.play()
                Log.i(TAG, "quickFinishToNext: seek to end-${FINISH_SEEK_BACK_MS}ms")
            }
        }
        return true
    }

    /** 服务销毁时释放播放器。 */
    fun release() {
        handler.post {
            player?.removeListener(playerListener)
            player?.release()
            player = null
            trackSelector = null
            _uiState.value = UiState()
        }
    }

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val selector = DefaultTrackSelector(context)
        trackSelector = selector
        applyResolutionCap()
        return ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .build()
            .also {
                it.addListener(playerListener)
                player = it
            }
    }

    /** 每个新片源先回到默认 1080p 档位。 */
    private fun resetResolutionCap() {
        if (maxLongSide == DEFAULT_MAX_LONG_SIDE) return
        maxLongSide = DEFAULT_MAX_LONG_SIDE
        applyResolutionCap()
    }

    /** 把当前 [maxLongSide] 应用到轨道选择器；调用后播放器会重新评估轨道。 */
    private fun applyResolutionCap() {
        trackSelector?.let { s ->
            s.setParameters(
                s.buildUponParameters()
                    .setMaxVideoSize(maxLongSide, maxLongSide)
                    .build()
            )
        }
    }

    /**
     * 兜底支持 2K/4K：当片源存在可支持解码的视频轨，但在“默认 1080p”档位下
     * 没有一条视频轨被选中（比如只有 4K 单轨的源），就把档位放开到不限，
     * 保证 2K/4K 源也能正常出画面。
     */
    private fun maybeEscalateResolutionIfNeeded() {
        if (maxLongSide == LONG_SIDE_UNLIMITED) return
        val p = player ?: return
        val groups = p.currentTracks.groups
        if (groups.isEmpty()) return

        val videoAvailable = groups.any { g ->
            g.type == C.TRACK_TYPE_VIDEO &&
                (0 until g.length).any { idx -> g.isTrackSupported(idx) }
        }
        if (!videoAvailable) return
        val videoSelected = groups.any { g ->
            g.type == C.TRACK_TYPE_VIDEO && g.isSelected
        }
        if (videoSelected) return

        Log.i(TAG, "Video present but none fits default 1080p cap, relax to support 2K/4K")
        maxLongSide = LONG_SIDE_UNLIMITED
        applyResolutionCap()
    }
}

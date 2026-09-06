package io.github.tffinder.mirrocast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.tffinder.mirrocast.MediaPlayback
import io.github.tffinder.mirrocast.R
import io.github.tffinder.mirrocast.dlna.DlnaMediaRenderer
import io.github.tffinder.mirrocast.dlna.SsdpServer
import io.github.tffinder.mirrocast.dlna.UpnpHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 投屏接收后台服务
 * 托管 DLNA/UPnP 接收端，保持应用在后台持续响应投屏连接。
 * renderer 的 DLNA 控制回调驱动 [MediaPlayback] 播放，并向 DLNA 上报真实播放进度。
 */
class MirrorService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val CHANNEL_NAME = "投屏接收服务"
    }

    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var deviceUuid: String

    // 播放进度上报（让 DLNA GetPositionInfo 返回真实进度）
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MirrorService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 初始化设备 UUID
        deviceUuid = generateDeviceUuid()

        initDlnaServices()
    }

    private fun initDlnaServices() {
        val localIp = NetworkUtils.getLocalIpAddress(this) ?: "127.0.0.1"
        Log.i(TAG, "DLNA local IP: $localIp")

        // 创建 DLNA MediaRenderer
        dlnaRenderer = DlnaMediaRenderer()

        // 渲染器回调 → 驱动 ExoPlayer 播放
        dlnaRenderer.onSetUri = { uri, metadata ->
            Log.i(TAG, "DLNA SetURI: $uri")
            val state = dlnaRenderer.getState()
            MediaPlayback.load(
                context = this,
                uri = uri,
                playlist = state.playlist.ifEmpty { listOf(uri) },
                startIndex = state.currentIndex,
                title = extractTitle(metadata),
            )
        }
        dlnaRenderer.onPlay = {
            Log.i(TAG, "DLNA Play")
            startProgressReporting()
            MediaPlayback.play()
        }
        dlnaRenderer.onPause = {
            stopProgressReporting()
            MediaPlayback.pause()
        }
        dlnaRenderer.onStop = {
            stopProgressReporting()
            dlnaRenderer.setStopped()
            MediaPlayback.stop()
        }
        dlnaRenderer.onSeek = { position -> MediaPlayback.seekTo(position) }
        dlnaRenderer.onSpeedChanged = { speed -> MediaPlayback.setSpeed(speed) }
        dlnaRenderer.onQualityUriChanged = { uri -> Log.i(TAG, "DLNA Quality URI: $uri") }
        dlnaRenderer.onVolumeChanged = { volume -> MediaPlayback.setVolume(volume) }
        dlnaRenderer.onMuteChanged = { muted -> MediaPlayback.setVolume(if (muted) 0 else 50) }

        // 创建 UPnP HTTP 服务器
        upnpHttpServer = UpnpHttpServer(
            context = this,
            renderer = dlnaRenderer,
            deviceUuid = deviceUuid,
            deviceName = getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            modelName = Build.MODEL,
            localIp = localIp,
            port = 8080
        )

        // 创建 SSDP 服务器
        ssdpServer = SsdpServer(
            context = this,
            deviceUuid = deviceUuid,
            localIp = localIp,
            httpPort = 8080
        )
    }

    /** 播放开始后周期性上报进度/状态到 DLNA renderer。 */
    private fun startProgressReporting() {
        if (progressJob?.isActive == true) return
        progressJob = mainScope.launch {
            while (isActive) {
                dlnaRenderer.updatePosition(MediaPlayback.position(), MediaPlayback.duration())
                if (MediaPlayback.currentPlayer()?.isPlaying == true) {
                    dlnaRenderer.setPlaying()
                } else if (MediaPlayback.uiState.value.active) {
                    dlnaRenderer.setPaused()
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressReporting() {
        progressJob?.cancel()
        progressJob = null
        dlnaRenderer.updatePosition(0L, 0L)
    }

    private fun extractTitle(metadata: String): String {
        val titlePattern = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
        return titlePattern.find(metadata)?.groupValues?.getOrNull(1) ?: "DLNA 投屏"
    }

    private fun getDeviceName(): String {
        return listOf("Mirrocast", Build.MODEL, Build.MANUFACTURER)
            .filter { it.isNotBlank() && it != "unknown" }
            .distinct()
            .joinToString(" ")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MirrorService started")

        // 启动 DLNA/UPnP 服务
        upnpHttpServer.start()
        ssdpServer.start()

        Log.i(TAG, "Cast services started (DLNA/UPnP)")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MirrorService destroyed")

        stopProgressReporting()
        MediaPlayback.release()
        ssdpServer.stop()
        upnpHttpServer.stop()
        mainScope.cancel()
    }

    private fun generateDeviceUuid(): String {
        // 使用设备信息生成一致的 UUID
        val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
        return UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持投屏接收服务运行"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在等待 DLNA 投屏连接")
            .setSmallIcon(R.drawable.tv_banner)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
package io.github.tffinder.mirrocast.dlna

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * SSDP (Simple Service Discovery Protocol) 服务器
 * 用于 DLNA/UPnP 设备发现
 */
class SsdpServer(
    private val context: Context,
    private val deviceUuid: String,
    private val localIp: String,
    private val httpPort: Int = 8080
) {
    companion object {
        private const val TAG = "SsdpServer"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_SEARCH_PATTERN = "M-SEARCH"

        // UPnP Device Types
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val SERVICE_TYPE_AV = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SERVICE_TYPE_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val SERVICE_TYPE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }

    private var multicastSocket: MulticastSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (serverJob?.isActive == true) {
            Log.w(TAG, "SSDP server already running")
            return
        }

        serverJob = scope.launch {
            try {
                multicastSocket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true

                    // 加入多播组
                    val group = InetAddress.getByName(SSDP_ADDRESS)
                    networks().forEach { networkInterface ->
                        runCatching {
                            joinGroup(InetSocketAddress(group, SSDP_PORT), networkInterface)
                            Log.i(TAG, "Joined multicast group on ${networkInterface.name}")
                        }
                    }
                }

                Log.i(TAG, "SSDP server started on port $SSDP_PORT")

                val buffer = ByteArray(1024)
                while (multicastSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    if (message.contains(SSDP_SEARCH_PATTERN)) {
                        handleSearchRequest(message, packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSDP server error", e)
            }
        }

        // 启动定期广播
        startPeriodicNotify()
    }

    fun stop() {
        try {
            sendByebye()
            serverJob?.cancel()
            multicastSocket?.close()
            Log.i(TAG, "SSDP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SSDP server", e)
        }
    }

    private fun networks(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
    }.getOrDefault(emptyList())

    private fun handleSearchRequest(message: String, address: InetAddress, port: Int) {
        Log.d(TAG, "SSDP search from ${address.hostAddress}:$port")

        // 检查搜索目标
        val searchTarget = extractSearchTarget(message)

        val shouldRespond = when {
            searchTarget == null -> false
            searchTarget == "ssdp:all" -> true
            searchTarget == "upnp:rootdevice" -> true
            searchTarget.contains("MediaRenderer") -> true
            searchTarget.contains("AVTransport") -> true
            searchTarget.contains("RenderingControl") -> true
            searchTarget.contains("ConnectionManager") -> true
            searchTarget == "uuid:$deviceUuid" -> true
            else -> false
        }

        if (shouldRespond) {
            sendSearchResponse(address, port, searchTarget ?: "upnp:rootdevice")
        }
    }

    private fun extractSearchTarget(message: String): String? {
        val lines = message.split("\r\n")
        for (line in lines) {
            if (line.startsWith("ST:", ignoreCase = true)) {
                return line.substring(3).trim()
            }
        }
        return null
    }

    private fun sendSearchResponse(address: InetAddress, port: Int, searchTarget: String) {
        val location = "http://$localIp:$httpPort/device.xml"

        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("EXT:\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: Android/11 UPnP/1.0 Mirrocast/1.0\r\n")
            append("ST: $searchTarget\r\n")
            append("USN: uuid:$deviceUuid")

            // 根据 ST 添加不同的 USN
            when {
                searchTarget == "upnp:rootdevice" -> append("::upnp:rootdevice")
                searchTarget.contains("device:") -> append("::$searchTarget")
                searchTarget.contains("service:") -> append("::$searchTarget")
            }

            append("\r\n\r\n")
        }

        try {
            val socket = DatagramSocket()
            val data = response.toByteArray()
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
            socket.close()
            Log.d(TAG, "Sent SSDP response to ${address.hostAddress}:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SSDP response", e)
        }
    }

    private fun startPeriodicNotify() {
        scope.launch {
            try {
                delay(2000) // 启动后等待2秒
                sendNotify()

                // 每30分钟发送一次 NOTIFY
                while (true) {
                    delay(30 * 60 * 1000)
                    sendNotify()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in periodic notify", e)
            }
        }
    }

    private fun sendNotify() {
        val location = "http://$localIp:$httpPort/device.xml"

        val notifications = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            DEVICE_TYPE,
            SERVICE_TYPE_AV,
            SERVICE_TYPE_RC,
            SERVICE_TYPE_CM
        )

        try {
            val socket = DatagramSocket()
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (nt in notifications) {
                val message = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("LOCATION: $location\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:alive\r\n")
                    append("SERVER: Android/11 UPnP/1.0 Mirrocast/1.0\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (nt != "uuid:$deviceUuid") {
                        append("::$nt")
                    }

                    append("\r\n\r\n")
                }

                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            socket.close()
            Log.i(TAG, "Sent SSDP NOTIFY messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SSDP notify", e)
        }
    }

    private fun sendByebye() {
        val notifications = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            DEVICE_TYPE,
            SERVICE_TYPE_AV,
            SERVICE_TYPE_RC,
            SERVICE_TYPE_CM
        )

        try {
            val socket = DatagramSocket()
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (nt in notifications) {
                val message = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:byebye\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (nt != "uuid:$deviceUuid") {
                        append("::$nt")
                    }

                    append("\r\n\r\n")
                }

                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            socket.close()
            Log.i(TAG, "Sent SSDP byebye messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SSDP byebye", e)
        }
    }
}
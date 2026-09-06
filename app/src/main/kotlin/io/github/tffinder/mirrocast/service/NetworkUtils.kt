package io.github.tffinder.mirrocast.service

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** 网络工具：获取当前局域网 IPv4 地址，用于 DLNA 设备描述中的 LOCATION 字段。 */
object NetworkUtils {
    /**
     * 获取本机局域网 IPv4 地址。
     * 优先从 NetworkInterface 枚举（正确），失败时退回 WifiManager DHCP 信息。
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                val isLoopback = nif.name.equals("lo", ignoreCase = true) || nif.isLoopback
                if (nif.isUp && !isLoopback && !nif.name.startsWith("p2p")) {
                    val ipv4 = nif.inetAddresses?.toList().orEmpty()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress }
                    if (ipv4 != null) return ipv4.hostAddress
                }
            }
        } catch (_: Exception) {
        }

        // 兜底：尝试 WifiManager 的 DHCP 信息（部分盒子 WiFi 接口枚举不到）
        return runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ints = wifi.connectionInfo?.ipAddress ?: 0
            if (ints != 0) {
                "${ints and 0xff}.${(ints shr 8) and 0xff}.${(ints shr 16) and 0xff}.${(ints shr 24) and 0xff}"
            } else null
        }.getOrNull()
    }
}
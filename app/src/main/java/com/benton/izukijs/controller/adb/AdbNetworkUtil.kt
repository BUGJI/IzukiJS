package com.benton.izukijs.controller.adb

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * 无线调试只允许连接本机 adbd，这里用于给手动端口回退提供本机地址，
 * 并校验 mDNS 发现的主机确实属于本机，避免误配到局域网里的其他设备。
 */
object AdbNetworkUtil {

    fun localAddresses(): List<InetAddress> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
    }.getOrDefault(emptyList())

    fun preferredIpv4Address(): String? =
        localAddresses().filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress

    fun isLocalAddress(address: InetAddress): Boolean =
        localAddresses().any { it.hostAddress == address.hostAddress }
}

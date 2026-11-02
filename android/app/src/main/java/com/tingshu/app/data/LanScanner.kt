package com.tingshu.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * 局域网扫描：手机和路由器在同一个 Wi-Fi 下时，挨个探测 /24 网段的 8686 端口。
 * 只是为了让用户少输一次 IP，扫不到也可以手动填。
 */
class LanScanner(private val port: Int = 8686) {

    suspend fun scan(): List<String> = coroutineScope {
        val local = localIpv4() ?: return@coroutineScope emptyList()
        val prefix = local.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return@coroutineScope emptyList()

        val found = Collections.synchronizedList(mutableListOf<String>())
        (1..254).map { host ->
            async(Dispatchers.IO) {
                val candidate = "$prefix.$host"
                if (httpAlive(candidate)) {
                    found.add("http://$candidate:$port/")
                }
            }
        }.awaitAll()

        found.sorted()
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    }.getOrNull()

    private fun httpAlive(ip: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write(
                    "GET /library.json HTTP/1.0\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                out.flush()
                val buf = ByteArray(32)
                val n = socket.getInputStream().read(buf)
                n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).startsWith("HTTP/")
            }
        } catch (t: Throwable) {
            false
        }
    }

    private companion object {
        const val TIMEOUT_MS = 260
    }
}

package com.geno1024.netdis.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP connect probing. An accepted connection is proof that a host is alive and
 * costs one short socket; it also gives a first guess at what the host is.
 */
object TcpProbe {

    /** Ports tried on every address, chosen because they answer on real devices. */
    val QUICK_PORTS = listOf(
        80, 443, 22, 53, 8080, 8443, 445, 62078, 9100, 554, 8009, 5000, 1883,
    )

    /** Added when the deep-scan option is on. */
    val DEEP_PORTS = listOf(
        21, 23, 25, 81, 88, 139, 515, 631, 1880, 1900, 3000, 5001, 5060,
        6668, 7000, 7681, 8000, 8081, 8088, 8090, 8888, 32400, 49152, 49153,
    )

    /**
     * Tries ports in order until one connects or the per-host budget expires.
     * The budget is what keeps a /24 with many filtered addresses from turning
     * into minutes of waiting.
     */
    suspend fun probeHost(
        ip: String,
        ports: List<Int>,
        connectTimeoutMs: Int,
        budgetMs: Long,
    ): List<Int> = withContext(Dispatchers.IO) {
        val found = ArrayList<Int>()
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        for (port in ports) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            val timeout = minOf(connectTimeoutMs.toLong(), remainingMs).toInt().coerceAtLeast(60)
            if (connect(ip, port, timeout)) found += port
        }
        found
    }

    private fun connect(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * ICMP when the platform permits it, TCP echo otherwise. Used only as extra
     * evidence for addresses that neither ARP nor a TCP port confirmed.
     */
    suspend fun icmpReachable(ip: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            InetAddress.getByName(ip).isReachable(timeoutMs)
        } catch (_: Exception) {
            false
        }
    }
}

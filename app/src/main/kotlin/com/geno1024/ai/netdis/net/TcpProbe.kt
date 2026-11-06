package com.geno1024.ai.netdis.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Scans the complete 1–65535 TCP range. A full range is 65k short connects,
     * so this is intentionally limited to a bounded pool and reported through
     * [onProgress] about every 64 ports. Cancelling the calling coroutine stops
     * new connects; in-flight sockets finish within one timeout.
     */
    suspend fun scanAllPorts(
        ip: String,
        concurrency: Int = 256,
        connectTimeoutMs: Int = 250,
        onProgress: (scanned: Int, openPorts: List<Int>) -> Unit = { _, _ -> },
    ): List<Int> = withContext(Dispatchers.IO) {
        coroutineScope {
            val openPorts = Collections.synchronizedList(ArrayList<Int>())
            val scanned = AtomicInteger(0)
            val reported = AtomicInteger(0)
            val nextPort = AtomicInteger(FIRST_PORT)
            val total = LAST_PORT
            val workers = (1..concurrency.coerceIn(1, MAX_CONCURRENCY)).map {
                launch {
                    while (true) {
                        if (!isActive) break
                        val port = nextPort.getAndIncrement()
                        if (port > total) break
                        if (connect(ip, port, connectTimeoutMs)) openPorts += port
                        val done = scanned.incrementAndGet()
                        if (done == total || done % PROGRESS_STEP == 0) {
                            // Workers finish out of order. Keep the published
                            // count monotonic and call back from one thread so
                            // the UI/notification never jumps backwards.
                            synchronized(reported) {
                                if (done > reported.get()) {
                                    reported.set(done)
                                    onProgress(done, snapshot(openPorts))
                                }
                            }
                        }
                    }
                }
            }
            workers.joinAll()
            val result = snapshot(openPorts)
            onProgress(total, result)
            result
        }
    }

    private fun snapshot(ports: List<Int>): List<Int> = synchronized(ports) { ports.sorted() }

    private const val FIRST_PORT = 1
    private const val LAST_PORT = 65535
    private const val PROGRESS_STEP = 64
    private const val MAX_CONCURRENCY = 512

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

package com.geno1024.ai.netdis.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Reverse DNS through the router's resolver. This is how DHCP host names show
 * up on networks where NetBIOS and mDNS are silent.
 */
object DnsReverse {

    suspend fun lookup(
        ip: String,
        server: String,
        timeoutMs: Int = 700,
    ): String? = withContext(Dispatchers.IO) {
        val queryName = DnsPacket.reverseName(ip) ?: return@withContext null
        val id = Random.nextInt(0, 0xFFFF)
        val query = DnsPacket.buildQuery(
            questions = listOf(queryName to DnsPacket.TYPE_PTR),
            id = id,
            recursionDesired = true,
        )
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(server)
                socket.send(DatagramPacket(query, query.size, address, 53))
                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = DnsPacket.parse(buffer, packet.length) ?: continue
                    if (message.id != id) continue
                    val ptr = message.of(DnsPacket.TYPE_PTR).firstOrNull() ?: continue
                    val target = (ptr.data as? DnsPacket.RData.Target)?.name ?: continue
                    return@withContext target.trimEnd('.').takeIf { it.isNotBlank() }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

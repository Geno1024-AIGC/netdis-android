package com.geno1024.netdis.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import com.geno1024.netdis.model.LocalNetwork
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the IPv4 network the phone is actually attached to. VPNs are skipped in
 * favour of the physical Wi-Fi/Ethernet link because the tunnel address says
 * nothing about the home LAN.
 */
object LocalNetworkDetector {

    private data class Candidate(
        val name: String,
        val link: LinkProperties,
        val capabilities: NetworkCapabilities?,
    )

    fun detect(context: Context, overrideCidr: String = ""): LocalNetwork? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val candidates = runCatching {
            cm.allNetworks.mapNotNull { network ->
                val link = cm.getLinkProperties(network) ?: return@mapNotNull null
                Candidate(network.toString(), link, cm.getNetworkCapabilities(network))
            }
        }.getOrDefault(emptyList())

        val physical = candidates.firstOrNull {
            it.capabilities?.let { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true && it.link.hasIpv4()
        }
        val active = runCatching {
            val network = cm.activeNetwork ?: return@runCatching null
            val link = cm.getLinkProperties(network) ?: return@runCatching null
            Candidate(network.toString(), link, cm.getNetworkCapabilities(network))
        }.getOrNull()
        val chosen = physical
            ?: candidates.firstOrNull { it.link.hasIpv4() && it.capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true }
            ?: active?.takeIf { it.link.hasIpv4() }
            ?: candidates.firstOrNull { it.link.hasIpv4() }

        if (chosen == null) return fallbackFromInterfaces(overrideCidr)

        val ipv4 = chosen.link.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: return fallbackFromInterfaces(overrideCidr)

        val localIp = ipv4.address.hostAddress ?: return fallbackFromInterfaces(overrideCidr)
        val prefix = ipv4.prefixLength
        val gateway = gatewayOf(chosen.link, localIp)
        val dns = chosen.link.dnsServers.mapNotNull { it.hostAddress }
        val transport = transportLabel(chosen.capabilities)

        val cidr = Subnet.parseCidr(overrideCidr)
            ?: Subnet.cidrOf(localIp, prefix)
            ?: return null
        return LocalNetwork(
            interfaceName = chosen.link.interfaceName ?: "?",
            localIp = localIp,
            prefixLength = prefix,
            gateway = gateway,
            dnsServers = dns,
            cidr = cidr.toString(),
            hostCount = cidr.hostCount,
            transport = transport,
        )
    }

    private fun gatewayOf(link: LinkProperties, localIp: String): String? {
        link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress?.let { return it }
        // API 30+, but the property is harmless to call behind a guard.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                link.dhcpServerAddress?.hostAddress?.let { return it }
            }
        }
        link.routes.firstOrNull { it.gateway is Inet4Address }
            ?.gateway?.hostAddress?.let { return it }
        return Subnet.guessGateway(localIp)
    }

    private fun LinkProperties.hasIpv4(): Boolean =
        linkAddresses.any { it.address is Inet4Address && !it.address.isLoopbackAddress }

    private fun transportLabel(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "网络"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
        else -> "网络"
    }

    private fun fallbackFromInterfaces(overrideCidr: String): LocalNetwork? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.interfaceAddresses) {
                val ipv4 = address.address as? Inet4Address ?: continue
                if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) continue
                val ip = ipv4.hostAddress ?: continue
                val cidr = Subnet.parseCidr(overrideCidr)
                    ?: Subnet.cidrOf(ip, address.networkPrefixLength.toInt())
                    ?: continue
                return LocalNetwork(
                    interfaceName = iface.name,
                    localIp = ip,
                    prefixLength = address.networkPrefixLength.toInt(),
                    gateway = Subnet.guessGateway(ip),
                    dnsServers = emptyList(),
                    cidr = cidr.toString(),
                    hostCount = cidr.hostCount,
                    transport = if (iface.name.startsWith("wl")) "Wi-Fi" else "网络",
                )
            }
        }
        return null
    }
}

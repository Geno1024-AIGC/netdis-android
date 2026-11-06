package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterParsingTest {

    @Test
    fun `converts netmasks to prefixes`() {
        assertEquals(24, RouterParsing.prefixFromNetmask("255.255.255.0"))
        assertEquals(16, RouterParsing.prefixFromNetmask("255.255.0.0"))
        assertEquals(32, RouterParsing.prefixFromNetmask("255.255.255.255"))
        assertEquals(0, RouterParsing.prefixFromNetmask("0.0.0.0"))
        assertEquals(30, RouterParsing.prefixFromNetmask("255.255.255.252"))
        assertNull(RouterParsing.prefixFromNetmask("255.0.255.0"))
        assertNull(RouterParsing.prefixFromNetmask("nope"))
    }

    @Test
    fun `builds cidrs from addresses plus mask or prefix`() {
        assertEquals("192.168.1.0/24", RouterParsing.cidrFrom("192.168.1.130", "255.255.255.0"))
        assertEquals("10.0.0.0/8", RouterParsing.cidrFrom("10.1.2.3", "8"))
        assertEquals("172.17.5.9/32", RouterParsing.cidrFrom("172.17.5.9", null))
        assertNull(RouterParsing.cidrFrom("192.168.1.1", "33"))
        assertNull(RouterParsing.cidrFrom("nope", "24"))
    }

    @Test
    fun `formats uptime the way router pages do`() {
        assertEquals("", RouterParsing.formatUptime(0))
        assertEquals("", RouterParsing.formatUptime(-5))
        assertEquals("5分钟", RouterParsing.formatUptime(5 * 60L))
        assertEquals("2小时", RouterParsing.formatUptime(2 * 3600L))
        assertEquals("3天4小时", RouterParsing.formatUptime(3 * 86_400L + 4 * 3600L + 30))
    }

    @Test
    fun `merges duplicate hosts field by field`() {
        val merged = RouterParsing.mergeHosts(
            listOf(
                HostRecord(
                    ip = "192.168.1.20",
                    hostname = "laptop",
                    openPorts = listOf(22, 80),
                    services = listOf("ssh"),
                    sources = setOf(HostSource.TCP),
                    kind = DeviceKind.COMPUTER,
                ),
                HostRecord(
                    ip = "192.168.1.20",
                    mac = "aa:bb:cc:dd:ee:20",
                    vendor = "Apple",
                    openPorts = listOf(80, 443),
                    services = listOf("http"),
                    sources = setOf(HostSource.ARP),
                    link = LinkKind.WIFI,
                    online = false,
                ),
            ),
        )
        val host = merged.single()
        assertEquals("192.168.1.20", host.ip)
        assertEquals("laptop", host.hostname)
        assertEquals("aa:bb:cc:dd:ee:20", host.mac)
        assertEquals("Apple", host.vendor)
        assertEquals(listOf(22, 80, 443), host.openPorts)
        assertEquals(listOf("ssh", "http"), host.services)
        assertEquals(DeviceKind.COMPUTER, host.kind)
        assertEquals(LinkKind.WIFI, host.link)
        assertEquals(setOf(HostSource.TCP, HostSource.ARP), host.sources)
    }

    @Test
    fun `merge keeps the first entry order and ignores empty input`() {
        assertEquals(0, RouterParsing.mergeHosts(emptyList()).size)
    }

    @Test
    fun `reads the page title`() {
        assertEquals(
            "OpenWrt - LuCI",
            RouterFingerprintProbe.extractTitle("<html><head><title> OpenWrt\n - LuCI </title></head>"),
        )
        assertEquals("MikroTik", RouterFingerprintProbe.extractTitle("<TITLE>MikroTik</TITLE>"))
        assertNull(RouterFingerprintProbe.extractTitle("<html>no title</html>"))
        assertNull(RouterFingerprintProbe.extractTitle("<title>   </title>"))
    }

    @Test
    fun `recognises router brands from page text`() {
        assertEquals("OpenWrt", RouterFingerprintProbe.detectBrand("LuCI - OpenWrt"))
        assertEquals("MikroTik", RouterFingerprintProbe.detectBrand("RouterOS v7"))
        assertEquals("TP-Link", RouterFingerprintProbe.detectBrand("TP-Link Archer"))
        assertEquals("小米", RouterFingerprintProbe.detectBrand("小米路由器"))
        assertNull(RouterFingerprintProbe.detectBrand("some nginx page"))
    }

    @Test
    fun `formats the fingerprint summary`() {
        val fingerprint = RouterFingerprint(
            reachable = true,
            title = "LuCI",
            brand = "OpenWrt",
        )
        assertEquals("OpenWrt · LuCI", fingerprint.summary)

        val serverOnly = RouterFingerprint(reachable = true, server = "nginx")
        assertEquals("nginx", serverOnly.summary)

        assertEquals(null, RouterFingerprint(reachable = false).summary)
    }

    @Test
    fun `normalises router urls`() {
        assertEquals(
            "10.0.0.1",
            RouterScanner.normalize(
                com.geno1024.ai.netdis.model.RouterConfig(id = "a", label = "", host = "http://10.0.0.1/"),
            ).host,
        )
        val withPort = RouterScanner.normalize(
            com.geno1024.ai.netdis.model.RouterConfig(id = "a", label = "", host = "10.0.0.1:8080"),
        )
        assertEquals("10.0.0.1", withPort.host)
        assertEquals(8080, withPort.port)

        val https = RouterScanner.normalize(
            com.geno1024.ai.netdis.model.RouterConfig(id = "a", label = "", host = "https://router.lan/admin"),
        )
        assertEquals("https", https.scheme)
        assertEquals("router.lan", https.host)
        assertEquals("https://router.lan:443", https.baseUrl)
    }
}

package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiParsingTest {

    private val loginPage = """
        <html><body>
        <form action="/login.cgi" method="post">
          <input name="username" type="text">
          <input name="password" type="password">
          <button>登录</button>
        </form>
        </body></html>
    """.trimIndent()

    private val dashboard = """
        <html><body>
          <a href="/logout.cgi">退出登录</a>
          <table>
            <tr><td>型号</td><td>Archer AX55</td></tr>
            <tr><td>软件版本</td><td>1.2.3 build 4</td></tr>
            <tr><td>运行时间</td><td>5天 2小时</td></tr>
            <tr><td>WAN IP 地址</td><td>100.64.0.2</td></tr>
            <tr><td>默认网关</td><td>100.64.0.1</td></tr>
            <tr><td>LAN IP 地址</td><td>192.168.1.1 / 255.255.255.0</td></tr>
            <tr><td>DNS 服务器</td><td>8.8.8.8 1.1.1.1</td></tr>
          </table>
        </body></html>
    """.trimIndent()

    private val clientTable = """
        <html><body>
          <table>
            <tr><th>序号</th><th>IP 地址</th><th>MAC 地址</th><th>主机名</th><th>接口</th><th>状态</th></tr>
            <tr><td>1</td><td>192.168.1.20</td><td>AA:BB:CC:DD:EE:01</td><td>phone</td><td>无线</td><td>在线</td></tr>
            <tr><td>2</td><td>192.168.1.21</td><td>AA-BB-CC-DD-EE-02</td><td>nas</td><td>有线</td><td>离线</td></tr>
            <tr><td>子网掩码</td><td>255.255.255.0</td></tr>
          </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `tells login, logout and rejection pages apart`() {
        assertTrue(WebUiParsing.looksLikeLoginPage(loginPage))
        assertFalse(WebUiParsing.looksLikeLoginPage(dashboard))
        assertTrue(WebUiParsing.looksLoggedIn(dashboard))
        assertFalse(WebUiParsing.looksLoggedIn(loginPage))
        assertTrue(WebUiParsing.loginFailed("<div>用户名或密码错误</div>"))
        assertTrue(WebUiParsing.loginFailed("<div>Invalid password</div>"))
        assertFalse(WebUiParsing.loginFailed(dashboard))
    }

    @Test
    fun `finds tokens in JSON and LuCI URLs`() {
        assertEquals("abc12345", WebUiParsing.extractToken("""{"token":"abc12345","code":0}"""))
        assertEquals("XYZ98765", WebUiParsing.extractToken("/cgi-bin/luci/;stok=XYZ98765/api/misystem/devicelist"))
        assertNull(WebUiParsing.extractToken("<html><body>no session here</body></html>"))
    }

    @Test
    fun `parses client rows without fixed column indexes`() {
        val clients = WebUiParsing.parseClients(clientTable, routerId = "gw")
        assertEquals(2, clients.size)

        val phone = clients[0]
        assertEquals("192.168.1.20", phone.ip)
        assertEquals("aa:bb:cc:dd:ee:01", phone.mac)
        assertEquals("phone", phone.hostname)
        assertEquals(LinkKind.WIFI, phone.link)
        assertTrue(phone.online)
        assertEquals("gw", phone.routerId)
        assertTrue(phone.sources.contains(HostSource.DHCP))
        assertTrue(phone.sources.contains(HostSource.ROUTER_ARP))

        val nas = clients[1]
        assertEquals("192.168.1.21", nas.ip)
        assertEquals("aa:bb:cc:dd:ee:02", nas.mac)
        assertEquals(LinkKind.ETHERNET, nas.link)
        assertFalse(nas.online)
    }

    @Test
    fun `parses ASUS style client maps keyed by MAC`() {
        val json = """
            {"get_clientlist":{
              "AA:BB:CC:DD:EE:11":{"ip":"192.168.1.30","name":"phone","isWL":"1","online":"1"},
              "AA:BB:CC:DD:EE:12":{"ip":"192.168.1.31","name":"tv","isWL":"0","online":"0"}
            }}
        """.trimIndent()
        val clients = WebUiParsing.parseClients(json)
        assertEquals(2, clients.size)
        val phone = clients.first { it.mac == "aa:bb:cc:dd:ee:11" }
        assertEquals("phone", phone.hostname)
        assertEquals(LinkKind.WIFI, phone.link)
        assertTrue(phone.online)
        val tv = clients.first { it.mac == "aa:bb:cc:dd:ee:12" }
        assertEquals(LinkKind.ETHERNET, tv.link)
        assertFalse(tv.online)
    }

    @Test
    fun `finds a device list embedded in a script tag`() {
        val html = """
            <html><script>
            var devicelist = {"list":[
              {"mac":"AA:BB:CC:DD:EE:21","name":"mi-phone","online":"1",
               "ip":[{"ip":"192.168.1.40","netmask":"255.255.255.0"}],"ssid":"home"}
            ]};
            </script></html>
        """.trimIndent()
        val clients = WebUiParsing.parseClients(html)
        assertEquals(1, clients.size)
        val phone = clients.single()
        assertEquals("192.168.1.40", phone.ip)
        assertEquals("aa:bb:cc:dd:ee:21", phone.mac)
        assertEquals("mi-phone", phone.hostname)
        assertEquals(LinkKind.WIFI, phone.link)
        assertEquals("home", phone.extra["ssid"])
    }

    @Test
    fun `reads status fields from HTML pairs`() {
        val status = WebUiParsing.parseStatus(listOf(dashboard))
        assertEquals("Archer AX55", status.model)
        assertEquals("1.2.3 build 4", status.firmware)
        assertEquals("5天 2小时", status.uptime)
        assertEquals("100.64.0.2", status.wanIp)
        assertEquals("100.64.0.1", status.wanGateway)
        assertEquals(listOf("192.168.1.0/24"), status.lanCidrs)
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), status.dnsServers)
    }

    @Test
    fun `reads ASUS nvram status fields`() {
        val json = """
            {"nvram_get(productid)":"RT-AX88U","nvram_get(firmver)":"3.0.0.4",
             "nvram_get(wan0_ipaddr)":"100.64.0.2","nvram_get(wan0_gateway)":"100.64.0.1",
             "nvram_get(wan0_dns)":"8.8.8.8 1.1.1.1"}
        """.trimIndent()
        val status = WebUiParsing.parseStatus(listOf(json))
        assertEquals("RT-AX88U", status.model)
        assertEquals("3.0.0.4", status.firmware)
        assertEquals("100.64.0.2", status.wanIp)
        assertEquals("100.64.0.1", status.wanGateway)
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), status.dnsServers)
    }

    @Test
    fun `merging status keeps the first value and fills gaps`() {
        val merged = WebUiParsing.StatusFields(model = "A")
            .merge(WebUiParsing.StatusFields(model = "B", firmware = "F"))
        assertEquals("A", merged.model)
        assertEquals("F", merged.firmware)
    }

    @Test
    fun `skips header, subnet-mask and gateway rows`() {
        val clients = WebUiParsing.parseClients(
            """
            <table>
              <tr><th>IP 地址</th><th>MAC 地址</th><th>主机名</th></tr>
              <tr><td>子网掩码</td><td>255.255.255.0</td><td>LAN</td></tr>
              <tr><td>默认网关</td><td>192.168.1.1</td><td>gateway</td></tr>
            </table>
            """.trimIndent(),
        )
        assertTrue(clients.isEmpty())
    }

    @Test
    fun `cleans markup and entities from cells`() {
        val rows = WebUiParsing.tableRows("<tr><td>  <b>客厅</b>&nbsp;电视 &amp; 盒子 </td></tr>")
        assertEquals(listOf("客厅 电视 & 盒子"), rows.single())
    }
}

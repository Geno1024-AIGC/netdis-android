package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZteParsingTest {

    private val loginPage = """
        <html><body>
        <form action="/login.gch" method="post">
          <input name="Username" type="text">
          <input name="Password" type="password">
          <button>登录</button>
        </form>
        </body></html>
    """.trimIndent()

    private val statusPage = """
        <html><body>
          <a href="/logout.gch">退出登录</a>
          <table>
            <tr><td>型号</td><td>ZXHN F7607P</td></tr>
            <tr><td>软件版本</td><td>V1.2.0P3N2</td></tr>
            <tr><td>运行时间</td><td>3天 4小时 5分钟</td></tr>
            <tr><td>WAN IP 地址</td><td>100.64.0.2</td></tr>
            <tr><td>默认网关</td><td>100.64.0.1</td></tr>
            <tr><td>LAN IP 地址</td><td>192.168.1.1 / 255.255.255.0</td></tr>
          </table>
        </body></html>
    """.trimIndent()

    private val clientPage = """
        <html><body>
          <table>
            <tr><th>序号</th><th>IP 地址</th><th>MAC 地址</th><th>主机名</th><th>接口</th><th>状态</th></tr>
            <tr><td>1</td><td>192.168.1.10</td><td>AA:BB:CC:DD:EE:FF</td><td>phone</td><td>无线</td><td>在线</td></tr>
            <tr><td>2</td><td>192.168.1.11</td><td>AA-BB-CC-DD-EE-01</td><td>nas</td><td>有线</td><td>离线</td></tr>
            <tr><td>子网掩码</td><td>255.255.255.0</td></tr>
          </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `reads a random token from a plain or scripted response`() {
        assertEquals("AbCd1234", ZteParsing.extractRand("AbCd1234"))
        assertEquals(
            "AbCd1234",
            ZteParsing.extractRand("""<script>var GetRandCount = "AbCd1234";</script>"""),
        )
        assertNull(ZteParsing.extractRand("<html><body>no token here</body></html>"))
    }

    @Test
    fun `tells login, logout and rejection pages apart`() {
        assertTrue(ZteParsing.looksLikeLoginPage(loginPage))
        assertFalse(ZteParsing.looksLikeLoginPage(statusPage))
        assertTrue(ZteParsing.looksLoggedIn(statusPage))
        assertFalse(ZteParsing.looksLoggedIn(loginPage))
        assertTrue(ZteParsing.loginFailed("<div>用户名或密码错误</div>"))
        assertFalse(ZteParsing.loginFailed(statusPage))
    }

    @Test
    fun `parses client rows without fixed column indexes`() {
        val clients = ZteParsing.parseClients(clientPage, routerId = "gw")
        assertEquals(2, clients.size)

        val phone = clients[0]
        assertEquals("192.168.1.10", phone.ip)
        assertEquals("aa:bb:cc:dd:ee:ff", phone.mac)
        assertEquals("phone", phone.hostname)
        assertEquals(LinkKind.WIFI, phone.link)
        assertTrue(phone.online)
        assertEquals("gw", phone.routerId)
        assertTrue(phone.sources.contains(HostSource.DHCP))

        val nas = clients[1]
        assertEquals("192.168.1.11", nas.ip)
        assertEquals("aa:bb:cc:dd:ee:01", nas.mac)
        assertEquals(LinkKind.ETHERNET, nas.link)
        assertFalse(nas.online)
    }

    @Test
    fun `skips header and subnet-mask rows`() {
        val clients = ZteParsing.parseClients(
            """
            <table>
              <tr><th>IP 地址</th><th>MAC 地址</th><th>主机名</th></tr>
              <tr><td>子网掩码</td><td>255.255.255.0</td><td>LAN</td></tr>
            </table>
            """.trimIndent(),
        )
        assertTrue(clients.isEmpty())
    }

    @Test
    fun `reads status fields from two-cell rows`() {
        val pairs = ZteParsing.tablePairs(statusPage)
        assertEquals("ZXHN F7607P", ZteParsing.extractModel(pairs, statusPage))
        assertEquals("V1.2.0P3N2", ZteParsing.extractFirmware(pairs, statusPage))
        assertEquals("3天 4小时 5分钟", ZteParsing.extractUptime(pairs))
        assertEquals("100.64.0.2", ZteParsing.extractWanIp(pairs))
        assertEquals("100.64.0.1", ZteParsing.extractWanGateway(pairs))
        assertEquals(listOf("192.168.1.0/24"), ZteParsing.extractLanCidrs(pairs))
    }

    @Test
    fun `cleans markup and entities from cells`() {
        val rows = ZteParsing.tableRows("<tr><td>  <b>客厅</b>&nbsp;电视 &amp; 盒子 </td></tr>")
        assertEquals(listOf("客厅 电视 & 盒子"), rows.single())
    }

    @Test
    fun `reads the E1600 login token and salt`() {
        val html = """
            <script>
              LoginFormObj.addParameter("_sessionTOKEN", "397667032103403366315799");
              var SHA256Password = sha256(Password + xmlObj);
            </script>
        """.trimIndent()
        assertEquals("397667032103403366315799", ZteParsing.extractLoginToken(html))

        val salt = ZteParsing.extractSalt(
            """<ajax_response_xml_root>63178618</ajax_response_xml_root>""",
        )
        assertEquals("63178618", salt)
        assertEquals("", ZteParsing.extractSalt("<html></html>"))
    }

    @Test
    fun `parses ParaName and ParaValue instances`() {
        val xml = """
            <ajax_response_xml_root>
              <OBJ_LAN_ACCESS_ID>
                <Instance>
                  <ParaName>IPAddress</ParaName><ParaValue>192.168.2.36</ParaValue>
                  <ParaName>MACAddress</ParaName><ParaValue>F0:13:C1:28:98:1A</ParaValue>
                  <ParaName>HostName</ParaName><ParaValue>printer</ParaValue>
                  <ParaName>Band</ParaName><ParaValue>5G</ParaValue>
                  <ParaName>Interface</ParaName><ParaValue>5G</ParaValue>
                  <ParaName>Active</ParaName><ParaValue>1</ParaValue>
                </Instance>
                <Instance>
                  <ParaName>IPAddress</ParaName><ParaValue>192.168.2.3</ParaValue>
                  <ParaName>MACAddress</ParaName><ParaValue>AA-BB-CC-DD-EE-03</ParaValue>
                  <ParaName>HostName</ParaName><ParaValue>speaker</ParaValue>
                  <ParaName>Interface</ParaName><ParaValue>LAN</ParaValue>
                  <ParaName>Active</ParaName><ParaValue>0</ParaValue>
                </Instance>
              </OBJ_LAN_ACCESS_ID>
            </ajax_response_xml_root>
        """.trimIndent()

        val instances = ZteParsing.parseInstances(xml)
        assertEquals(2, instances.size)
        assertEquals("192.168.2.36", instances[0]["IPAddress"])
        assertEquals("F0:13:C1:28:98:1A", instances[0]["MACAddress"])

        val clients = ZteParsing.parseAccessDevices(xml, routerId = "zte")
        assertEquals(2, clients.size)

        val printer = clients[0]
        assertEquals("192.168.2.36", printer.ip)
        assertEquals("f0:13:c1:28:98:1a", printer.mac)
        assertEquals("printer", printer.hostname)
        assertEquals(LinkKind.WIFI, printer.link)
        assertTrue(printer.online)
        assertEquals("5G", printer.extra["band"])
        assertEquals("zte", printer.routerId)

        val speaker = clients[1]
        assertEquals(LinkKind.ETHERNET, speaker.link)
        assertFalse(speaker.online)
    }

    @Test
    fun `reads E1600 status from ParaName instances`() {
        val xml = """
            <ajax_response_xml_root>
              <Instance>
                <ParaName>ModelName</ParaName><ParaValue>ZXHN E1600</ParaValue>
                <ParaName>SoftwareVer</ParaName><ParaValue>V1.0.0.1B9.1000</ParaValue>
                <ParaName>HardwareVer</ParaName><ParaValue>V1.0.0</ParaValue>
                <ParaName>UpTime</ParaName><ParaValue>3458615</ParaValue>
                <ParaName>ManuFacturer</ParaName><ParaValue>ZTE</ParaValue>
              </Instance>
              <Instance>
                <ParaName>BrIPAddr</ParaName><ParaValue>192.168.2.1</ParaValue>
                <ParaName>SubMask</ParaName><ParaValue>255.255.255.0</ParaValue>
              </Instance>
              <Instance>
                <ParaName>IPAddress</ParaName><ParaValue>192.168.1.7</ParaValue>
                <ParaName>GateWay</ParaName><ParaValue>192.168.1.1</ParaValue>
                <ParaName>DNS1</ParaName><ParaValue>192.168.1.1</ParaValue>
                <ParaName>WorkIFMac</ParaName><ParaValue>d4:b7:09:93:07:d2</ParaValue>
              </Instance>
            </ajax_response_xml_root>
        """.trimIndent()

        val instances = ZteParsing.parseInstances(xml)
        val model = instances.firstNotNullOfOrNull { it["ModelName"] }
        val firmware = instances.firstNotNullOfOrNull { it["SoftwareVer"] }
        val uptime = instances.firstNotNullOfOrNull { it["UpTime"] }
        assertEquals("ZXHN E1600", model)
        assertEquals("V1.0.0.1B9.1000", firmware)
        assertEquals(3458615L, uptime?.toLong())

        val lan = instances.firstNotNullOfOrNull { it["BrIPAddr"] }
        val mask = instances.firstNotNullOfOrNull { it["SubMask"] }
        assertEquals("192.168.2.0/24", RouterParsing.cidrFrom(lan.orEmpty(), mask))
        assertEquals("192.168.1.7", instances.firstNotNullOfOrNull { it["IPAddress"] })
        assertEquals("192.168.1.1", instances.firstNotNullOfOrNull { it["GateWay"] })
    }
}

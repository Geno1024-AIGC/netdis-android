package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.DeviceKind
import com.geno1024.ai.netdis.model.HostSource
import com.geno1024.ai.netdis.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the shapes captured from a China Telecom HS8145C. The gateway keys
 * objects by generated names (`dev1`, `pc1`) instead of returning arrays, so
 * these fixtures are exactly the payloads the adapter has to walk.
 */
class HuaweiParsingTest {

    private val autoSubmit = """
        <html><body>
        <form id="argform" method="post" action="/cgi-bin/luci">
          <input type="hidden" name="token" value="hgdetrsafdasfw3egfsgfd" />
        </form>
        </body></html>
    """.trimIndent()

    private val loginPage = """
        <html><body>
        <form id="login_form" method="post" action="/cgi-bin/luci" onsubmit="return checkPassword();">
          <input id="login_username" name="username" value="useradmin" />
          <input id="login_password" name="psd" type="password" />
        </form>
        </body></html>
    """.trimIndent()

    private val adminPage = """
        <html><head>
        <link href='/luci-static/resources/css/main_style.css' rel='stylesheet'/>
        </head><body><a href="/cgi-bin/luci/admin/logout">退出</a></body></html>
    """.trimIndent()

    private val gwinfo = """
        {"LANIP":"192.168.1.1","WANIP":"100.64.56.11","ProductSN":"E86819-31862E8681911B4CC",
         "DevType":"GPON天翼网关(4口单频)","SWVer":"V3.17.C10S125",
         "MAC":"E8681911B4CC","ProductCls":"HS8145C"}
    """.trimIndent()

    private val gwstatus = """
        {"sysTime":13509,"wcount":4,"wlcount":0,"scount":0,"upTime":13419,"cpuUsed":22}
    """.trimIndent()

    private val devInfo = """
        {"dev1":{"mac":"D4B7099307D2","ipv6":"","restrict":false,"ip":"192.168.1.7",
          "black":false,"type":"ROUTER","system":"linux"},
         "dev3":{"mac":"00E04C360EE6","ipv6":"","restrict":false,"ip":"192.168.1.11",
          "black":false,"type":"PC","system":"windows"},
         "dev4":{"mac":"9CEBE88E8DCF","ipv6":"","restrict":false,"ip":"192.168.1.12",
          "black":false,"type":"phone","system":"android"},
         "curip":"192.168.1.7","count":3}
    """.trimIndent()

    private val allInfo = """
        {"pc1":{"brand":"中兴","type":"pc","model":"中兴E1600","onlineTime":13377,"ip":"192.168.1.7"},
         "pc3":{"brand":"其它","type":"PC","model":"Windows 11","onlineTime":2830,"ip":"192.168.1.11"},
         "pc4":{"brand":"一加","type":"phone","model":"一加13","onlineTime":2826,"ip":"192.168.1.12"},
         "wanConnect":"CONNECTED","wcount":3,"wlcount":0}
    """.trimIndent()

    @Test
    fun `extracts the hidden login token`() {
        assertEquals("hgdetrsafdasfw3egfsgfd", HuaweiParsing.extractToken(autoSubmit))
        assertNull(HuaweiParsing.extractToken(adminPage))
    }

    @Test
    fun `tells the login form from the admin frame`() {
        assertTrue(HuaweiParsing.looksLikeLoginPage(loginPage))
        assertFalse(HuaweiParsing.looksLikeLoginPage(adminPage))
        assertTrue(HuaweiParsing.looksLoggedIn(adminPage))
        assertFalse(HuaweiParsing.looksLoggedIn(loginPage))
        assertTrue(HuaweiParsing.loginFailed("<div>用户名或密码错误</div>"))
        assertFalse(HuaweiParsing.loginFailed(adminPage))
    }

    @Test
    fun `parses wired devices with normalized MACs and kinds`() {
        val devices = HuaweiParsing.parseDevices(devInfo, routerId = "huawei", wifi = false)
        assertEquals(3, devices.size)
        val router = devices.first { it.ip == "192.168.1.7" }
        assertEquals("d4:b7:09:93:07:d2", router.mac)
        assertEquals(DeviceKind.ROUTER, router.kind)
        assertEquals(LinkKind.ETHERNET, router.link)
        assertEquals("huawei", router.routerId)
        assertTrue(router.sources.contains(HostSource.ROUTER_ARP))

        assertEquals(DeviceKind.COMPUTER, devices.first { it.ip == "192.168.1.11" }.kind)
        assertEquals(DeviceKind.PHONE, devices.first { it.ip == "192.168.1.12" }.kind)
    }

    @Test
    fun `names devices from allInfo and keeps the evidence`() {
        val names = HuaweiParsing.parseNames(allInfo)
        assertEquals(3, names.size)
        assertEquals("中兴E1600", names.getValue("192.168.1.7").model)
        assertEquals(13377L, names.getValue("192.168.1.7").onlineSeconds)

        val devices = HuaweiParsing.applyNames(
            HuaweiParsing.parseDevices(devInfo, routerId = "huawei", wifi = false),
            names,
        )
        val onePlus = devices.first { it.ip == "192.168.1.12" }
        assertEquals("一加13", onePlus.hostname)
        assertEquals("一加", onePlus.vendor)
        assertEquals("一加13", onePlus.displayName)
        assertEquals("2826", onePlus.extra["onlineSeconds"])
    }

    @Test
    fun `parses gateway identity and status`() {
        val info = HuaweiParsing.parseInfo(gwinfo, gwstatus)
        assertEquals("HS8145C", info.model)
        assertEquals("V3.17.C10S125", info.firmware)
        assertEquals("e8:68:19:11:b4:cc", info.mac)
        assertEquals(listOf("192.168.1.0/24"), info.lanCidrs)
        assertEquals("100.64.56.11", info.wanIp)
        assertNotNull(info.uptime)
        assertTrue(info.uptime.orEmpty().isNotBlank())
    }

    @Test
    fun `ignores endpoints that carry no rows`() {
        assertTrue(HuaweiParsing.parseDevices("""{"curip":"","count":0}""", null, wifi = true).isEmpty())
        assertTrue(HuaweiParsing.parseNames("""{"curip":"","count":0}""").isEmpty())
        assertTrue(HuaweiParsing.parseDevices("not json", null, wifi = false).isEmpty())
    }
}

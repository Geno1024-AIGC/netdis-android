package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType

private fun webGet(path: String) = WebUiRequest(path)

private fun webForm(path: String, body: String) =
    WebUiRequest(path, method = "POST", body = body)

private fun webJson(path: String, body: String) =
    WebUiRequest(path, method = "POST", body = body, contentType = "application/json")

abstract class WebUiBrand : RouterAdapter {
    protected abstract val profile: WebUiProfile

    override suspend fun scan(config: RouterConfig, timeoutMs: Int): RouterScanOutcome =
        WebUiScanner.scan(profile, config, timeoutMs)
}

private fun tpLinkProfile(type: RouterType, brand: String) = WebUiProfile(
    type = type,
    brand = brand,
    landingPaths = listOf("/", "/webpages/login.html", "/login.html"),
    loginSteps = listOf(
        webForm(
            "/cgi-bin/luci/;stok=/login?form=login",
            "operation=login&username={user}&password={pass}",
        ),
        webForm("/cgi-bin/luci", "username={user}&password={pass}"),
        webForm("/login.cgi", "username={user}&password={pass}"),
        webForm("/", "username={user}&password={pass}"),
    ),
    clientRequests = listOf(
        webGet("/cgi-bin/luci/;stok=/admin/status?form=all"),
        webGet("/cgi-bin/luci/;stok=/admin/status?form=client_list"),
        webGet("/cgi-bin/luci/admin/status/clients"),
        webGet("/userRpm/AssignedIpAddrListRpm.htm"),
        webGet("/userRpm/WlanStationRpm.htm"),
        webGet("/userRpm/SystemStatisticRpm.htm"),
        webGet("/webpages/index.html"),
    ),
    statusRequests = listOf(
        webGet("/userRpm/StatusRpm.htm"),
        webGet("/cgi-bin/luci/;stok=/admin/status?form=status"),
    ),
    basicAuth = true,
)

/**
 * TP-Link and its Chinese sibling Mercury share the same LuCI-derived pages, so
 * one profile covers both and only the displayed type differs.
 */
object TpLinkAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.TP_LINK
    override val profile = tpLinkProfile(type, "TP-Link")
}

object MercuryAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.MERCURY
    override val profile = tpLinkProfile(type, "水星 Mercury")
}

/**
 * ASUSWRT accepts a base64 `user:password` pair at `/login.cgi` and exposes the
 * client table through `appGet.cgi`. Its nvram hooks return one small JSON
 * object per key, which the shared JSON walker already understands.
 */
object AsusAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.ASUS
    override val profile = WebUiProfile(
        type = type,
        brand = "华硕 ASUS",
        landingPaths = listOf("/", "/Main_Login.asp", "/login.asp"),
        loginSteps = listOf(
            webForm("/login.cgi", "login_authorization={basic}"),
            webForm("/login.cgi", "login_authorization={basic}&submit_flag=login"),
            webForm("/login.cgi", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/appGet.cgi?hook=get_clientlist()"),
            webGet("/appGet.cgi?hook=get_wlnoauthlist()"),
            webGet("/appGet.cgi?hook=get_clientlist"),
            webGet("/update_clients.asp"),
            webGet("/clients.asp"),
        ),
        statusRequests = listOf(
            webGet("/appGet.cgi?hook=nvram_get(productid)"),
            webGet("/appGet.cgi?hook=nvram_get(firmver)"),
            webGet("/appGet.cgi?hook=nvram_get(wan0_ipaddr)"),
            webGet("/appGet.cgi?hook=nvram_get(wan0_gateway)"),
            webGet("/appGet.cgi?hook=nvram_get(wan0_dns)"),
        ),
    )
}

/**
 * MiWiFi's LuCI API returns a `token`, then a JSON device list. Some firmware
 * only accepts a pre-hashed password; the plain form is tried first and the
 * capture log shows the rejected request when the newer scheme is required.
 */
object XiaomiAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.XIAOMI
    override val profile = WebUiProfile(
        type = type,
        brand = "小米 MiWiFi",
        landingPaths = listOf("/", "/cgi-bin/luci/web", "/cgi-bin/luci"),
        loginSteps = listOf(
            webJson(
                "/cgi-bin/luci/api/xqsystem/login",
                """{"username":"{user}","password":"{pass}","logtype":2,"nonce":"0"}""",
            ),
            webForm(
                "/cgi-bin/luci/api/xqsystem/login",
                "username={user}&password={pass}&logtype=2&nonce=0",
            ),
            webForm("/cgi-bin/luci/web", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/cgi-bin/luci/;stok={token}/api/misystem/devicelist"),
            webGet("/cgi-bin/luci/api/misystem/devicelist"),
            webGet("/cgi-bin/luci/;stok={token}/api/misystem/status"),
        ),
        statusRequests = listOf(
            webGet("/cgi-bin/luci/;stok={token}/api/misystem/sys_status"),
            webGet("/cgi-bin/luci/;stok={token}/api/xqsystem/init_info"),
        ),
    )
}

/**
 * Huawei home gateways mix a base64 form login with newer JSON endpoints. The
 * client pages are still plain HTML tables on most HG/WS firmware.
 */
object HuaweiAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.HUAWEI
    override val profile = WebUiProfile(
        type = type,
        brand = "华为 Huawei",
        landingPaths = listOf("/", "/html/ssmp/common/login.asp", "/html/index.asp"),
        tokenPaths = listOf("/asp/GetRandCount.asp"),
        loginSteps = listOf(
            webForm("/login.cgi", "Username={userB64}&Password={passB64}"),
            webForm("/login.cgi", "username={userB64}&password={passB64}"),
            webJson(
                "/api/system/user_login",
                """{"username":"{user}","password":"{pass}"}""",
            ),
            webForm("/html/ssmp/common/login.asp", "Username={userB64}&Password={passB64}"),
        ),
        clientRequests = listOf(
            webGet("/html/ssmp/dhcp/dhcphosts.asp"),
            webGet("/html/ssmp/wlan/wlanclients.asp"),
            webGet("/html/status/landevice.asp"),
            webGet("/html/ssmp/landevice/landevice.asp"),
            webGet("/api/system/deviceinfo"),
        ),
        statusRequests = listOf(
            webGet("/html/ssmp/deviceinfo/deviceinfo.asp"),
            webGet("/html/status/status.asp"),
            webGet("/api/system/deviceinfo"),
        ),
    )
}

/** Tenda's goform endpoints return JSON, which the shared walker reads directly. */
object TendaAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.TENDA
    override val profile = WebUiProfile(
        type = type,
        brand = "腾达 Tenda",
        landingPaths = listOf("/", "/login.html"),
        loginSteps = listOf(
            webForm("/login/Auth", "username={user}&password={pass}"),
            webForm("/goform/login", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/goform/getWirelessClientList"),
            webGet("/goform/getDhcpClientList"),
            webGet("/goform/getOnlineList"),
        ),
        statusRequests = listOf(
            webGet("/goform/getStatus"),
            webGet("/goform/getWanInfo"),
        ),
    )
}

/**
 * iKuai authenticates with an MD5 password and speaks JSON-RPC through
 * `/Action/call`. The monitor calls return the same client fields its console
 * shows, so a single profile covers the common 3.x firmware.
 */
object IkuaiAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.IKUAI
    override val profile = WebUiProfile(
        type = type,
        brand = "爱快 iKuai",
        landingPaths = listOf("/", "/login"),
        loginSteps = listOf(
            webJson("/Action/login", """{"username":"{user}","passwd":"{passMd5}"}"""),
            webJson("/Action/login", """{"username":"{user}","passwd":"{pass}"}"""),
            webForm("/Action/login", "username={user}&passwd={passMd5}"),
        ),
        clientRequests = listOf(
            webJson(
                "/Action/call",
                """{"func_name":"monitor_lanip","action":"show","param":{"TYPE":"data,total","limit":"0,100"}}""",
            ),
            webJson("/Action/call", """{"func_name":"lan","action":"show"}"""),
            webJson("/Action/call", """{"func_name":"monitor_iface","action":"show"}"""),
        ),
        statusRequests = listOf(
            webJson("/Action/call", """{"func_name":"homepage","action":"show"}"""),
        ),
    )
}

/**
 * Ruijie's home routers reuse the LuCI/MiWiFi-style JSON API on many models.
 * The additional `sta_list` endpoint covers firmware that names the client
 * list differently from Xiaomi.
 */
object RuijieAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.RUIJIE
    override val profile = WebUiProfile(
        type = type,
        brand = "锐捷 Ruijie",
        landingPaths = listOf("/", "/cgi-bin/luci/web"),
        loginSteps = listOf(
            webJson(
                "/cgi-bin/luci/api/xqsystem/login",
                """{"username":"{user}","password":"{pass}","logtype":2,"nonce":"0"}""",
            ),
            webForm(
                "/cgi-bin/luci/api/xqsystem/login",
                "username={user}&password={pass}&logtype=2&nonce=0",
            ),
            webForm("/cgi-bin/luci", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/cgi-bin/luci/;stok={token}/api/misystem/devicelist"),
            webGet("/cgi-bin/luci/api/misystem/devicelist"),
            webGet("/cgi-bin/luci/;stok={token}/api/xqsystem/sta_list"),
            webGet("/cgi-bin/luci/;stok={token}/api/misystem/status"),
        ),
        statusRequests = listOf(
            webGet("/cgi-bin/luci/;stok={token}/api/misystem/sys_status"),
            webGet("/cgi-bin/luci/;stok={token}/api/xqsystem/init_info"),
        ),
    )
}

/** H3C Magic and small-business firmware expose a small JSON API plus goform pages. */
object H3cAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.H3C
    override val profile = WebUiProfile(
        type = type,
        brand = "H3C",
        landingPaths = listOf("/", "/login.html", "/index.html"),
        loginSteps = listOf(
            webJson(
                "/api/login",
                """{"username":"{user}","password":"{pass}","remember":true}""",
            ),
            webForm("/api/login", "username={user}&password={pass}"),
            webForm("/login.cgi", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/api/client/list"),
            webGet("/api/device/client"),
            webGet("/api/v1/client"),
            webGet("/goform/getClientList"),
            webGet("/cgi-bin/luci/admin/status/clients"),
        ),
        statusRequests = listOf(
            webGet("/api/system/info"),
            webGet("/api/device/info"),
            webGet("/goform/getStatus"),
        ),
    )
}

/** NETGEAR's stock firmware keeps a few legacy pages plus a newer JSON API. */
object NetgearAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.NETGEAR
    override val profile = WebUiProfile(
        type = type,
        brand = "Netgear",
        landingPaths = listOf("/", "/login.htm", "/login.cgi"),
        loginSteps = listOf(
            webForm("/login.cgi", "submit_flag=login&username={user}&password={pass}"),
            webForm("/login.htm", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/api/device_info"),
            webGet("/DEV_device.htm"),
            webGet("/RST_status.htm"),
            webGet("/cgi-bin/device_info.cgi"),
        ),
        statusRequests = listOf(
            webGet("/api/system_info"),
            webGet("/RST_status.htm"),
        ),
        basicAuth = true,
    )
}

/** D-Link's older DIR firmware still exposes HTML client tables after login. */
object DLinkAdapter : WebUiBrand() {
    override val type: RouterType = RouterType.D_LINK
    override val profile = WebUiProfile(
        type = type,
        brand = "D-Link",
        landingPaths = listOf("/", "/login.php", "/login.html"),
        loginSteps = listOf(
            webForm("/login.php", "username={user}&password={pass}"),
            webForm("/login.cgi", "username={user}&password={pass}"),
        ),
        clientRequests = listOf(
            webGet("/cli.htm"),
            webGet("/DHCPCLIENT.htm"),
            webGet("/status.htm"),
            webGet("/api/client/list"),
        ),
        statusRequests = listOf(
            webGet("/status.htm"),
            webGet("/api/system/info"),
        ),
        basicAuth = true,
    )
}

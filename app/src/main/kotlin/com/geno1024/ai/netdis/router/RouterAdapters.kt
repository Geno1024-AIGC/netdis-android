package com.geno1024.ai.netdis.router

import com.geno1024.ai.netdis.model.RouterType

/**
 * The single place that knows which router adapters exist. Adding a brand is
 * intentionally small: implement [RouterAdapter], add it to [all], and it shows
 * up in the router editor and in AUTO probing automatically.
 *
 * AUTO keeps trying the adapters in order until one reports success. The login
 * page fingerprint is used only to move the likely vendor to the front; a wrong
 * hint costs a few requests, not a failed login.
 */
object RouterAdapters {

    val all: List<RouterAdapter> = listOf(
        OpenWrtAdapter,
        MikroTikAdapter,
        ZteAdapter,
        TpLinkAdapter,
        MercuryAdapter,
        AsusAdapter,
        XiaomiAdapter,
        HuaweiAdapter,
        TendaAdapter,
        IkuaiAdapter,
        RuijieAdapter,
        H3cAdapter,
        NetgearAdapter,
        DLinkAdapter,
    )

    /** Types offered by the router editor's dropdown, newest adapter included. */
    val selectableTypes: List<RouterType> = listOf(RouterType.AUTO) + all.map { it.type }

    fun candidates(type: RouterType, brandHint: String? = null): List<RouterAdapter> {
        if (type == RouterType.UNKNOWN) return emptyList()
        val matching = if (type == RouterType.AUTO) all else all.filter { it.type == type }
        if (type != RouterType.AUTO) return matching
        val preferred = preferredType(brandHint) ?: return matching
        return matching.sortedBy { if (it.type == preferred) 0 else 1 }
    }

    fun preferredType(brandHint: String?): RouterType? {
        val hint = brandHint?.lowercase().orEmpty()
        if (hint.isBlank()) return null
        return when {
            "openwrt" in hint || "luci" in hint -> RouterType.OPENWRT
            "mikrotik" in hint || "routeros" in hint -> RouterType.MIKROTIK
            "zte" in hint || "中兴" in hint || "zxhn" in hint -> RouterType.ZTE
            "tp-link" in hint || "tplink" in hint -> RouterType.TP_LINK
            "mercury" in hint || "水星" in hint -> RouterType.MERCURY
            "asus" in hint || "华硕" in hint -> RouterType.ASUS
            "xiaomi" in hint || "miwifi" in hint || "小米" in hint -> RouterType.XIAOMI
            "huawei" in hint || "华为" in hint -> RouterType.HUAWEI
            "tenda" in hint || "腾达" in hint -> RouterType.TENDA
            "ikuai" in hint || "爱快" in hint -> RouterType.IKUAI
            "ruijie" in hint || "锐捷" in hint -> RouterType.RUIJIE
            "h3c" in hint -> RouterType.H3C
            "netgear" in hint -> RouterType.NETGEAR
            "dlink" in hint || "d-link" in hint -> RouterType.D_LINK
            else -> null
        }
    }
}

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
    )

    /** Types shown as chips in the router editor, newest adapter included. */
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
            else -> null
        }
    }
}

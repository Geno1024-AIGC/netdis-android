package com.geno1024.ai.netdis.update

/**
 * A GitHub "prefix proxy": it serves any GitHub URL when that URL is appended
 * after the prefix, e.g.
 *
 *     https://ghfast.top/https://github.com/owner/repo/releases/download/...
 *
 * These mirrors are third-party, community-run infrastructure with no SLA.
 * Domains appear and disappear often, which is why the list is user-selectable
 * and also accepts an arbitrary custom prefix.
 */
data class Mirror(
    val label: String,
    val prefix: String,
) {
    /** Wraps a GitHub URL for this mirror. Returns it unchanged for [Mirrors.DIRECT]. */
    fun wrap(url: String): String = if (prefix.isEmpty()) url else prefix.trimEnd('/') + "/" + url
}

object Mirrors {

    /** No proxy at all. Usually the most reliable, but can be slow or blocked. */
    val DIRECT = Mirror("直连 GitHub", "")

    /**
     * Only mirrors that have been observed serving a release asset are listed.
     * The custom field on the settings page covers the long tail without a new
     * build whenever one of these stops working.
     */
    val PRESETS: List<Mirror> = listOf(
        DIRECT,
        Mirror("ghfast.top", "https://ghfast.top"),
        Mirror("gh-proxy.com", "https://gh-proxy.com"),
        Mirror("ghproxy.net", "https://ghproxy.net"),
    )

    /** Accepts either a known preset prefix or an arbitrary user-typed one. */
    fun resolve(prefix: String): Mirror {
        val trimmed = prefix.trim()
        PRESETS.firstOrNull { it.prefix == trimmed }?.let { return it }
        return Mirror("自定义", trimmed)
    }
}

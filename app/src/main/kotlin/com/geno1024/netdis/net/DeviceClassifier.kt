package com.geno1024.netdis.net

import com.geno1024.netdis.model.DeviceKind
import com.geno1024.netdis.model.HostRecord

/**
 * Turns the evidence collected by different probes into one best-guess kind.
 * Order matters: explicit service announcements beat port guesses, and a name
 * beats a vendor that makes both phones and routers.
 */
object DeviceClassifier {

    fun classify(host: HostRecord): DeviceKind {
        val name = buildString {
            append(host.hostname.orEmpty())
            append(' ')
            host.services.joinTo(this, " ")
        }.lowercase()
        val services = host.services.joinToString(" ").lowercase()
        val vendor = host.vendor.orEmpty().lowercase()
        val ports = host.openPorts.toSet()

        fun nameContains(vararg needles: String) = needles.any { name.contains(it) }

        if (nameContains("openwrt", "mikrotik", "routeros", "gateway", "router", "路由",
                "miwifi", "tenda", "mercury", "ruijie", "unifi", "edgerouter", "ikuai",
                "pfsense", "opnsense", "asus rt", "rt-", "archer", "nighthawk")
        ) return DeviceKind.ROUTER

        if (services.contains("_printer") || services.contains("_ipp") ||
            ports.any { it in setOf(9100, 631, 515) } ||
            nameContains("printer", "打印", "hp-", "brother", "canon", "epson", "xerox")
        ) return DeviceKind.PRINTER

        if (nameContains("camera", "ipcam", "摄像", "hikvision", "dahua", "reolink",
                "webcam", "doorbell", "门铃")
        ) return DeviceKind.CAMERA

        if (services.contains("_googlecast") || services.contains("_airplay") ||
            nameContains("chromecast", "apple tv", "appletv", "bravia", "roku", "fire tv",
                "firetv", "shield", "tv", "电视", "projector", "投影")
        ) return DeviceKind.TV

        if (services.contains("_sonos") || services.contains("_raop") ||
            nameContains("sonos", "speaker", "音响", "homepod", "echo", "alexa", "soundbar")
        ) return DeviceKind.SPEAKER

        if (services.contains("_smb") || nameContains("nas", "diskstation", "synology",
                "qnap", "truenas", "freenas", "openmediavault", "plex", "unraid")
        ) return DeviceKind.NAS

        if (nameContains("xbox", "playstation", "ps4", "ps5", "nintendo", "switch ") ||
            (ports.contains(3074) && vendor.contains("microsoft"))
        ) return DeviceKind.CONSOLE

        if (nameContains("iphone", "ipad", "android", "redmi", "pixel", "galaxy",
                "oneplus", "oppo", "vivo", "huawei mate", "honor", "手机")
        ) return DeviceKind.PHONE

        if (nameContains("macbook", "imac", "mac mini", "laptop", "desktop", "pc-",
                "thinkpad", "surface", "raspberry", "树莓")
        ) return DeviceKind.COMPUTER

        if (services.contains("_hap") || services.contains("_matter") ||
            services.contains("_esphomelib") || services.contains("_home-assistant") ||
            nameContains("esp", "sonoff", "shelly", "tuya", "hue", "light", "bulb",
                "lamp", "灯", "plug", "插座", "thermostat", "nest", "sensor", "传感器",
                "robot", "扫地", "vacuum", "airpurifier", "净化器")
        ) return DeviceKind.IOT

        if (ports.any { it in setOf(3389, 22, 445, 139) } && !vendor.contains("espressif")) {
            return if (ports.contains(32400) || ports.contains(445) && vendor.contains("synology")) {
                DeviceKind.NAS
            } else {
                DeviceKind.COMPUTER
            }
        }

        if (ports.contains(62078)) return DeviceKind.PHONE
        if (ports.contains(554)) return DeviceKind.CAMERA

        if (Oui.isIotVendor(host.vendor)) return DeviceKind.IOT
        if (host.vendor == "Sonos") return DeviceKind.SPEAKER
        if (host.vendor == "Roku") return DeviceKind.TV
        if (host.vendor == "Nintendo") return DeviceKind.CONSOLE
        if (host.vendor == "Raspberry Pi") return DeviceKind.COMPUTER
        if (host.vendor in setOf("LG", "Sony") && ports.any { it in setOf(8009, 3000, 55000) }) {
            return DeviceKind.TV
        }
        if (host.vendor in setOf("Intel", "Microsoft", "Realtek", "Broadcom", "QEMU")) {
            return DeviceKind.COMPUTER
        }
        if (host.vendor in setOf("Apple", "Samsung", "Xiaomi", "Huawei")) return DeviceKind.PHONE

        return DeviceKind.UNKNOWN
    }

    /**
     * A host that looks like an unmanaged router: web UI open and either a
     * network-vendor MAC or a gateway-ish name. The UI can then suggest adding
     * credentials without pretending it already has them.
     */
    fun isRouterCandidate(host: HostRecord): Boolean {
        if (host.kind == DeviceKind.ROUTER) return true
        val web = host.openPorts.any { it in setOf(80, 443, 8080, 8443) }
        if (!web) return false
        val name = host.hostname.orEmpty().lowercase()
        return Oui.isNetworkVendor(host.vendor) ||
            host.services.any { it.contains("InternetGatewayDevice", ignoreCase = true) } ||
            listOf("router", "gateway", "miwifi", "tenda", "openwrt", "asus", "tp-link")
                .any { name.contains(it) }
    }
}

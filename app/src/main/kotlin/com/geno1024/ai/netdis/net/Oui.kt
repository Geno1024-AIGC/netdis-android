package com.geno1024.ai.netdis.net

/**
 * A deliberately small, hand-checked OUI table. It covers the vendors a home
 * network actually contains; an unknown prefix is reported as unknown rather
 * than guessed, because a wrong vendor is worse than none.
 */
object Oui {

    private val vendors: Map<String, String> = buildMap {
        fun add(vararg entries: Pair<String, String>) = entries.forEach { (oui, name) ->
            put(oui.uppercase(), name)
        }

        add(
            "000393" to "Apple", "000A27" to "Apple", "001B63" to "Apple",
            "001E52" to "Apple", "001EC2" to "Apple", "002312" to "Apple",
            "002500" to "Apple", "0026BB" to "Apple", "28CFE9" to "Apple",
            "3C0754" to "Apple", "406C8F" to "Apple", "4860BC" to "Apple",
            "600308" to "Apple", "68AB1E" to "Apple", "7C6D62" to "Apple",
            "8C8590" to "Apple", "90B0ED" to "Apple", "A483E7" to "Apple",
            "ACBC32" to "Apple", "B8782E" to "Apple", "D0034B" to "Apple",
            "DCA904" to "Apple", "F01898" to "Apple", "F0DBE2" to "Apple",
            "F0B0E7" to "Apple", "BC52B7" to "Apple", "F40616" to "Apple",
        )
        add(
            "0012FB" to "Samsung", "001599" to "Samsung", "001632" to "Samsung",
            "08373D" to "Samsung", "28395E" to "Samsung", "342387" to "Samsung",
            "38AA3C" to "Samsung", "400E85" to "Samsung", "444E1A" to "Samsung",
            "5C0A5B" to "Samsung", "781FDB" to "Samsung", "8C7712" to "Samsung",
            "A02195" to "Samsung", "C0BDD1" to "Samsung", "E8508B" to "Samsung",
            "F47B5E" to "Samsung", "50CCF8" to "Samsung", "D0C1B1" to "Samsung",
        )
        add(
            "009EC8" to "Xiaomi", "04CF8C" to "Xiaomi", "0C1DAF" to "Xiaomi",
            "102AB3" to "Xiaomi", "14F65A" to "Xiaomi", "185936" to "Xiaomi",
            "2082C0" to "Xiaomi", "24DA9B" to "Xiaomi", "286C07" to "Xiaomi",
            "3480B3" to "Xiaomi", "38A4ED" to "Xiaomi", "3CBDD8" to "Xiaomi",
            "40313C" to "Xiaomi", "4C49E3" to "Xiaomi", "508F4C" to "Xiaomi",
            "584498" to "Xiaomi", "5C0214" to "Xiaomi", "640980" to "Xiaomi",
            "64B473" to "Xiaomi", "68DFDD" to "Xiaomi", "6CF784" to "Xiaomi",
            "742344" to "Xiaomi", "7811DC" to "Xiaomi", "7C1DD9" to "Xiaomi",
            "8CBEBE" to "Xiaomi", "9078B2" to "Xiaomi", "98FAE3" to "Xiaomi",
            "9C99A0" to "Xiaomi", "A086C6" to "Xiaomi", "A4DA22" to "Xiaomi",
            "ACC1EE" to "Xiaomi", "B0E235" to "Xiaomi", "C40BCB" to "Xiaomi",
            "C8AD59" to "Xiaomi", "CC2D1B" to "Xiaomi", "D4970B" to "Xiaomi",
            "E4AAEC" to "Xiaomi", "F0B429" to "Xiaomi", "F48B32" to "Xiaomi",
            "FC64BA" to "Xiaomi",
        )
        add(
            "009ACD" to "Huawei", "04BD70" to "Huawei", "0819A6" to "Huawei",
            "104780" to "Huawei", "18C58A" to "Huawei", "200BC7" to "Huawei",
            "246968" to "Huawei", "283CE4" to "Huawei", "2C55D3" to "Huawei",
            "308730" to "Huawei", "346BD3" to "Huawei", "38F889" to "Huawei",
            "3CDFBD" to "Huawei", "404D8E" to "Huawei", "48437C" to "Huawei",
            "4C1FCC" to "Huawei", "509F27" to "Huawei", "5425EA" to "Huawei",
            "581FAA" to "Huawei", "5C4CA9" to "Huawei", "60DE44" to "Huawei",
            "643E8C" to "Huawei", "6889C4" to "Huawei", "6C92BF" to "Huawei",
            "70723C" to "Huawei", "74882A" to "Huawei", "78D752" to "Huawei",
            "7C11BE" to "Huawei", "80FB06" to "Huawei", "84A8E4" to "Huawei",
            "8828B3" to "Huawei", "8C34FD" to "Huawei", "90671C" to "Huawei",
            "94049C" to "Huawei", "98E7F5" to "Huawei", "9C28EF" to "Huawei",
            "A08CF8" to "Huawei", "A49947" to "Huawei", "A8F5E1" to "Huawei",
            "AC4E91" to "Huawei", "B05B67" to "Huawei", "B41513" to "Huawei",
            "B808CF" to "Huawei", "BC25E0" to "Huawei", "C07009" to "Huawei",
            "C40528" to "Huawei", "C894BB" to "Huawei", "CCA223" to "Huawei",
            "D07AB5" to "Huawei", "D46AA8" to "Huawei", "D8492F" to "Huawei",
            "DCD2FC" to "Huawei", "E0247F" to "Huawei", "E4A7C5" to "Huawei",
            "E8088B" to "Huawei", "EC233D" to "Huawei", "F04347" to "Huawei",
            "F4559C" to "Huawei", "F80113" to "Huawei", "FC48EF" to "Huawei",
        )
        add(
            "000AEB" to "TP-Link", "001D0F" to "TP-Link", "0023CD" to "TP-Link",
            "002586" to "TP-Link", "002719" to "TP-Link", "085700" to "TP-Link",
            "0C8063" to "TP-Link", "1027F5" to "TP-Link", "10FEED" to "TP-Link",
            "14CC20" to "TP-Link", "18A6F7" to "TP-Link", "1C3BF3" to "TP-Link",
            "1C61B4" to "TP-Link", "30B5C2" to "TP-Link", "34E894" to "TP-Link",
            "388345" to "TP-Link", "3C46D8" to "TP-Link", "3C846A" to "TP-Link",
            "40169F" to "TP-Link", "4494FC" to "TP-Link", "482254" to "TP-Link",
            "4CE173" to "TP-Link", "50642B" to "TP-Link", "50C7BF" to "TP-Link",
            "54C80F" to "TP-Link", "584120" to "TP-Link", "5C63BF" to "TP-Link",
            "5CE931" to "TP-Link", "6032B1" to "TP-Link", "60E327" to "TP-Link",
            "645601" to "TP-Link", "6466B3" to "TP-Link", "68FF7B" to "TP-Link",
            "6C5AB0" to "TP-Link", "704F57" to "TP-Link", "74DA88" to "TP-Link",
            "788CB5" to "TP-Link", "7C8BCA" to "TP-Link", "80EA96" to "TP-Link",
            "8416F9" to "TP-Link", "882593" to "TP-Link", "8C210A" to "TP-Link",
            "90F652" to "TP-Link", "940C6D" to "TP-Link", "98DAC4" to "TP-Link",
            "9CA2F4" to "TP-Link", "A040A0" to "TP-Link", "A42BB0" to "TP-Link",
            "A8574E" to "TP-Link", "AC15A2" to "TP-Link", "AC84C6" to "TP-Link",
            "B0487A" to "TP-Link", "B04E26" to "TP-Link", "B4B024" to "TP-Link",
            "B8F883" to "TP-Link", "BC4699" to "TP-Link", "C006C3" to "TP-Link",
            "C025E9" to "TP-Link", "C46E1F" to "TP-Link", "C4E984" to "TP-Link",
            "C80E14" to "TP-Link", "CC32E5" to "TP-Link", "D0768F" to "TP-Link",
            "D46E5C" to "TP-Link", "D807B6" to "TP-Link", "E005C5" to "TP-Link",
            "E4C32A" to "TP-Link", "E894F6" to "TP-Link", "EC086B" to "TP-Link",
            "EC172F" to "TP-Link", "F483CD" to "TP-Link", "F4F26D" to "TP-Link",
            "F832E4" to "TP-Link", "FC7C02" to "TP-Link",
        )
        add(
            "000C6E" to "ASUS", "000EA6" to "ASUS", "00112F" to "ASUS",
            "0013D4" to "ASUS", "0015F2" to "ASUS", "001731" to "ASUS",
            "0018F3" to "ASUS", "001BFC" to "ASUS", "001D60" to "ASUS",
            "001E8C" to "ASUS", "001FC6" to "ASUS", "002215" to "ASUS",
            "00248C" to "ASUS", "002618" to "ASUS", "04D4C4" to "ASUS",
            "08606E" to "ASUS", "086266" to "ASUS", "0C9D92" to "ASUS",
            "107B44" to "ASUS", "10BF48" to "ASUS", "10C37B" to "ASUS",
            "14DAE9" to "ASUS", "1831BF" to "ASUS", "1C872C" to "ASUS",
            "20CF30" to "ASUS", "244BFE" to "ASUS", "2C56DC" to "ASUS",
            "2CFDA1" to "ASUS", "305A3A" to "ASUS", "3497F6" to "ASUS",
            "382C4A" to "ASUS", "38D547" to "ASUS", "3C7C3F" to "ASUS",
            "40167E" to "ASUS", "448A5B" to "ASUS", "485B39" to "ASUS",
            "4CEDFB" to "ASUS", "50465D" to "ASUS", "50EBF6" to "ASUS",
            "5404A6" to "ASUS", "581122" to "ASUS", "6045CB" to "ASUS",
            "704D7B" to "ASUS", "74D02B" to "ASUS", "7824AF" to "ASUS",
            "7C10C9" to "ASUS", "88D7F6" to "ASUS", "9C5C8E" to "ASUS",
            "A85E45" to "ASUS", "AC220B" to "ASUS", "B06EBF" to "ASUS",
            "BCAEC5" to "ASUS", "C86000" to "ASUS",
            "D017C2" to "ASUS", "D850E6" to "ASUS", "E03F49" to "ASUS",
        )
        add(
            "00095B" to "Netgear", "00146C" to "Netgear", "001B2F" to "Netgear",
            "001F33" to "Netgear", "0024B2" to "Netgear", "0836C9" to "Netgear",
            "204E7F" to "Netgear", "28C68E" to "Netgear", "2CB05D" to "Netgear",
            "4C60DE" to "Netgear", "9C3DCF" to "Netgear", "A06391" to "Netgear",
            "C03F0E" to "Netgear", "E0469A" to "Netgear", "E4F4C6" to "Netgear",
        )
        add(
            "00055D" to "D-Link", "000D88" to "D-Link", "001195" to "D-Link",
            "00179A" to "D-Link", "001B11" to "D-Link", "001E58" to "D-Link",
            "002191" to "D-Link", "002401" to "D-Link", "1C7EE5" to "D-Link",
            "28107B" to "D-Link", "3C1E04" to "D-Link", "5CD998" to "D-Link",
            "744401" to "D-Link", "78321B" to "D-Link", "84C9B2" to "D-Link",
            "9094E4" to "D-Link", "B8A386" to "D-Link", "C4A81D" to "D-Link",
            "CCB255" to "D-Link",
        )
        add(
            "000C42" to "MikroTik", "085531" to "MikroTik", "18FD74" to "MikroTik",
            "2CC81B" to "MikroTik", "488F5A" to "MikroTik", "4C5E0C" to "MikroTik",
            "64D154" to "MikroTik", "6C3B6B" to "MikroTik", "744D28" to "MikroTik",
            "789A18" to "MikroTik", "CC2DE0" to "MikroTik", "DC2C6E" to "MikroTik",
            "E48D8C" to "MikroTik", "F41E57" to "MikroTik",
        )
        add(
            "00156D" to "Ubiquiti", "002722" to "Ubiquiti", "0418D6" to "Ubiquiti",
            "245A4C" to "Ubiquiti", "44D9E7" to "Ubiquiti", "68D79A" to "Ubiquiti",
            "7483C2" to "Ubiquiti", "784558" to "Ubiquiti", "788A20" to "Ubiquiti",
            "802AA8" to "Ubiquiti", "9C05D6" to "Ubiquiti", "AC8BA9" to "Ubiquiti",
            "B4FBE4" to "Ubiquiti", "D021F9" to "Ubiquiti", "DC9FDB" to "Ubiquiti",
            "E063DA" to "Ubiquiti", "F09FC2" to "Ubiquiti", "FCECDA" to "Ubiquiti",
        )
        add(
            "240AC4" to "Espressif", "246F28" to "Espressif", "2462AB" to "Espressif",
            "30AEA4" to "Espressif", "3C6105" to "Espressif", "3C71BF" to "Espressif",
            "483FDA" to "Espressif", "4C11AE" to "Espressif", "545AA6" to "Espressif",
            "5CCF7F" to "Espressif", "600194" to "Espressif", "68C63A" to "Espressif",
            "7C9EBD" to "Espressif", "807D3A" to "Espressif", "840D8E" to "Espressif",
            "84F3EB" to "Espressif", "8CAAB5" to "Espressif", "A020A6" to "Espressif",
            "A4CF12" to "Espressif", "AC67B2" to "Espressif", "B4E62D" to "Espressif",
            "B8F009" to "Espressif", "BCDDC2" to "Espressif", "C44F33" to "Espressif",
            "C82B96" to "Espressif", "CC50E3" to "Espressif", "D8A01D" to "Espressif",
            "DC4F22" to "Espressif", "E868E7" to "Espressif", "ECFABC" to "Espressif",
            "F008D1" to "Espressif", "F4CFA2" to "Espressif", "FCF5C4" to "Espressif",
        )
        add(
            "001A11" to "Google", "3C5AB4" to "Google", "546009" to "Google",
            "6CADF8" to "Google", "94EB2C" to "Google", "F4F5D8" to "Google",
            "A47733" to "Google", "D86C63" to "Google", "F0EF86" to "Google",
            "18B430" to "Google Nest", "641666" to "Google Nest",
        )
        add(
            "44650D" to "Amazon", "6837E9" to "Amazon", "74C246" to "Amazon",
            "84D6D0" to "Amazon", "F0272D" to "Amazon", "0C47C9" to "Amazon",
            "38F73D" to "Amazon", "50DCE7" to "Amazon", "6854FD" to "Amazon",
            "78E103" to "Amazon",
        )
        add(
            "000E58" to "Sonos", "347E5C" to "Sonos", "48A6B8" to "Sonos",
            "5CAAFD" to "Sonos", "7828CA" to "Sonos", "949F3E" to "Sonos",
            "B8E937" to "Sonos", "D43A2E" to "Sonos",
        )
        add(
            "001788" to "Philips Hue", "001A22" to "Philips",
            "ECB5FA" to "Philips Hue",
        )
        add(
            "000D4B" to "Roku", "AC3A7A" to "Roku", "B0A737" to "Roku",
            "CC6DA0" to "Roku", "D83134" to "Roku",
        )
        add(
            "0013A9" to "Sony", "001DBA" to "Sony", "30F9ED" to "Sony",
            "544249" to "Sony", "78843C" to "Sony", "FC0FE6" to "Sony",
            "0024BE" to "Sony", "0019C1" to "Sony", "001A80" to "Sony",
            "001EA9" to "Sony", "0023F1" to "Sony",
        )
        add(
            "001C62" to "LG", "001E75" to "LG", "0022A9" to "LG",
            "002483" to "LG", "0026E2" to "LG", "10F96F" to "LG",
            "2C54CF" to "LG", "3CCD57" to "LG", "40B034" to "LG",
            "58A2B5" to "LG", "6C4008" to "LG", "88366C" to "LG",
            "A816B2" to "LG", "C4366C" to "LG", "CC2D8C" to "LG",
            "E85B5B" to "LG",
        )
        add(
            "00125A" to "Microsoft", "00155D" to "Microsoft", "0017FA" to "Microsoft",
            "001DD8" to "Microsoft", "002248" to "Microsoft", "0025AE" to "Microsoft",
            "281878" to "Microsoft", "3C8375" to "Microsoft", "5882A8" to "Microsoft",
            "7C1E52" to "Microsoft", "985FD3" to "Microsoft", "C83F26" to "Microsoft",
        )
        add(
            "001B21" to "Intel", "001E67" to "Intel", "00216A" to "Intel",
            "0024D7" to "Intel", "3413E8" to "Intel", "3CA9F4" to "Intel",
            "448500" to "Intel", "4C3488" to "Intel", "58946B" to "Intel",
            "5C514F" to "Intel", "681729" to "Intel", "7C7A91" to "Intel",
            "8086F2" to "Intel", "8C1645" to "Intel", "94652D" to "Intel",
            "9CB6D0" to "Intel", "A0369F" to "Intel", "A4BB6D" to "Intel",
            "B46BFC" to "Intel", "C8F750" to "Intel", "D8FC93" to "Intel",
            "E4A471" to "Intel", "F81654" to "Intel",
        )
        add(
            "B827EB" to "Raspberry Pi", "DCA632" to "Raspberry Pi",
            "E45F01" to "Raspberry Pi", "28CDC1" to "Raspberry Pi",
            "2CCF67" to "Raspberry Pi",
        )
        add(
            "0009BF" to "Nintendo", "0017AB" to "Nintendo", "001BEA" to "Nintendo",
            "001E35" to "Nintendo", "0021BD" to "Nintendo", "0022AA" to "Nintendo",
            "0023CC" to "Nintendo", "0024F3" to "Nintendo", "002659" to "Nintendo",
            "34AF2C" to "Nintendo", "40D28A" to "Nintendo", "58BDA3" to "Nintendo",
            "78A2A0" to "Nintendo", "98B6E9" to "Nintendo", "B8AE6E" to "Nintendo",
            "E0E751" to "Nintendo",
        )
        add(
            "001132" to "Synology", "245EBE" to "Synology", "9009D0" to "Synology",
        )
        add(
            "001B78" to "HP", "001F29" to "HP", "00215A" to "HP",
            "00237D" to "HP", "3CD92B" to "HP", "6C3BE5" to "HP",
            "9457A5" to "HP",
        )
        add(
            "008077" to "Brother", "30055C" to "Brother", "3C2AF4" to "Brother",
            "000085" to "Canon", "001E8F" to "Canon", "2C9EFC" to "Canon",
            "888717" to "Canon", "000048" to "Epson", "44D244" to "Epson",
            "64EB8C" to "Epson", "A4EE57" to "Epson",
        )
        add(
            "4CBD8F" to "Hikvision", "54C415" to "Hikvision", "8CE748" to "Hikvision",
            "A41437" to "Hikvision", "C056E3" to "Hikvision", "C42F90" to "Hikvision",
            "E0CA3C" to "Hikvision", "3CEF8C" to "Dahua", "4C11BF" to "Dahua",
            "9002A9" to "Dahua", "A0BD1D" to "Dahua", "B44C3B" to "Dahua",
        )
        add(
            "84CCA8" to "Shelly", "C45BBE" to "Shelly", "D073D5" to "LIFX",
            "2CAA8E" to "Wyze", "7C78B2" to "Wyze", "D03F27" to "Wyze",
            "446132" to "Ecobee", "501479" to "iRobot",
            "C83A35" to "Tenda", "00B00C" to "Tenda", "50D2F5" to "Tenda",
        )
        add(
            "000FE2" to "H3C", "3C8C40" to "H3C", "00749C" to "Ruijie",
            "2C3A28" to "Ruijie", "0007AB" to "ZTE", "34E0CF" to "ZTE",
            "5C5B35" to "ZTE",
        )
        add(
            "00E04C" to "Realtek", "525400" to "QEMU", "001018" to "Broadcom",
            "001BE9" to "Broadcom",
        )
    }

    fun vendorFor(mac: String?): String? {
        val normalized = ArpTable.normalizeMac(mac) ?: return null
        val key = normalized.replace(":", "").take(6).uppercase()
        return vendors[key]
    }

    /** Vendors whose boxes are almost always routers, access points or switches. */
    fun isNetworkVendor(vendor: String?): Boolean = vendor in setOf(
        "TP-Link", "ASUS", "Netgear", "D-Link", "MikroTik", "Ubiquiti",
        "Tenda", "H3C", "Ruijie", "ZTE", "Huawei", "Xiaomi",
    )

    /** Vendors that make small always-on gadgets rather than user devices. */
    fun isIotVendor(vendor: String?): Boolean = vendor in setOf(
        "Espressif", "Shelly", "LIFX", "Philips Hue", "Tuya", "Google Nest",
        "Ecobee", "Wyze",
    )

    fun entryCount(): Int = vendors.size
}

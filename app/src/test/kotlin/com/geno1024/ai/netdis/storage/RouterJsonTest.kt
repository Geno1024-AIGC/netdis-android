package com.geno1024.ai.netdis.storage

import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterJsonTest {

    private val vault = InMemoryVault()

    private val configs = listOf(
        RouterConfig(
            id = "gw",
            label = "客厅主路由",
            host = "192.168.1.1",
            scheme = "http",
            port = 80,
            type = RouterType.OPENWRT,
            username = "root",
            password = "hunter2",
            enabled = true,
            note = "一楼",
        ),
        RouterConfig(
            id = "study",
            label = "书房",
            host = "192.168.2.1",
            scheme = "https",
            port = 443,
            type = RouterType.MIKROTIK,
            username = "admin",
            password = "s3cret!",
            enabled = false,
        ),
    )

    @Test
    fun `round-trips every field`() {
        val text = RouterJson.encode(configs, vault::seal)
        assertEquals(configs, RouterJson.decode(text, vault::unseal))
    }

    @Test
    fun `never writes a readable password`() {
        val text = RouterJson.encode(configs, vault::seal)
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("s3cret!"))
        assertTrue(text.contains("password"))
    }

    @Test
    fun `an unreadable token yields an empty password instead of failing`() {
        val text = RouterJson.encode(configs, vault::seal)
        val decoded = RouterJson.decode(text) { null }
        assertEquals(2, decoded.size)
        assertEquals("", decoded[0].password)
        assertEquals("root", decoded[0].username)
        assertEquals("192.168.2.1", decoded[1].host)
    }

    @Test
    fun `survives corrupt storage`() {
        assertEquals(emptyList<RouterConfig>(), RouterJson.decode("", vault::unseal))
        assertEquals(emptyList<RouterConfig>(), RouterJson.decode("not json", vault::unseal))
        assertEquals(emptyList<RouterConfig>(), RouterJson.decode("{}", vault::unseal))
        assertEquals(
            emptyList<RouterConfig>(),
            RouterJson.decode("""[{"id":"x"},{"label":"no id"},{"id":"y","host":""}]""", vault::unseal),
        )
    }

    @Test
    fun `falls back to safe defaults for unknown enums`() {
        val decoded = RouterJson.decode(
            """[{"id":"a","host":"10.0.0.1","type":"MAGIC","port":99999,"scheme":""}]""",
            vault::unseal,
        ).single()
        assertEquals(RouterType.AUTO, decoded.type)
        assertEquals(65535, decoded.port)
        assertEquals("http", decoded.scheme)
        assertTrue(decoded.enabled)
        assertEquals("", decoded.password)
    }

    @Test
    fun `empty password stays empty`() {
        val text = RouterJson.encode(
            listOf(RouterConfig(id = "a", label = "", host = "10.0.0.1")),
            vault::seal,
        )
        assertEquals("", RouterJson.decode(text, vault::unseal).single().password)
    }

    @Test
    fun `round-trips and normalizes the router MAC`() {
        val text = RouterJson.encode(
            listOf(
                RouterConfig(
                    id = "zte",
                    label = "二楼",
                    host = "192.168.2.1",
                    type = RouterType.ZTE,
                    mac = "D4B7099307D2",
                ),
            ),
            vault::seal,
        )
        val decoded = RouterJson.decode(text, vault::unseal).single()
        assertEquals("d4:b7:09:93:07:d2", decoded.mac)
        assertEquals(RouterType.ZTE, decoded.type)
    }

    @Test
    fun `a blank or malformed MAC decodes as absent`() {
        val decoded = RouterJson.decode(
            """[{"id":"a","host":"10.0.0.1","mac":""},{"id":"b","host":"10.0.0.2","mac":"nope"}]""",
            vault::unseal,
        )
        assertNull(decoded[0].mac)
        assertNull(decoded[1].mac)
    }
}

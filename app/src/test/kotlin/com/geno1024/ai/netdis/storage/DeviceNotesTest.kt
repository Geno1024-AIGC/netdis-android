package com.geno1024.ai.netdis.storage

import com.geno1024.ai.netdis.model.HostRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNotesTest {

    private val host = HostRecord(
        ip = "192.168.1.10",
        mac = "AA:BB:CC:DD:EE:FF",
        hostname = "stock-name",
    )

    @Test
    fun `matches by MAC even when the lease moves to another address`() {
        val note = DeviceNote(
            mac = "aa-bb-cc-dd-ee-ff",
            ip = "192.168.1.99",
            alias = "客厅电视",
            note = "接在交换机 3 口",
        )
        assertTrue(note.matches(host.copy(ip = "192.168.1.42")))
    }

    @Test
    fun `falls back to the IP when the MAC is unavailable`() {
        val note = DeviceNote(ip = "192.168.1.10", alias = "旧平板")
        assertTrue(note.matches(host.copy(mac = null)))
        assertFalse(note.matches(host.copy(ip = "192.168.1.11", mac = null)))
    }

    @Test
    fun `applies the label over the discovered hostname`() {
        val applied = DeviceNote(ip = host.ip, alias = "卧室空调", note = "格力").applyTo(host)
        assertEquals("卧室空调", applied.alias)
        assertEquals("卧室空调", applied.displayName)
        assertEquals("格力", applied.note)
        // The original discovery stays visible under the custom name.
        assertEquals("stock-name", applied.hostname)
    }

    @Test
    fun `round-trips notes and normalises MAC spellings`() {
        val notes = listOf(
            DeviceNote(
                mac = "AA-BB-CC-DD-EE-FF",
                ip = "192.168.1.10",
                alias = "电视",
                note = "客厅",
                updatedAt = 42,
            ),
            // An entry without a label is not worth persisting.
            DeviceNote(ip = "192.168.1.20"),
        )
        val text = DeviceNotesJson.encode(notes)
        assertFalse(text.contains("192.168.1.20"))

        val decoded = DeviceNotesJson.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("aa:bb:cc:dd:ee:ff", decoded.first().mac)
        assertEquals("192.168.1.10", decoded.first().ip)
        assertEquals("电视", decoded.first().alias)
        assertEquals("客厅", decoded.first().note)
        assertEquals(42, decoded.first().updatedAt)
    }

    @Test
    fun `drops corrupt JSON and unnamed entries instead of failing`() {
        assertEquals(emptyList<DeviceNote>(), DeviceNotesJson.decode("not json"))
        assertEquals(emptyList<DeviceNote>(), DeviceNotesJson.decode(""))
        val decoded = DeviceNotesJson.decode(
            """
            [
              {"ip": "", "alias": "no address"},
              {"ip": "192.168.1.5", "alias": "", "note": ""},
              {"ip": "192.168.1.6", "alias": "kept", "mac": "zz:zz"}
            ]
            """.trimIndent(),
        )
        assertEquals(1, decoded.size)
        assertEquals("192.168.1.6", decoded.single().ip)
        assertEquals("kept", decoded.single().alias)
        assertNull(decoded.single().mac)
    }
}

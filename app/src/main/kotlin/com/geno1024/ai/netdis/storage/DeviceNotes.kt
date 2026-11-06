package com.geno1024.ai.netdis.storage

import android.content.Context
import com.geno1024.ai.netdis.model.HostRecord
import com.geno1024.ai.netdis.net.ArpTable
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user's private label for a device. MAC is the stable key; the IP is kept as
 * a fallback for networks or Android versions where the MAC is unavailable.
 */
data class DeviceNote(
    val mac: String? = null,
    val ip: String = "",
    val alias: String = "",
    val note: String = "",
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = alias.isBlank() && note.isBlank()

    fun matches(host: HostRecord): Boolean {
        val hostMac = host.mac?.let(ArpTable::normalizeMac)
        val noteMac = mac?.let(ArpTable::normalizeMac)
        return (hostMac != null && noteMac == hostMac) || (ip.isNotBlank() && ip == host.ip)
    }

    /** Returns a copy of [host] with the user's label and note applied. */
    fun applyTo(host: HostRecord): HostRecord = host.copy(
        alias = alias.takeIf { it.isNotBlank() },
        note = note.takeIf { it.isNotBlank() },
    )
}

/** JSON for device notes, separate from the Android preference wrapper for tests. */
object DeviceNotesJson {

    fun encode(notes: List<DeviceNote>): String {
        val array = JSONArray()
        for (note in notes) {
            if (note.isEmpty) continue
            array.put(
                JSONObject().apply {
                    note.mac?.takeIf { it.isNotBlank() }?.let { put("mac", it) }
                    put("ip", note.ip)
                    put("alias", note.alias)
                    put("note", note.note)
                    put("updatedAt", note.updatedAt)
                },
            )
        }
        return array.toString()
    }

    fun decode(text: String): List<DeviceNote> {
        if (text.isBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val result = ArrayList<DeviceNote>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val ip = entry.optString("ip").takeIf { it.isNotBlank() } ?: continue
            val mac = entry.optString("mac").takeIf { it.isNotBlank() }
                ?.let(ArpTable::normalizeMac)
            val note = DeviceNote(
                mac = mac,
                ip = ip,
                alias = entry.optString("alias").trim(),
                note = entry.optString("note").trim(),
                updatedAt = entry.optLong("updatedAt", 0),
            )
            if (!note.isEmpty) result += note
        }
        return result
    }
}

/**
 * Persists notes in app-private preferences. Notes are applied to a scan
 * snapshot before [com.geno1024.ai.netdis.topo.TopologyBuilder] runs, so the
 * graph label, the device list and the detail sheet all agree.
 */
class DeviceNotesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<DeviceNote> = DeviceNotesJson.decode(prefs.getString(KEY, null).orEmpty())

    @Synchronized
    fun find(host: HostRecord): DeviceNote? {
        val notes = load()
        return find(host, notes)
    }

    /**
     * Applies the best matching note; header labels prefer MAC over IP. A host
     * without a matching note has stale labels cleared, which is what makes
     * deleting a note update an already-rendered snapshot.
     */
    fun apply(host: HostRecord, notes: List<DeviceNote> = load()): HostRecord {
        val note = find(host, notes)
        return host.copy(
            alias = note?.alias?.takeIf { it.isNotBlank() },
            note = note?.note?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Creates, updates or removes a note. Clearing both fields deletes the
     * entry, which is how the detail sheet's "清除备注" button works.
     */
    @Synchronized
    fun save(host: HostRecord, alias: String, note: String) {
        val notes = load().toMutableList()
        val matches = notes.filter { it.matches(host) }
        notes.removeAll(matches)
        val cleanAlias = alias.trim()
        val cleanNote = note.trim()
        if (cleanAlias.isNotEmpty() || cleanNote.isNotEmpty()) {
            val hostMac = host.mac?.let(ArpTable::normalizeMac)
            notes += DeviceNote(
                mac = hostMac ?: matches.firstNotNullOfOrNull { it.mac },
                ip = host.ip,
                alias = cleanAlias,
                note = cleanNote,
                updatedAt = System.currentTimeMillis(),
            )
        }
        write(notes)
    }

    @Synchronized
    fun remove(host: HostRecord) {
        write(load().filterNot { it.matches(host) })
    }

    private fun find(host: HostRecord, notes: List<DeviceNote>): DeviceNote? {
        val hostMac = host.mac?.let(ArpTable::normalizeMac)
        if (hostMac != null) {
            notes.firstOrNull { it.mac == hostMac }?.let { return it }
        }
        return notes.firstOrNull { it.ip == host.ip }
    }

    private fun write(notes: List<DeviceNote>) {
        prefs.edit().putString(KEY, DeviceNotesJson.encode(notes)).apply()
    }

    private companion object {
        const val PREFS = "netdis.device-notes"
        const val KEY = "entries.v1"
    }
}

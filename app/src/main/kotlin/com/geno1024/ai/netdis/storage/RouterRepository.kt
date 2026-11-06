package com.geno1024.ai.netdis.storage

import android.content.Context
import com.geno1024.ai.netdis.model.RouterConfig
import com.geno1024.ai.netdis.model.RouterType
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for the configured routers. Passwords are handed to [seal] before they
 * are written, so a dump of the preferences file never contains a readable
 * credential.
 */
object RouterJson {

    fun encode(configs: List<RouterConfig>, seal: (String) -> String): String {
        val array = JSONArray()
        for (config in configs) {
            array.put(
                JSONObject().apply {
                    put("id", config.id)
                    put("label", config.label)
                    put("host", config.host)
                    put("scheme", config.scheme)
                    put("port", config.port)
                    put("type", config.type.name)
                    put("username", config.username)
                    put("password", seal(config.password))
                    put("enabled", config.enabled)
                    put("note", config.note)
                },
            )
        }
        return array.toString()
    }

    fun decode(text: String, unseal: (String) -> String?): List<RouterConfig> {
        if (text.isBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val result = ArrayList<RouterConfig>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: continue
            val host = entry.optString("host").takeIf { it.isNotBlank() } ?: continue
            val token = entry.optString("password")
            result += RouterConfig(
                id = id,
                label = entry.optString("label"),
                host = host,
                scheme = entry.optString("scheme").ifBlank { "http" },
                port = entry.optInt("port", 0).coerceIn(0, 65535),
                type = RouterType.entries.firstOrNull { it.name == entry.optString("type") }
                    ?: RouterType.AUTO,
                username = entry.optString("username"),
                password = if (token.isBlank()) "" else unseal(token).orEmpty(),
                enabled = entry.optBoolean("enabled", true),
                note = entry.optString("note"),
            )
        }
        return result
    }
}

class RouterRepository(
    context: Context,
    private val vault: SecretVault = CredentialVault(),
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<RouterConfig> =
        RouterJson.decode(prefs.getString(KEY_ITEMS, null).orEmpty(), vault::unseal)

    fun save(configs: List<RouterConfig>) {
        prefs.edit()
            .putString(KEY_ITEMS, RouterJson.encode(configs, vault::seal))
            .apply()
    }

    private companion object {
        const val PREFS = "netdis.routers"
        const val KEY_ITEMS = "items.v1"
    }
}

package com.dreamdisplayx.util

import com.dreamdisplayx.util.json.DreamJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Normalizes a Bilibili cookie from a raw header, a JSON object, or a browser export.
 * Values are credentials: callers must never log the result.
 */
object BilibiliCookie {
    private val ATTRIBUTES = setOf(
        "path", "expires", "max-age", "domain", "samesite", "secure", "httponly", "version", "comment",
    )

    private val KNOWN_NAMES = setOf(
        "sessdata", "bili_jct", "dedeuserid", "dedeuserid__ckmd5", "sid",
        "buvid3", "buvid4", "b_nut", "buvid_fp", "_uuid", "bili_ticket",
        "current_fnval", "current_quality", "rpd", "bsource", "fingerprint", "fingerprint2",
    )

    /** Returns a `name=value; ...` cookie header, or an empty string for unusable input. */
    fun parse(text: String?): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""

        if (trimmed.startsWith("{")) {
            val fromJson = fromJson(trimmed)
            if (fromJson.isNotEmpty()) return join(fromJson)
            val scanned = scanKnownPairs(trimmed)
            if (scanned.isNotEmpty()) return join(scanned)
        }
        return join(fromPairs(trimmed))
    }

    /** Merges several cookie sources, keeping the first value for each cookie name. */
    fun merge(vararg values: String?): String =
        parse(values.filterNotNull().joinToString("; "))

    /** True when [cookie] contains a non-empty SESSDATA value. */
    fun hasSession(cookie: String?): Boolean =
        parse(cookie).split(';').any { part ->
            val pair = part.trim()
            pair.substringBefore('=').equals("SESSDATA", ignoreCase = true) &&
                pair.substringAfter('=', "").isNotBlank()
        }

    private fun fromJson(text: String): Map<String, String> {
        val root = runCatching { DreamJson.compact.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()

        (root["cookies"] as? JsonObject)?.let { collect(it, out, knownOnly = false) }
        if (out.isEmpty()) {
            for (value in root.values) {
                val nested = value as? JsonObject ?: continue
                val cookies = nested["cookies"] as? JsonObject ?: continue
                collect(cookies, out, knownOnly = false)
            }
        }
        if (out.isEmpty()) collect(root, out, knownOnly = true)
        return out
    }

    private fun collect(source: JsonObject, out: MutableMap<String, String>, knownOnly: Boolean) {
        for ((name, element) in source) {
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val key = name.trim()
            if (key.isEmpty() || value.isBlank()) continue
            if (key.lowercase() in ATTRIBUTES) continue
            if (knownOnly && key.lowercase() !in KNOWN_NAMES) continue
            out.putIfAbsent(key, clean(value))
        }
    }

    private fun scanKnownPairs(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val matcher = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text)
        for (match in matcher) {
            val name = match.groupValues[1]
            val value = match.groupValues[2]
            if (name.lowercase() !in KNOWN_NAMES || value.isBlank()) continue
            out.putIfAbsent(name, clean(value))
        }
        return out
    }

    private fun fromPairs(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chunk in text.split(Regex("[;\\r\\n]+"))) {
            val part = chunk.trim()
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            var value = part.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name.lowercase() in ATTRIBUTES) continue
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            out.putIfAbsent(name, clean(value))
        }
        return out
    }

    private fun clean(value: String): String =
        value.replace("\r", "").replace("\n", "").trim()

    private fun join(pairs: Map<String, String>): String =
        pairs.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

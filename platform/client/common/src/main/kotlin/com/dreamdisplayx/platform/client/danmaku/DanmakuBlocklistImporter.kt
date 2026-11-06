package com.dreamdisplayx.platform.client.danmaku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One parsed danmaku rule, either a literal substring or an ignore-case regex. */
internal data class DanmakuBlockRule(
    val literal: String?,
    val regex: Regex?,
    val canonical: String,
)

/** Parses the persisted DDX block-word text and the supported import formats. */
internal object DanmakuBlockWords {

    fun parse(raw: String): List<DanmakuBlockRule> =
        raw.lineSequence()
            .flatMap { splitTokens(it).asSequence() }
            .mapNotNull { token ->
                if (token.length >= 2 && token.startsWith('/') && token.endsWith('/')) {
                    makeRegexRule(token.substring(1, token.length - 1))
                } else {
                    makeLiteralRule(token)
                }
            }
            .toList()

    /** Encodes rules as one line for the single-line Cloth text field. */
    fun toSingleLine(raw: String): String =
        parse(raw).joinToString(", ") { it.canonical }

    /** Splits on separators outside a slash-delimited regex so `/foo,bar/` stays one rule. */
    private fun splitTokens(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inRegex = false
        var escaped = false
        var atTokenStart = true
        for (ch in line) {
            if (escaped) {
                current.append(ch)
                escaped = false
                atTokenStart = false
                continue
            }
            if (ch == '\\') {
                current.append(ch)
                escaped = true
                atTokenStart = false
                continue
            }
            if (ch == '/' && (inRegex || atTokenStart)) {
                inRegex = !inRegex
                current.append(ch)
                atTokenStart = false
                continue
            }
            if (!inRegex && (ch == ',' || ch == ';')) {
                val token = current.toString().trim()
                if (token.isNotEmpty()) out.add(token)
                current.clear()
                atTokenStart = true
                continue
            }
            current.append(ch)
            if (!ch.isWhitespace()) atTokenStart = false
        }
        val token = current.toString().trim()
        if (token.isNotEmpty()) out.add(token)
        return out
    }

    fun key(rule: DanmakuBlockRule): String =
        rule.regex?.let { "r:${it.pattern}" } ?: "k:${rule.literal.orEmpty().lowercase()}"

    fun makeRegexRule(pattern: String): DanmakuBlockRule? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            DanmakuBlockRule(null, Regex(trimmed, RegexOption.IGNORE_CASE), "/$trimmed/")
        }.getOrNull()
    }

    fun makeLiteralRule(value: String): DanmakuBlockRule? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val canonical = if (trimmed.any { it == ',' || it == ';' || it == '\n' || it == '\r' }) {
            "/${Regex.escape(trimmed)}/"
        } else {
            trimmed
        }
        return DanmakuBlockRule(trimmed, null, canonical)
    }
}

enum class DanmakuImportFormat { JSON, XML, TEXT }

data class DanmakuImportResult(
    val added: Int,
    val totalRead: Int,
    val skippedDisabled: Int,
    val skippedUid: Int,
    val skippedDuplicate: Int,
    val skippedInvalid: Int,
    val updatedRaw: String,
    val format: DanmakuImportFormat,
)

/** Converts Bilibili JSON/XML exports and DDX text files into append-only DDX rules. */
object DanmakuBlocklistImporter {

    /** Single-line form used by the config text field; imported rules stay comma-separated. */
    fun toSingleLine(raw: String): String = DanmakuBlockWords.toSingleLine(raw)

    fun importFile(file: File, existingRaw: String): DanmakuImportResult {
        val text = file.readText(StandardCharsets.UTF_8)
        return importText(text, file.name, existingRaw)
    }

    fun importText(text: String, fileName: String, existingRaw: String): DanmakuImportResult {
        val parsed = parse(text, fileName)
        val existing = DanmakuBlockWords.parse(existingRaw)
        val keys = existing.mapTo(HashSet(), DanmakuBlockWords::key)
        var duplicates = 0
        val additions = ArrayList<DanmakuBlockRule>()
        for (rule in parsed.rules) {
            if (keys.add(DanmakuBlockWords.key(rule))) additions.add(rule) else duplicates++
        }
        val updated = buildList {
            addAll(existing.map { it.canonical })
            addAll(additions.map { it.canonical })
        }.joinToString("\n")
        return DanmakuImportResult(
            added = additions.size,
            totalRead = parsed.rules.size + parsed.skippedDisabled + parsed.skippedUid + parsed.skippedInvalid,
            skippedDisabled = parsed.skippedDisabled,
            skippedUid = parsed.skippedUid,
            skippedDuplicate = duplicates,
            skippedInvalid = parsed.skippedInvalid,
            updatedRaw = updated,
            format = parsed.format,
        )
    }

    private data class Parsed(
        val format: DanmakuImportFormat,
        val rules: List<DanmakuBlockRule>,
        val skippedDisabled: Int,
        val skippedUid: Int,
        val skippedInvalid: Int,
    )

    private fun parse(text: String, fileName: String): Parsed {
        val format = detectFormat(text, fileName)
        return when (format) {
            DanmakuImportFormat.JSON -> parseJson(text)
            DanmakuImportFormat.XML -> parseXml(text)
            DanmakuImportFormat.TEXT -> parseText(text)
        }
    }

    private fun detectFormat(text: String, fileName: String): DanmakuImportFormat {
        val lower = fileName.lowercase()
        if (lower.endsWith(".json")) return DanmakuImportFormat.JSON
        if (lower.endsWith(".xml")) return DanmakuImportFormat.XML
        if (lower.endsWith(".txt")) return DanmakuImportFormat.TEXT
        val first = text.trimStart().firstOrNull()
        return when (first) {
            '<' -> DanmakuImportFormat.XML
            '[', '{' -> DanmakuImportFormat.JSON
            else -> DanmakuImportFormat.TEXT
        }
    }

    private fun parseJson(text: String): Parsed {
        val root = Json.parseToJsonElement(text)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["rules"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val rules = ArrayList<DanmakuBlockRule>()
        var disabled = 0
        var uid = 0
        var invalid = 0
        for (element in array) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: run {
                invalid++
                continue
            }
            val type = obj["type"]?.jsonPrimitive?.intOrNull ?: run {
                invalid++
                continue
            }
            val filter = obj["filter"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (filter.isEmpty()) {
                invalid++
                continue
            }
            if (obj["opened"]?.jsonPrimitive?.booleanOrNull == false) {
                disabled++
                continue
            }
            val rule = when (type) {
                0 -> DanmakuBlockWords.makeLiteralRule(filter)
                1 -> DanmakuBlockWords.makeRegexRule(filter)
                2 -> {
                    uid++
                    null
                }
                else -> {
                    invalid++
                    null
                }
            }
            when {
                rule != null -> rules.add(rule)
                type == 0 || type == 1 -> invalid++
            }
        }
        return Parsed(DanmakuImportFormat.JSON, rules, disabled, uid, invalid)
    }

    private fun parseXml(text: String): Parsed {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder()
            .parse(text.byteInputStream(StandardCharsets.UTF_8))
        val items = document.getElementsByTagName("item")
        val rules = ArrayList<DanmakuBlockRule>()
        var disabled = 0
        var invalid = 0
        for (i in 0 until items.length) {
            val element = items.item(i) as? Element ?: continue
            val value = element.textContent?.trim().orEmpty()
            if (value.isEmpty() || value.startsWith("t=")) continue
            if (element.getAttribute("enabled").equals("false", ignoreCase = true)) {
                disabled++
                continue
            }
            val rule = if (value.startsWith("r=")) {
                DanmakuBlockWords.makeRegexRule(value.substring(2))
            } else {
                DanmakuBlockWords.makeLiteralRule(value)
            }
            if (rule != null) rules.add(rule) else invalid++
        }
        return Parsed(DanmakuImportFormat.XML, rules, disabled, 0, invalid)
    }

    private fun parseText(text: String): Parsed {
        val rules = ArrayList<DanmakuBlockRule>()
        var invalid = 0
        for (line in text.lineSequence()) {
            val value = line.trim()
            if (value.isEmpty() || value.startsWith("#") || value.startsWith("//")) continue
            if (value.startsWith("t=")) continue
            val rule = when {
                value.startsWith("r=") -> DanmakuBlockWords.makeRegexRule(value.substring(2))
                value.length >= 2 && value.startsWith('/') && value.endsWith('/') ->
                    DanmakuBlockWords.makeRegexRule(value.substring(1, value.length - 1))
                else -> DanmakuBlockWords.makeLiteralRule(value)
            }
            if (rule != null) rules.add(rule) else invalid++
        }
        return Parsed(DanmakuImportFormat.TEXT, rules, 0, 0, invalid)
    }
}

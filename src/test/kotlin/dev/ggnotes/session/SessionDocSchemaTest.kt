package dev.ggnotes.session

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * SPEC §9: the two lenient schemas hold for every example gg wrote. The check is a
 * small hand-written walk over the subset of JSON Schema the two files use (type,
 * required, properties, additionalProperties, items, bounds, pattern), applied to
 * tomlj's JSON rendering of the parsed TOML (date-times become RFC 3339 strings).
 */
class SessionDocSchemaTest {

    private val dir = Paths.get("docs/gg-session-docs")
    private val overviewSchema = JsonParser.parseString(Files.readString(dir.resolve("overview-frontmatter.schema.json"))).asJsonObject
    private val noteSchema = JsonParser.parseString(Files.readString(dir.resolve("note.schema.json"))).asJsonObject

    @Test
    fun `every example overview's front matter matches the schema`() {
        // the examples' README.md is beside the documents; it has no front matter and a reader refuses it
        val files = Files.list(dir.resolve("examples")).use { s -> s.filter { it.toString().endsWith(".md") }.toList() }
            .filter { SessionDocParser.splitFrontMatter(Files.readString(it)) != null }
        assertEquals(2, files.size)
        for (f in files) {
            val head = SessionDocParser.splitFrontMatter(Files.readString(f))!!.first
            val json = JsonParser.parseString(org.tomlj.Toml.parse(head).toJson())
            assertEquals("$f", emptyList<String>(), check(overviewSchema, json, "$"))
        }
    }

    @Test
    fun `every example note matches the schema`() {
        val files = Files.list(dir.resolve("examples")).use { s -> s.filter { it.toString().endsWith(".toml") }.toList() }
        assertEquals(3, files.size)
        for (f in files) {
            val json = JsonParser.parseString(org.tomlj.Toml.parse(Files.readString(f)).toJson())
            assertEquals("$f", emptyList<String>(), check(noteSchema, json, "$"))
        }
    }

    @Test
    fun `the check itself catches a violation`() {
        val bad = JsonParser.parseString("""{"path":"/abs","start":0,"end":1,"summary":"","lines":[]}""")
        val problems = check(noteSchema, bad, "$")
        assertEquals(problems.toString(), 4, problems.size)
    }

    private fun check(schema: JsonObject, value: JsonElement, at: String): List<String> {
        val out = ArrayList<String>()
        schema.get("type")?.asString?.let { type ->
            val ok = when (type) {
                "object" -> value.isJsonObject
                "array" -> value.isJsonArray
                "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
                "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.toLongOrNull() != null
                else -> true
            }
            if (!ok) return listOf("$at: not a $type")
        }
        if (value.isJsonObject) {
            val o = value.asJsonObject
            schema.getAsJsonArray("required")?.forEach { r -> if (!o.has(r.asString)) out += "$at: missing ${r.asString}" }
            val props = schema.getAsJsonObject("properties") ?: JsonObject()
            val additional = schema.get("additionalProperties")
            for ((k, v) in o.entrySet()) {
                val sub = props.getAsJsonObject(k) ?: additional?.takeIf { it.isJsonObject }?.asJsonObject
                if (sub == null) {
                    if (additional != null && additional.isJsonPrimitive && !additional.asBoolean) out += "$at: unknown key $k"
                    continue
                }
                out += check(sub, v, "$at.$k")
            }
        }
        if (value.isJsonArray) {
            val a: JsonArray = value.asJsonArray
            schema.get("minItems")?.asInt?.let { if (a.size() < it) out += "$at: fewer than $it items" }
            schema.getAsJsonObject("items")?.let { items -> a.forEachIndexed { i, e -> out += check(items, e, "$at[$i]") } }
        }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val s = value.asString
            schema.get("minLength")?.asInt?.let { if (s.length < it) out += "$at: shorter than $it" }
            schema.get("maxLength")?.asInt?.let { if (s.length > it) out += "$at: longer than $it" }
            schema.get("pattern")?.asString?.let { if (!Regex(it).containsMatchIn(s)) out += "$at: does not match $it" }
        }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            schema.get("minimum")?.asLong?.let { if (value.asLong < it) out += "$at: below $it" }
        }
        return out
    }
}

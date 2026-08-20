package com.hfstudio.guidenh

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.platform.eel.fs.EelFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Extracts only Java syntax that can be inferred safely into an additive schema overlay. */
object GuideNhJavaSchemaExtractor {
    private const val maxFiles = 5_000
    private const val maxSourceCharacters = 2_000_000

    fun extract(sourceRoot: Path): JsonObject {
        val tags = linkedMapOf<String, GeneratedTag>()
        val sources = mutableListOf<GuideNhSchemaEnhancer.JavaSource>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("java", true) }
                .limit(maxFiles.toLong())
                .forEach { path ->
                    val source = runCatching { EelFiles.readString(path) }.getOrNull()
                        ?.takeIf { it.length <= maxSourceCharacters } ?: return@forEach
                    sources += GuideNhSchemaEnhancer.JavaSource(path.toString(), source)
                    val names = tagNames(source)
                    if (names.isEmpty()) return@forEach
                    val attributes = attributes(source)
                    names.forEach { name ->
                        tags.getOrPut(name.lowercase()) { GeneratedTag(name) }.attributes.putAll(attributes)
                    }
                }
        }
        return JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("generatedBy", "GuideNH IDEA Java schema extractor")
            add("tags", JsonObject().also { destination ->
                tags.values.sortedBy { it.name.lowercase() }.forEach { tag ->
                    destination.add(tag.name, JsonObject().apply {
                        addProperty("name", tag.name)
                        addProperty("description", "Extracted from GuideNH Java compiler source.")
                        add("attributes", JsonObject().also { attributes ->
                            tag.attributes.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, attribute) ->
                                attributes.add(name, JsonObject().apply {
                                    addProperty("type", attribute.type)
                                    addProperty("valueStyle", attribute.valueStyle)
                                })
                            }
                        })
                        add("children", JsonArray())
                    })
                }
            })
            GuideNhSchemaEnhancer.enhance(this, sources)
        }
    }

    private fun tagNames(source: String): Set<String> {
        val constants = stringConstants(source)
        val names = linkedSetOf<String>()
        Regex("""[\"']([A-Za-z][A-Za-z0-9]*)[\"']\s*,\s*new\s+[A-Za-z0-9_]+Compiler""").findAll(source)
            .forEach { names += it.groupValues[1] }
        Regex("""getTagNames\s*\(\s*\)\s*\{""").findAll(source).forEach { match ->
            val openBrace = source.indexOf('{', match.range.first)
            val body = matchingBrace(source, openBrace)?.let { source.substring(openBrace + 1, it) }.orEmpty()
            Regex("""[\"']([A-Za-z][A-Za-z0-9]*)[\"']""").findAll(body).forEach { names += it.groupValues[1] }
            Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""").findAll(body).forEach { identifier ->
                constants[identifier.value]?.let(names::add)
            }
        }
        return names
    }

    private fun stringConstants(source: String): Map<String, String> {
        val declarations = Regex("""\b(?:(?:public|private|protected|static|final)\s+)*String\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^;]+);""")
            .findAll(source).toList()
        val result = linkedMapOf<String, String>()
        repeat(declarations.size) {
            var changed = false
            declarations.forEach { declaration ->
                val name = declaration.groupValues[1]
                if (name in result) return@forEach
                resolveStringExpression(declaration.groupValues[2], result)?.let { value ->
                    result[name] = value
                    changed = true
                }
            }
            if (!changed) return result
        }
        return result
    }

    private fun resolveStringExpression(value: String, constants: Map<String, String>): String? {
        val result = StringBuilder()
        value.split('+').map(String::trim).forEach { part ->
            Regex("""^\"([^\"]*)\"$""").matchEntire(part)?.groupValues?.get(1)?.let(result::append)
                ?: constants[part]?.let(result::append)
                ?: return null
        }
        return result.toString()
    }

    private fun attributes(source: String): Map<String, GeneratedAttribute> {
        val attributes = linkedMapOf<String, GeneratedAttribute>()
        Regex("""MdxAttrs\s*\.\s*([A-Za-z0-9_]+)\s*\([^;]*?[\"']([^\"']+)[\"'][^;]*?\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(source).forEach { match ->
                attributes.putIfAbsent(match.groupValues[2], schemaForReader(match.groupValues[1]))
            }
        return attributes
    }

    private fun schemaForReader(reader: String): GeneratedAttribute {
        val normalized = reader.lowercase()
        return when {
            "boolean" in normalized -> GeneratedAttribute("boolean", "expression")
            "int" in normalized || "float" in normalized || "double" in normalized || "number" in normalized -> GeneratedAttribute("number", "string")
            "color" in normalized -> GeneratedAttribute("color", "string")
            "itemstack" in normalized -> GeneratedAttribute("item", "string")
            "ore" in normalized -> GeneratedAttribute("ore", "string")
            else -> GeneratedAttribute("string", "string")
        }
    }

    private fun matchingBrace(source: String, openBrace: Int): Int? {
        if (openBrace < 0) return null
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private class GeneratedTag(val name: String) {
        val attributes = linkedMapOf<String, GeneratedAttribute>()
    }

    private data class GeneratedAttribute(val type: String, val valueStyle: String)
}

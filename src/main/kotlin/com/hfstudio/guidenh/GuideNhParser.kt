package com.hfstudio.guidenh

import com.intellij.openapi.util.TextRange

object GuideNhParser {
    private val tagPattern = Regex("</?([A-Za-z][A-Za-z0-9]*)(\\s(?:[^>\\\"']+|\\\"[^\\\"]*\\\"|'[^']*')*?)?(/?)>")
    private val attributePattern = Regex("([A-Za-z_][\\w.:-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|\\{([^}]*)\\}|([^\\s\"'=<>`]+)))?")
    private val markdownLinkPattern = Regex("!?\\[[^\\]\\r\\n]*\\]\\(\\s*([^()\\s]+)\\s*\\)")
    private val resourceExtensions = setOf("snbt", "json", "png", "jpg", "jpeg", "gif", "webp", "svg")
    private val pageAttributeNames = setOf("linksto", "page", "parent")
    private val resourceAttributeNames = setOf("resource", "file", "structure", "src", "iconimage")
    private val ignoredHtmlTagNames = setOf(
        "a", "abbr", "area", "b", "base", "code", "col", "del", "em", "embed", "hr", "i", "img", "input", "link", "mark",
        "meta", "param", "s", "small", "source", "span", "strong", "sub", "sup", "track", "u", "wbr"
    )

    fun parse(text: String): GuideNhDocumentModel {
        val tags = mutableListOf<GuideNhTag>()
        val markdownMaskedText = maskMarkdown(text)
        for (match in tagPattern.findAll(markdownMaskedText)) {
            val source = match.value
            val closing = source.startsWith("</")
            val name = match.groupValues[1]
            if (name.lowercase() in ignoredHtmlTagNames) continue
            val nameStart = match.range.first + if (closing) 2 else 1
            val attributes = if (closing) emptyList() else parseAttributes(match, text)
            tags += GuideNhTag(
                name = name,
                range = TextRange(match.range.first, match.range.last + 1),
                nameRange = TextRange(nameStart, nameStart + name.length),
                attributes = attributes,
                closing = closing,
                selfClosing = closing || source.trimEnd().endsWith("/>") || name.equals("br", true)
            )
        }
        val references = mutableListOf<GuideNhReference>()
        val frontmatterParent = Regex("(?m)^\\s{2,}parent\\s*:\\s*([^\\s#]+\\.md(?:#[^\\s]+)?)")
        for (match in frontmatterParent.findAll(text)) {
            val value = match.groupValues[1]
            val start = match.range.first + match.value.lastIndexOf(value)
            references += GuideNhReference(GuideNhReferenceKind.PAGE, value, TextRange(start, start + value.length), "parent")
        }
        references += findFrontmatterSemanticReferences(text)
        for (match in markdownLinkPattern.findAll(markdownMaskedText)) {
            val rawValue = match.groupValues[1]
            val value = unwrapLegacyMarkdownDestination(rawValue)
            val kind = when {
                value.substringBefore('#').endsWith(".md", true) -> GuideNhReferenceKind.PAGE
                isGuideNhResourceReference(value) -> GuideNhReferenceKind.RESOURCE
                else -> null
            } ?: continue
            val start = match.range.first + match.value.indexOf(rawValue)
            references += GuideNhReference(kind, value, TextRange(start, start + rawValue.length))
        }
        for (tag in tags) {
            for (attribute in tag.attributes) {
                val normalized = attribute.name.lowercase()
                val value = attribute.value ?: continue
                // Keep semantic references aligned with GuideVSC's explicit runtime
                // attribute registry. A generic `id` is common in non-item GuideNH
                // tags and must not acquire item completion, hover or Find Usages.
                val runtimeCapability = resolveGuideNhRuntimeCapability(tag.name, attribute.name)
                val kind = when {
                    normalized in pageAttributeNames || value.endsWith(".md", true) -> GuideNhReferenceKind.PAGE
                    // A namespaced value is a resource reference; a bare file name such as `test1.png` is a
                    // path relative to the document, which GuideNH resolves as a file. Only the first form
                    // can be looked up in the resource index, so only it becomes a reference.
                    normalized in resourceAttributeNames -> value.takeIf { it.contains(':') }
                        ?.let { GuideNhReferenceKind.RESOURCE }
                    isGuideNhResourceReference(value) -> GuideNhReferenceKind.RESOURCE
                    runtimeCapability == "items" -> GuideNhReferenceKind.ITEM
                    runtimeCapability == "ores" -> GuideNhReferenceKind.ORE
                    else -> null
                }
                if (kind != null) references += GuideNhReference(kind, value, attribute.valueRange, attribute.name)
            }
        }
        return GuideNhDocumentModel(tags, references)
    }

    private fun findFrontmatterSemanticReferences(text: String): List<GuideNhReference> {
        val lines = text.splitToSequence('\n').toList()
        if (lines.firstOrNull()?.trim()?.removeSuffix("\r") != "---") return emptyList()
        val references = mutableListOf<GuideNhReference>()
        var offset = lines.first().length + 1
        var activeKind: GuideNhReferenceKind? = null
        var activeAttribute: String? = null
        for (lineIndex in 1 until lines.size) {
            val rawLine = lines[lineIndex].removeSuffix("\r")
            if (rawLine.trim() == "---") break
            val scalar = Regex("^(\\s*)(item_id|item_ids|ore_ids)\\s*:\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE).find(rawLine)
            if (scalar != null) {
                val name = scalar.groupValues[2]
                val rawValue = scalar.groupValues[3]
                val kind = if (name.equals("ore_ids", true)) GuideNhReferenceKind.ORE else GuideNhReferenceKind.ITEM
                val valueStart = offset + scalar.range.first + scalar.value.lastIndexOf(rawValue)
                if (rawValue.isBlank()) {
                    activeKind = kind
                    activeAttribute = name
                } else {
                    addFrontmatterValues(references, kind, name, rawValue, valueStart)
                    activeKind = null
                    activeAttribute = null
                }
            } else {
                val listValue = Regex("^\\s*-\\s+(.+?)\\s*$").find(rawLine)
                if (listValue != null && activeKind != null && activeAttribute != null) {
                    val rawValue = listValue.groupValues[1]
                    val valueStart = offset + listValue.range.first + listValue.value.lastIndexOf(rawValue)
                    addFrontmatterValues(references, activeKind, activeAttribute, rawValue, valueStart)
                } else if (rawLine.isNotBlank() && !rawLine.trimStart().startsWith("#")) {
                    activeKind = null
                    activeAttribute = null
                }
            }
            offset += lines[lineIndex].length + if (lineIndex < lines.lastIndex) 1 else 0
        }
        return references
    }

    private fun addFrontmatterValues(
        references: MutableList<GuideNhReference>,
        kind: GuideNhReferenceKind,
        attribute: String,
        rawValue: String,
        valueStart: Int
    ) {
        val valueBody = rawValue.trim().removePrefix("[").removeSuffix("]")
        val bodyStart = valueStart + rawValue.indexOf(valueBody).coerceAtLeast(0)
        Regex("""\"([^\"]*)\"|'([^']*)'|([^,\s]+)""").findAll(valueBody).forEach { match ->
            val group = (1..3).firstOrNull { match.groups[it] != null } ?: return@forEach
            val valueMatch = match.groups[group] ?: return@forEach
            val value = valueMatch.value.trim()
            if (value.isNotEmpty()) {
                val start = bodyStart + valueMatch.range.first
                references += GuideNhReference(kind, value, TextRange(start, start + value.length), attribute, declaration = true)
            }
        }
    }

    private fun parseAttributes(match: MatchResult, text: String): List<GuideNhAttribute> {
        val source = match.groupValues.getOrNull(2).orEmpty()
        if (source.isBlank()) return emptyList()
        val base = match.range.first + match.value.indexOf(source)
        return attributePattern.findAll(source).map { attribute ->
            val name = attribute.groupValues[1]
            val valueGroup = (2..5).firstOrNull { attribute.groups[it]?.value?.isNotEmpty() == true }
            val value = valueGroup?.let { attribute.groupValues[it] }
            val valueStyle = when (valueGroup) {
                2, 3 -> "string"
                4 -> "expression"
                5 -> "bare"
                else -> null
            }
            val nameStart = base + attribute.range.first
            val valueStartInMatch = value?.let { source.indexOf(it, attribute.range.first) }
                ?: attribute.range.first
            val valueStart = base + valueStartInMatch
            GuideNhAttribute(
                name,
                value,
                valueStyle,
                TextRange(nameStart, nameStart + name.length),
                TextRange(valueStart, valueStart + (value?.length ?: name.length))
            )
        }.toList()
    }

    fun isGuideNhResourceReference(value: String): Boolean {
        val extension = unwrapLegacyMarkdownDestination(value).substringBefore('#').substringAfterLast('.', "").lowercase()
        return extension in resourceExtensions
    }

    private fun unwrapLegacyMarkdownDestination(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith('*') && trimmed.endsWith('*') && trimmed.length > 2) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }

    private fun maskMarkdown(text: String): String {
        val chars = text.toCharArray()
        val ranges = mutableListOf<IntRange>()
        Regex("\\{/\\*[\\s\\S]*?\\*/\\}").findAll(text).forEach { ranges += it.range }
        Regex("(?m)^([`~]{3,})[^\\r\\n]*(?:\\r?\\n[\\s\\S]*?^\\1[ \\t]*$|[\\s\\S]*$)").findAll(text).forEach { ranges += it.range }
        collectInlineCodeRanges(text).forEach { ranges += it }
        for (range in ranges) for (i in range) if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' '
        return String(chars)
    }

    private fun collectInlineCodeRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('`', index)
            if (start < 0) break
            var afterOpening = start + 1
            while (afterOpening < text.length && text[afterOpening] == '`') afterOpening++
            val tickCount = afterOpening - start
            if (tickCount >= 3) {
                index = afterOpening
                continue
            }
            val delimiter = "`".repeat(tickCount)
            var searchFrom = afterOpening
            var closing = -1
            while (searchFrom < text.length) {
                val candidate = text.indexOf(delimiter, searchFrom)
                if (candidate < 0) break
                if (!text.startsWith("${delimiter}`", candidate)) {
                    closing = candidate
                    break
                }
                searchFrom = candidate + tickCount + 1
            }
            if (closing >= 0) {
                ranges += start..(closing + tickCount - 1)
                index = closing + tickCount
            } else {
                index = afterOpening
            }
        }
        return ranges
    }
}

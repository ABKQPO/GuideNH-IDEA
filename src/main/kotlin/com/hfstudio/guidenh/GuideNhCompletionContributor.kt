package com.hfstudio.guidenh

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.hfstudio.MyMessageBundle

class GuideNhCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile
                if (!GuideNhFileUtil.isGuideNhDocument(file)) return
                val text = file.text
                val offset = parameters.offset.coerceAtMost(text.length)
                val before = text.substring(0, offset)
                val tagPrefix = Regex("<([A-Za-z][A-Za-z0-9]*)?$").find(before)?.groupValues?.getOrNull(1)
                val schema = GuideNhSchemaService.get(file.project)
                if (before.substringAfterLast('<').contains('>')) return
                val frontmatterLine = before.substringAfterLast('\n')
                if (before.lineSequence().firstOrNull()?.trim() == "---" && !frontmatterLine.trimStart().startsWith("-") && !frontmatterLine.contains(':')) {
                    val keyPrefix = frontmatterLine.trim()
                    val path = before.substringBeforeLast('\n').lineSequence()
                    .mapNotNull { Regex("""^(\s*)([A-Za-z_][\w.-]*)\s*:""").find(it)?.let { match -> match.groupValues[2] to match.groupValues[1].length } }
                        .sortedBy { it.second }.map { it.first }.toList()
                    val keys = schema.frontmatterKeys(path)
                    keys.filter { it.startsWith(keyPrefix, true) }.forEach { key ->
                        result.addElement(LookupElementBuilder.create(key).withTypeText(MyMessageBundle.message("completion.frontmatter")))
                    }
                    if (keys.isNotEmpty()) return
                }
                val frontmatterValue = findFrontmatterValueContext(before)
                if (frontmatterValue != null) {
                    val (path, prefix) = frontmatterValue
                    val capability = resolveGuideNhFrontmatterRuntimeCapability(path)
                    if (path.equals("navigation.parent", true)) {
                        GuideNhWorkspaceIndex.get(file.project).queryPages(prefix, file.virtualFile).forEach { page ->
                            result.addElement(LookupElementBuilder.create(page.relativePath).withTypeText(MyMessageBundle.message("completion.page")))
                        }
                    }
                    GuideNhWorkspaceIndex.get(file.project).querySemanticValues(path, prefix).forEach { value ->
                        result.addElement(LookupElementBuilder.create(value).withTypeText(MyMessageBundle.message("completion.frontmatter")))
                    }
                    if (capability != null && GuideNhRuntimeBridgeService.get().isConnected) {
                        val runtime = GuideNhRuntimeBridgeService.get()
                        val cached = runtime.queryCached(capability, prefix, 80)
                        cached.forEach { entry ->
                            val id = entry.get("id")?.asString ?: return@forEach
                            result.addElement(LookupElementBuilder.create(id).withTypeText(entry.get("label")?.asString ?: capability))
                        }
                        if (cached.isEmpty()) {
                            runtime.prefetch(capability, prefix, 80)
                            result.restartCompletionOnPrefixChange(prefix)
                        }
                    }
                    return
                }
                val valueContext = findAttributeValueContext(before)
                if (valueContext != null) {
                    val tagName = valueContext.tagName
                    val attributeName = valueContext.attributeName
                    val prefix = valueContext.prefix
                    val index = GuideNhWorkspaceIndex.get(file.project)
                    val attributeSchema = schema.attribute(tagName, attributeName)
                    val isResource = attributeSchema?.type.equals("resource", true)
                    val isPage = attributeSchema?.type.equals("page", true) ||
                        attributeName.equals("parent", true) || attributeName.equals("linksTo", true) || attributeName.equals("page", true)
                    val values = if (isResource) {
                        index.queryResources(prefix, file.virtualFile).map { it.relativePath }
                    } else if (isPage) {
                        index.queryPages(prefix, file.virtualFile).map { it.relativePath }
                    } else emptyList()
                    val staticValues = attributeSchema?.values.orEmpty() +
                        (if (attributeSchema?.type.equals("boolean", true)) listOf("true", "false") else emptyList()) +
                        guideNhStaticRuntimeValues(tagName, attributeName)
                    staticValues.filter { it.startsWith(prefix, true) }.forEach { value ->
                        result.addElement(valueLookup(value, MyMessageBundle.message("completion.value"), valueContext))
                    }
                    if (staticValues.isNotEmpty()) return
                    values.forEach { value ->
                        val insertion = preserveReferencePrefix(prefix, value)
                        result.addElement(valueLookup(insertion, if (isResource) MyMessageBundle.message("completion.resource") else MyMessageBundle.message("completion.page"), valueContext))
                    }
                    if (values.isNotEmpty()) return
                    val runtimeCapability = resolveGuideNhRuntimeCapability(tagName, attributeName, attributeSchema)
                    if (runtimeCapability != null && GuideNhRuntimeBridgeService.get().isConnected) {
                        val runtime = GuideNhRuntimeBridgeService.get()
                        val filters = guideNhRuntimeFilters(text, offset, tagName, attributeName)
                        val cached = runtime.queryCached(runtimeCapability, prefix, 80, filters)
                        cached.forEach { entry ->
                            val id = entry.get("id")?.asString ?: return@forEach
                            result.addElement(valueLookup(id, entry.get("label")?.asString ?: runtimeCapability, valueContext))
                        }
                        if (cached.isEmpty()) {
                            runtime.prefetch(runtimeCapability, prefix, 80, filters)
                            result.restartCompletionOnPrefixChange(prefix)
                        }
                        return
                    }
                }
                val frontmatterParent = Regex("(?m)^\\s{2,}parent\\s*:\\s*([^\\n]*)$").find(before)
                if (frontmatterParent != null && !before.substringAfterLast("\n").contains("#")) {
                    val prefix = frontmatterParent.groupValues[1].trim()
                    GuideNhWorkspaceIndex.get(file.project).queryPages(prefix, file.virtualFile).forEach { page ->
                        result.addElement(LookupElementBuilder.create(page.relativePath).withTypeText(MyMessageBundle.message("completion.page")))
                    }
                    return
                }
                val line = before.substringAfterLast('\n')
                if (line.trimStart().startsWith("```") && !line.trimStart().substring(3).contains(' ')) {
                    val prefix = line.trimStart().removePrefix("```")
                    GuideNhSchemaService.get(file.project).fencedBlocks().filterKeys { it.startsWith(prefix, true) }.forEach { (name, description) ->
                        result.addElement(LookupElementBuilder.create(name).withTypeText(MyMessageBundle.message("completion.fenced")).withTailText("  $description"))
                    }
                    return
                }
                val markerService = GuideNhSchemaService.get(file.project)
                val markerPrefix = markerService.inlineMarkers().values.map { it.first }.filter { it.isNotEmpty() }
                    .sortedByDescending { it.length }.firstOrNull { before.endsWith(it) }.orEmpty()
                if (markerPrefix.isNotEmpty()) {
                    markerService.inlineMarkers().filter { (_, marker) -> marker.first.startsWith(markerPrefix) }.forEach { (_, marker) ->
                        result.addElement(LookupElementBuilder.create(marker.first).withTypeText(MyMessageBundle.message("completion.inline")).withTailText(" ${marker.second}"))
                    }
                }
                val word = Regex("([A-Za-z][A-Za-z0-9]*)$").find(before)?.value.orEmpty()
                if (word.isNotEmpty() && !before.substringAfterLast('<').contains('>')) {
                    GuideNhSchemaService.get(file.project).snippets().filter { it.prefix.startsWith(word, true) }.forEach { snippet ->
                        result.addElement(LookupElementBuilder.create(snippet.prefix).withTypeText(MyMessageBundle.message("completion.snippet")).withTailText("  ${snippet.description ?: ""}").withInsertHandler { insertionContext, _ ->
                            val start = (insertionContext.tailOffset - word.length).coerceAtLeast(0)
                            insertionContext.editor.selectionModel.setSelection(start, insertionContext.tailOffset)
                            TemplateManager.getInstance(file.project).startTemplate(insertionContext.editor, createSnippetTemplate(file.project, snippet.body.joinToString("\n")))
                        })
                    }
                }
                if (tagPrefix != null) {
                    schema.allTags().filter { it.name.startsWith(tagPrefix, true) }.forEach {
                        result.addElement(LookupElementBuilder.create(it.name).withTypeText(MyMessageBundle.message("completion.tag")).withTailText("  ${it.description ?: ""}").withInsertHandler { insertionContext, _ ->
                            val tag = it.name
                            val suffix = if (it.attributes.isEmpty() && it.children.isEmpty()) " />" else ">\n</$tag>"
                            insertionContext.document.insertString(insertionContext.tailOffset, suffix)
                            insertionContext.editor.caretModel.moveToOffset(insertionContext.tailOffset - suffix.length + if (suffix.startsWith(" />")) 0 else 1)
                        })
                    }
                    return
                }
                val closingPrefix = Regex("</([A-Za-z][A-Za-z0-9]*)?$").find(before)?.groupValues?.getOrNull(1)
                if (closingPrefix != null) {
                    val openTags = GuideNhParser.parse(before).tags.filter { !it.closing && !it.selfClosing }.map { it.name }
                    openTags.asReversed().distinct().filter { it.startsWith(closingPrefix, true) }.forEach { name ->
                        result.addElement(LookupElementBuilder.create(name).withTypeText(MyMessageBundle.message("completion.closing.tag")))
                    }
                    return
                }
                val openTag = Regex("<([A-Za-z][A-Za-z0-9]*)\\s+[^>]*?([A-Za-z_][\\w.-]*)?$").find(before)
                if (openTag != null) {
                    val tagName = openTag.groupValues[1]
                    val prefix = openTag.groupValues[2]
                    val declared = mutableSetOf<String>()
                    schema.tag(tagName)?.attributes?.forEach { (name, attr) ->
                        declared += name.lowercase()
                        if (!name.startsWith(prefix, true)) return@forEach
                        result.addElement(LookupElementBuilder.create(name).withTypeText(attr.type).withTailText("  ${attr.description ?: ""}").withInsertHandler { insertionContext, _ ->
                            val suffix = createAttributeInsertion(attr)
                            if (suffix.isNotEmpty()) insertionContext.document.insertString(insertionContext.tailOffset, suffix)
                        })
                    }
                    // A <Template> call takes its arguments as attributes, so the named template's own
                    // parameters are offered alongside the tag's declared ones.
                    if (tagName.equals("Template", true)) {
                        val templateName = Regex("\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").find(before.substringAfter('<'))
                        val target = templateName?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                        if (!target.isNullOrBlank()) {
                            GuideNhWorkspaceIndex.get(file.project).templateParameterNames(target, file.virtualFile)
                                .filter { !declared.contains(it.lowercase()) }
                                .filter { it.startsWith(prefix, true) }
                                .forEach { name ->
                                    result.addElement(
                                        LookupElementBuilder.create(name)
                                            .withTypeText(MyMessageBundle.message("completion.template.argument"))
                                            .withTailText("  $target")
                                            .withInsertHandler { insertionContext, _ ->
                                                insertionContext.document.insertString(insertionContext.tailOffset, "=\"\"")
                                                insertionContext.editor.caretModel.moveToOffset(insertionContext.tailOffset - 1)
                                            }
                                    )
                                }
                        }
                    }
                    return
                }
                if (before.contains("[")) {
                    val linkPrefix = before.substringAfterLast('(')
                    if (!linkPrefix.contains(')')) {
                        GuideNhWorkspaceIndex.get(file.project).queryPages(linkPrefix, file.virtualFile).forEach {
                            result.addElement(LookupElementBuilder.create(it.relativePath).withTypeText(MyMessageBundle.message("completion.page")))
                        }
                    }
                }
            }
        })
    }

    private data class AttributeValueContext(
        val tagName: String,
        val attributeName: String,
        val prefix: String,
        val valueStart: Int,
        val terminator: Char?
    )

    private fun findAttributeValueContext(before: String): AttributeValueContext? {
        val openTag = Regex("<([A-Za-z][A-Za-z0-9]*)\\b[^<>]*$").find(before) ?: return null
        val attribute = Regex("""([A-Za-z_][\w.-]*)\s*=\s*(?:\"([^\"]*)|'([^']*)'|\{([^}]*)|([^\s\"'=<>`]*))$""").find(openTag.value) ?: return null
        val valueGroup = (2..5).firstOrNull { attribute.groups[it] != null } ?: return null
        val valueMatch = attribute.groups[valueGroup] ?: return null
        return AttributeValueContext(
            tagName = openTag.groupValues[1],
            attributeName = attribute.groupValues[1],
            prefix = valueMatch.value,
            valueStart = openTag.range.first + valueMatch.range.first,
            terminator = when (valueGroup) {
                2 -> '\"'
                3 -> '\''
                4 -> '}'
                else -> null
            }
        )
    }

    private fun valueLookup(value: String, typeText: String, context: AttributeValueContext): LookupElementBuilder =
        LookupElementBuilder.create(value).withTypeText(typeText).withInsertHandler { insertionContext, _ ->
            val document = insertionContext.document
            val start = context.valueStart.coerceIn(0, document.textLength)
            var end = insertionContext.tailOffset.coerceIn(start, document.textLength)
            while (end < document.textLength && when (context.terminator) {
                null -> !document.charsSequence[end].isWhitespace() && document.charsSequence[end] != '>' && document.charsSequence[end] != '/'
                else -> document.charsSequence[end] != context.terminator
            }) end++
            document.replaceString(start, end, value)
            insertionContext.editor.caretModel.moveToOffset(start + value.length)
        }

    private fun preserveReferencePrefix(prefix: String, value: String): String {
        val match = Regex("^(?:(?:\\.\\./)|(?:\\./))+").find(prefix)?.value.orEmpty()
        val normalizedValue = value.removePrefix("/")
        return when {
            match.isNotEmpty() -> match + normalizedValue
            prefix.startsWith('/') -> "/$normalizedValue"
            else -> normalizedValue
        }
    }

    private fun findFrontmatterValueContext(before: String): Pair<String, String>? {
        if (before.lineSequence().firstOrNull()?.trim() != "---") return null
        val line = before.substringAfterLast('\n')
        val keyMatch = Regex("""^(\s*)([A-Za-z_][\w.-]*)\s*:\s*([^\n]*)$""").find(line)
        val listMatch = Regex("""^(\s*)-\s*(.*?)\s*$""").find(line)
        if (keyMatch == null && listMatch == null) return null
        val indent = (keyMatch ?: listMatch!!).groupValues[1].length
        val key = keyMatch?.groupValues?.get(2)
        val rawValue = keyMatch?.groupValues?.get(3) ?: listMatch!!.groupValues[2]
        val prefix = rawValue.trim().removePrefix("[").substringAfterLast(',').trim().removeSurrounding("\"").removeSurrounding("'")
        val parents = ArrayDeque<Pair<Int, String>>()
        before.substringBeforeLast('\n').lineSequence().drop(1).forEach { candidate ->
            val match = Regex("""^(\s*)([A-Za-z_][\w.-]*)\s*:\s*(.*)$""").find(candidate) ?: return@forEach
            val candidateIndent = match.groupValues[1].length
            while (parents.isNotEmpty() && parents.last().first >= candidateIndent) parents.removeLast()
            if (match.groupValues[3].isBlank()) parents.addLast(candidateIndent to match.groupValues[2])
        }
        val ancestors = parents.filter { it.first < indent }.map { it.second }
        val path = if (key == null) ancestors else ancestors + key
        return path.joinToString(".") to prefix
    }

    private fun createAttributeInsertion(attribute: GuideNhAttributeSchema): String {
        if (attribute.valueStyle.equals("bare", true)) return ""
        val defaultValue = when (attribute.type.lowercase()) {
            "boolean" -> "true"
            "number", "integer" -> "0"
            "color" -> "#ffffff"
            "item" -> "minecraft:stone"
            "ore" -> "oreDustIron"
            "enum" -> attribute.values.firstOrNull().orEmpty()
            else -> "value"
        }
        return if (attribute.valueStyle.equals("expression", true)) "={$defaultValue}" else "=\"$defaultValue\""
    }

    private fun createSnippetTemplate(project: com.intellij.openapi.project.Project, value: String): Template {
        val template = TemplateManager.getInstance(project).createTemplate("guidenh", "GuideNH", "")
        var cursor = 0
        val declared = HashSet<Int>()
        Regex("\\$\\{(\\d+)(?::([^}]*))?}|\\$(\\d+)").findAll(value).forEach { match ->
            if (match.range.first > cursor) template.addTextSegment(value.substring(cursor, match.range.first))
            val index = match.groupValues[1].ifEmpty { match.groupValues[3] }.toIntOrNull() ?: 0
            val defaultValue = match.groupValues[2]
            when {
                index == 0 -> template.addEndVariable()
                declared.add(index) -> template.addVariable("GUIDENH_$index", TextExpression(defaultValue), TextExpression(defaultValue), true)
                else -> template.addVariableSegment("GUIDENH_$index")
            }
            cursor = match.range.last + 1
        }
        if (cursor < value.length) template.addTextSegment(value.substring(cursor))
        if (!value.contains("$0") && !value.contains($$"${0")) template.addEndVariable()
        return template
    }
}

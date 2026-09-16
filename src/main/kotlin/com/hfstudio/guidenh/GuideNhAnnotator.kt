package com.hfstudio.guidenh

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.hfstudio.MyMessageBundle

class GuideNhAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile || !GuideNhFileUtil.isGuideNhDocument(element)) return
        val model = GuideNhParser.parse(element.text)
        val schema = GuideNhSchemaService.get(element.project)
        validateFrontmatter(element.text, schema, holder)
        val stack = ArrayDeque<GuideNhTag>()
        for (tag in model.tags) {
            val tagSchema = schema.tag(tag.name)
            if (tagSchema == null) {
                error(holder, tag.nameRange, MyMessageBundle.message("diagnostic.unknown.tag", tag.name))
            }
            if (tag.closing) {
                val matchingIndex = stack.indexOfLast { it.name.equals(tag.name, true) }
                val expected = stack.lastOrNull()
                if (matchingIndex < 0) {
                    error(holder, tag.nameRange, MyMessageBundle.message("diagnostic.unexpected.closing", tag.name))
                } else if (expected != null && !expected.name.equals(tag.name, true)) {
                    val missingClosures = stack.drop(matchingIndex + 1).asReversed().map { it.name }
                    holder.newAnnotation(HighlightSeverity.ERROR, MyMessageBundle.message("diagnostic.closing.mismatch", tag.name, expected.name))
                        .range(tag.nameRange)
                        .withFix(GuideNhInsertClosingBeforeFix(missingClosures.ifEmpty { listOf(expected.name) }))
                        .create()
                }
                if (matchingIndex >= 0) repeat(stack.size - matchingIndex) { stack.removeLast() }
                continue
            }
            val parent = stack.lastOrNull()
            val parentSchema = parent?.let { schema.tag(it.name) }
            val parentTakesBlockContent = parentSchema != null && parentSchema.preferredChildren.isNotEmpty()
            if (parentSchema != null && !parentTakesBlockContent && parentSchema.children.isNotEmpty() &&
                parentSchema.children.none { it.equals(tag.name, true) }) {
                error(holder, tag.nameRange, MyMessageBundle.message("diagnostic.child.not.allowed", tag.name, parent.name))
            }
            if (tagSchema != null) {
                val seenAttributes = mutableSetOf<String>()
                for (attribute in tag.attributes) {
                    val attributeSchema = schema.attribute(tag.name, attribute.name)
                    if (!seenAttributes.add(attribute.name.lowercase())) {
                        error(holder, attribute.range, MyMessageBundle.message("diagnostic.duplicate.attribute", attribute.name, tag.name))
                    } else if (attributeSchema == null && !tagSchema.forwardsAttributes) {
                        // A tag that forwards attributes turns every undeclared one into data, so it is not a mistake.
                        error(holder, attribute.range, MyMessageBundle.message("diagnostic.unknown.attribute", attribute.name, tag.name))
                    } else if (attributeSchema != null) {
                        validateAttribute(holder, attribute, attributeSchema)
                    }
                }
                tagSchema.attributes.forEach { (name, attributeSchema) ->
                    val hasAttribute = tag.attributes.any { it.name.equals(name, true) && !it.value.isNullOrBlank() }
                    val alternativesMissing = attributeSchema.requiredWhenMissing.all { alternative ->
                        tag.attributes.none { it.name.equals(alternative, true) && !it.value.isNullOrBlank() }
                    }
                    if (!hasAttribute && (attributeSchema.required || (attributeSchema.requiredWhenMissing.isNotEmpty() && alternativesMissing))) {
                        error(holder, tag.nameRange, MyMessageBundle.message("diagnostic.missing.attribute", name, tag.name))
                    }
                }
            }
            validateFloatingImage(tag, holder)
            if (!tag.selfClosing && tagSchema != null) stack.addLast(tag)
        }
        stack.forEachIndexed { index, tag ->
            val closingNames = stack.drop(index).asReversed().map { it.name }
            val closingAnnotation = holder.newAnnotation(HighlightSeverity.ERROR, MyMessageBundle.message("diagnostic.unclosed.tag", tag.name))
                .range(tag.range)
                .withFix(GuideNhCloseTagFix(closingNames, element.text, element.text.length, tag.range.startOffset))
            findPreferredClosingBoundary(element.text, model.tags, tag)?.let { boundary ->
                closingAnnotation.withFix(GuideNhCloseTagFix(closingNames, element.text, boundary.range.endOffset, tag.range.startOffset, boundary.name))
            }
            closingAnnotation.create()
        }
        for (reference in model.references) {
            if (reference.kind == GuideNhReferenceKind.PAGE || reference.kind == GuideNhReferenceKind.RESOURCE) {
                // Keep unresolved references visible without making normal external links fatal.
                val target = if (reference.kind == GuideNhReferenceKind.PAGE)
                    GuideNhWorkspaceIndex.get(element.project).findPage(reference.value, element.virtualFile)
                else GuideNhWorkspaceIndex.get(element.project).findResource(reference.value, element.virtualFile)
                if (target == null && (reference.kind == GuideNhReferenceKind.RESOURCE || reference.value.endsWith(".md", true))) {
                    val kind = if (reference.kind == GuideNhReferenceKind.PAGE) "page" else "resource"
                    error(holder, reference.range, MyMessageBundle.message("diagnostic.unknown.reference", kind, reference.value))
                }
            }
        }
    }

    private fun error(holder: AnnotationHolder, range: TextRange, message: String) {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create()
    }

    private fun validateAttribute(holder: AnnotationHolder, attribute: GuideNhAttribute, schema: GuideNhAttributeSchema) {
        val value = attribute.value
        if (value == null) {
            if (!schema.type.equals("boolean", true)) {
                error(holder, attribute.range, MyMessageBundle.message("diagnostic.attribute.expects", attribute.name, schema.type))
            }
            return
        }
        if (schema.values.isNotEmpty() && schema.values.none { it.equals(value, true) }) {
            error(holder, attribute.valueRange, MyMessageBundle.message("diagnostic.enum", schema.values.joinToString()))
            return
        }
        if (schema.valueStyle.equals("string", true) && attribute.valueStyle == "expression") {
            error(holder, attribute.valueRange, MyMessageBundle.message("diagnostic.attribute.expects", attribute.name, schema.type))
            return
        }
        val normalized = value.trim().removePrefix("{").removeSuffix("}").trim()
        when (schema.type.lowercase()) {
            "boolean" -> if (normalized != "true" && normalized != "false") error(holder, attribute.valueRange, MyMessageBundle.message("diagnostic.boolean"))
            "number", "integer" -> if (schema.valueStyle != "expression" && normalized.toDoubleOrNull() == null) error(holder, attribute.valueRange, MyMessageBundle.message("diagnostic.number"))
            "color" -> if (!COLOR_VALUE.matches(normalized)) error(holder, attribute.valueRange, MyMessageBundle.message("diagnostic.attribute.expects", attribute.name, "color"))
            "item", "ore", "resource", "page" -> if (normalized.isBlank()) error(holder, attribute.valueRange, MyMessageBundle.message("diagnostic.attribute.expects", attribute.name, schema.type))
        }
    }

    private fun validateFloatingImage(tag: GuideNhTag, holder: AnnotationHolder) {
        if (!tag.name.equals("FloatingImage", true)) return
        val values = tag.attributes.associateBy { it.name.lowercase() }
        fun conflict(first: String, second: String, messageKey: String) {
            if (values[first]?.value.isNullOrBlank() || values[second]?.value.isNullOrBlank()) return
            error(holder, tag.range, MyMessageBundle.message(messageKey))
        }
        conflict("width", "w", "diagnostic.floating.image.width")
        conflict("height", "h", "diagnostic.floating.image.height")
        val hasDisplaySize = !values["displaywidth"]?.value.isNullOrBlank() || !values["displayheight"]?.value.isNullOrBlank()
        val hasScale = !values["scalex"]?.value.isNullOrBlank() || !values["scaley"]?.value.isNullOrBlank()
        if (hasDisplaySize && hasScale) error(holder, tag.range, MyMessageBundle.message("diagnostic.floating.image.display.scale"))
    }

    private fun validateFrontmatter(text: String, schema: GuideNhSchemaService, holder: AnnotationHolder) {
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim() != "---") return
        var offset = lines.first().length + 1
        val indentKeys = sortedMapOf<Int, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.trim() == "---") break
            val match = Regex("^(\\s*)([A-Za-z_][\\w.-]*)\\s*:\\s*(.*)$").find(line)
            if (match == null) { offset += line.length + 1; continue }
            val indent = match.groupValues[1].length
            indentKeys.keys.filter { it >= indent }.toList().forEach { indentKeys.remove(it) }
            val path = indentKeys.toSortedMap().values.toList()
            val key = match.groupValues[2]
            if (schema.frontmatterKey(path, key) == null) {
                val start = offset + match.range.first + match.groupValues[1].length
                error(holder, TextRange(start, start + key.length), MyMessageBundle.message("diagnostic.frontmatter.key", key))
            } else {
                val value = match.groupValues[3].trim()
                if (value.isNotEmpty()) {
                    val keySchema = schema.frontmatterKey(path, key)
                    if (keySchema != null) validateFrontmatterValue(holder, offset + line.indexOf(value), value, keySchema.type, key)
                }
            }
            indentKeys[indent] = key
            offset += line.length + 1
        }
    }

    private fun validateFrontmatterValue(holder: AnnotationHolder, start: Int, value: String, type: String, key: String) {
        val normalized = value.removeSurrounding("\"", "\"").removeSurrounding("'", "'").trim()
        val valid = when (type.lowercase()) {
            "string", "date" -> type.equals("string", true) || Regex("\\d{4}-\\d{2}-\\d{2}(?:[T ].*)?").matches(normalized)
            "number" -> normalized.toDoubleOrNull() != null
            "boolean" -> normalized.equals("true", true) || normalized.equals("false", true)
            "list", "string_or_list" -> normalized.startsWith("[") && normalized.endsWith("]") || normalized.isNotEmpty()
            "map" -> normalized.isEmpty() || normalized.startsWith("{") || normalized.endsWith(":")
            else -> true
        }
        if (!valid) error(holder, TextRange(start, start + value.length), MyMessageBundle.message("diagnostic.frontmatter.value", key, type))
    }
}

private val COLOR_VALUE = Regex("^(?:#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|0x[0-9a-fA-F]{6}|0x[0-9a-fA-F]{8}|[A-Za-z_][\\w.-]*)$")

private fun findPreferredClosingBoundary(text: String, tags: List<GuideNhTag>, unclosedTag: GuideNhTag): GuideNhTag? {
    val startIndex = tags.indexOf(unclosedTag)
    if (startIndex < 0) return null
    for (index in startIndex + 1 until tags.size) {
        val tag = tags[index]
        if (!tag.closing && !tag.selfClosing) continue
        val nextStart = tags.getOrNull(index + 1)?.range?.startOffset ?: text.length
        if (text.substring(tag.range.endOffset, nextStart).any { !it.isWhitespace() }) return tag
    }
    return null
}

private class GuideNhCloseTagFix(
    private val tagNames: List<String>,
    private val sourceText: String,
    private val targetOffset: Int,
    private val sourceOffset: Int,
    private val afterTag: String? = null
) : com.intellij.codeInsight.intention.IntentionAction {
    override fun getFamilyName(): String = MyMessageBundle.message("fix.family")
    override fun getText(): String = afterTag?.let {
        MyMessageBundle.message("fix.close.after", it, tagNames.joinToString("、") { name -> "</$name>" })
    } ?: MyMessageBundle.message("fix.close.eof", tagNames.joinToString("、") { "</$it>" })
    override fun isAvailable(project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile): Boolean = true
    override fun invoke(project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile) {
        val document = editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val lineBreak = if (document.text.contains("\r\n")) "\r\n" else "\n"
            val offset = targetOffset.coerceIn(0, document.textLength)
            val indent = sourceText.substring(sourceText.lastIndexOf('\n', sourceOffset - 1) + 1, sourceOffset).takeWhile { it == ' ' || it == '\t' }
            val prefix = if (offset == document.textLength && (document.text.endsWith("\n") || document.text.endsWith("\r"))) "" else lineBreak
            document.insertString(offset, prefix + tagNames.joinToString(lineBreak) { "$indent</$it>" })
        }
    }
    override fun startInWriteAction(): Boolean = true
}

private class GuideNhInsertClosingBeforeFix(private val tagNames: List<String>) : com.intellij.codeInsight.intention.IntentionAction {
    override fun getFamilyName(): String = MyMessageBundle.message("fix.family")
    override fun getText(): String = MyMessageBundle.message("fix.close.before")
    override fun isAvailable(project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile): Boolean = true
    override fun invoke(project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: PsiFile) {
        val offset = editor.caretModel.offset
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(offset, tagNames.joinToString("\n") { "</$it>" } + "\n")
        }
    }
    override fun startInWriteAction(): Boolean = true
}

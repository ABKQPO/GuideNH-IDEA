package com.hfstudio.guidenh

/** Locates item-stack attributes supported by GuideNH item picker and editor drops. */
data class GuideNhItemStackContext(
    val tagName: String,
    val attributeName: String,
    val tagStart: Int,
    val tagEndExclusive: Int,
    val valueStart: Int,
    val valueEndExclusive: Int,
    val value: String
)

object GuideNhItemStackContexts {
    private val attributeTargets = mapOf(
        "itemlink" to setOf("id"),
        "itemimage" to setOf("id"),
        "blockimage" to setOf("id"),
        "block" to setOf("id"),
        "placeblock" to setOf("id"),
        "removeblocks" to setOf("id"),
        "replaceblock" to setOf("from", "to")
    )
    private val tagPattern = Regex("<([A-Za-z][A-Za-z0-9]*)\\b[^<>]*?>")
    private val attributePattern = Regex("([A-Za-z_][\\w.-]*)\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)')")

    fun findAt(text: CharSequence, offset: Int): GuideNhItemStackContext? {
        val source = text.toString()
        return tagPattern.findAll(source).asSequence().flatMap { tag ->
            val tagName = tag.groupValues[1]
            val targetAttributes = attributeTargets[tagName.lowercase()].orEmpty()
            if (targetAttributes.isEmpty()) return@flatMap emptySequence()
            attributePattern.findAll(tag.value).asSequence().mapNotNull { attribute ->
                val attributeName = attribute.groupValues[1]
                if (attributeName.lowercase() !in targetAttributes) return@mapNotNull null
                val valueGroup = if (attribute.groups[2] != null) attribute.groups[2]!! else attribute.groups[3]!!
                GuideNhItemStackContext(
                    tagName = tagName,
                    attributeName = attributeName,
                    tagStart = tag.range.first,
                    tagEndExclusive = tag.range.last + 1,
                    valueStart = tag.range.first + valueGroup.range.first,
                    valueEndExclusive = tag.range.first + valueGroup.range.last + 1,
                    value = valueGroup.value
                )
            }
        }.firstOrNull { context -> offset in context.valueStart..context.valueEndExclusive }
    }
}

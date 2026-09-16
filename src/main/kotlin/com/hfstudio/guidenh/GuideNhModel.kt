package com.hfstudio.guidenh

import com.intellij.openapi.util.TextRange

data class GuideNhAttribute(
    val name: String,
    val value: String?,
    val valueStyle: String? = null,
    val range: TextRange,
    val valueRange: TextRange
)

data class GuideNhTag(
    val name: String,
    val range: TextRange,
    val nameRange: TextRange,
    val attributes: List<GuideNhAttribute>,
    val closing: Boolean,
    val selfClosing: Boolean
)

data class GuideNhDocumentModel(
    val tags: List<GuideNhTag>,
    val references: List<GuideNhReference>
)

enum class GuideNhReferenceKind { PAGE, RESOURCE, ITEM, ORE }

data class GuideNhReference(
    val kind: GuideNhReferenceKind,
    val value: String,
    val range: TextRange,
    val attribute: String? = null,
    val declaration: Boolean = false
)

data class GuideNhTagSchema(
    val name: String = "",
    val description: String? = null,
    val attributes: Map<String, GuideNhAttributeSchema> = emptyMap(),
    val children: List<String> = emptyList(),
    /** Set when the body also takes ordinary block content, so [children] ranks completion instead of restricting it. */
    val preferredChildren: List<String> = emptyList(),
    /** Set when the tag accepts any attribute, so one it does not declare is legal rather than a mistake. */
    val forwardsAttributes: Boolean = false,
    val removedAttributes: Set<String> = emptySet()
)

data class GuideNhAttributeSchema(
    val type: String = "string",
    val description: String? = null,
    val valueStyle: String? = null,
    val values: List<String> = emptyList(),
    val required: Boolean = false,
    val requiredWhenMissing: List<String> = emptyList()
)

data class GuideNhFrontmatterKey(
    val type: String = "string",
    val description: String? = null,
    val children: Map<String, GuideNhFrontmatterKey> = emptyMap()
)

data class GuideNhSnippet(val prefix: String, val body: List<String>, val description: String? = null)

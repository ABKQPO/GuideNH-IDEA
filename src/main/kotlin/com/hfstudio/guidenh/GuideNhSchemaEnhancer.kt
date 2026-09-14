package com.hfstudio.guidenh

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Applies GuideVSC's compiler-specific schema knowledge to the generated overlay. */
object GuideNhSchemaEnhancer {
    data class JavaSource(val path: String, val text: String)

    fun enhance(root: JsonObject, sources: List<JavaSource>) {
        val tags = root.getAsJsonObject("tags") ?: JsonObject().also { root.add("tags", it) }
        charts(tags, sources)
        containers(tags)
        scenes(tags, sources)
        functionGraph(tags, source(sources, "FunctionGraphAttrs")?.text)
        recipes(tags)
        references(tags)
        contributors(tags, sources)
        markdownExtensions(root, sources)
        insertTemplates(root, sources)
    }

    private fun charts(tags: JsonObject, sources: List<JavaSource>) {
        val common = source(sources, "CommonChartAttrs")?.let { attributes(it.text) }.orEmpty()
        sources.filter { it.text.contains("CommonChartAttrs.apply") }.forEach { compiler ->
            tagNames(compiler.text).forEach { mergeAttrs(tags, it, common + axes(compiler.text)) }
        }
        source(sources, "ChartChildParser")?.text?.let { text ->
            if (text.contains("\"Series\"")) define(tags, "Series", "GuideNH chart series", mapOf(
                "color" to string(), "data" to string(), "icon" to string(), "iconImage" to string(), "name" to string(), "points" to string(), "tooltip" to string()), listOf("Point"))
            if (text.contains("\"LineSeries\"")) define(tags, "LineSeries", "GuideNH line chart series", mapOf(
                "color" to string(), "data" to string(), "icon" to string(), "iconImage" to string(), "name" to string(), "tooltip" to string()))
            if (text.contains("\"Slice\"")) define(tags, "Slice", "GuideNH pie chart slice", mapOf(
                "color" to string(), "icon" to string(), "iconImage" to string(), "label" to string(), "name" to string(), "tooltip" to string(), "value" to number("expression")))
            if (text.contains("\"PieInset\"")) define(tags, "PieInset", "GuideNH pie chart inset", mapOf(
                "direction" to string(), "height" to number("expression"), "position" to string(), "size" to number("expression"), "startAngleDeg" to number("expression"), "title" to string(), "titleColor" to string(), "width" to number("expression")), listOf("Slice"))
            define(tags, "Point", "GuideNH chart point", mapOf("atX" to number("expression"), "atY" to number("expression"), "color" to string(), "label" to string(), "plot" to number("expression"), "x" to number("expression"), "y" to number("expression")))
        }
        mapOf("ColumnChart" to listOf("Series", "LineSeries", "PieInset"), "BarChart" to listOf("Series", "LineSeries", "PieInset"), "LineChart" to listOf("Series"), "ScatterChart" to listOf("Series"), "PieChart" to listOf("Slice")).forEach { (tag, children) -> mergeChildren(tags, tag, children) }
    }

    private fun containers(tags: JsonObject) {
        define(tags, "ContentTabs", "GuideNH content tab container", mapOf("color" to color(), "default" to string(), "defaultIndex" to number(), "icon" to string(), "iconItem" to item(), "iconPng" to string(), "icon_item" to item(), "icon_png" to string(), "title" to string(), "width" to number(), "height" to number()), listOf("Tab"))
        define(tags, "Tab", "GuideNH content tab", mapOf("title" to string()))
        define(tags, "summary", "GuideNH details summary")
        define(tags, "NodeContent", "GuideNH Mermaid rich node content", mapOf("id" to string()))
        mergeChildren(tags, "Mermaid", listOf("NodeContent"))
    }

    private fun scenes(tags: JsonObject, sources: List<JavaSource>) {
        val children = linkedSetOf<String>()
        source(sources, "SceneTagCompiler")?.text?.let { children += sceneNames(it) }
        source(sources, "DefaultExtensions")?.text?.let { extensions ->
            Regex("new\\s+([A-Za-z0-9_]+ElementCompiler)\\s*\\(\\)").findAll(extensions).forEach { match ->
                source(sources, match.groupValues[1])?.let { children += tagNames(it.text) }
            }
        }
        children.removeAll(setOf("BlockStat", "GameScene", "Scene"))
        mergeChildren(tags, "GameScene", children); mergeChildren(tags, "Scene", children)
        define(tags, "BlockStats", "GuideNH scene block statistics", mapOf("buttonEnabled" to boolean("expression"), "corner" to string(), "dock" to string(), "filter" to string(), "filterMode" to string(), "maxHeight" to number(), "maxWidth" to number(), "mode" to string(), "showNames" to boolean("expression"), "visible" to boolean("expression")), listOf("BlockStat"))
        define(tags, "BlockStat", "GuideNH scene block statistic", mapOf("count" to number("expression"), "id" to item(), "item" to item(), "ore" to ore()))
        listOf("Block", "ImportStructure", "ImportStructureLib", "PlaceBlock", "ReplaceBlock").forEach { mergeAttrs(tags, it, mapOf("formed" to boolean("expression", "Whether controller previews are formed."))) }
        mergeAttrs(tags, "Entity", mapOf("baby" to boolean("expression"), "capeRotation" to string(), "headRotation" to string(), "leftArmRotation" to string(), "leftLegRotation" to string(), "rightArmRotation" to string(), "rightLegRotation" to string(), "showCape" to boolean("expression"), "showName" to boolean("expression")))
        mergeAttrs(tags, "LineAnnotation", mapOf("arrow" to string(), "pointColor" to color(), "points" to string(), "pointSize" to number("expression"), "showPoints" to boolean("expression")))
        define(tags, "LinePoint", "GuideNH line annotation point", mapOf("color" to color(), "index" to number(), "show" to boolean("bare"), "size" to number("expression")))
        val conditions = mapOf("showWhenChannels" to string(), "showWhenStructure" to string(), "showWhenTier" to string())
        listOf("BlockAnnotation", "BlockAnnotationTemplate", "BoxAnnotation", "DiamondAnnotation", "LineAnnotation", "PlaySound", "TextAnnotation").forEach { mergeAttrs(tags, it, conditions) }
        mergeAttrs(tags, "ImportStructureLib", mapOf("name" to string()))
        mergeAttrs(tags, "PlaySound", sound())
    }

    private fun functionGraph(tags: JsonObject, source: String?) {
        val container = functionContainer().toMutableMap(); val plot = functionPlot().toMutableMap()
        source?.let { text ->
            Regex("MdxAttrs\\.getString\\(compiler,\\s*sink,\\s*el,\\s*\"([^\"]+)\"").findAll(text).forEach { match -> val name = match.groupValues[1]; (if (name in functionContainerNames) container else plot)[name] = if (name.contains("color", true)) color() else string() }
            Regex("MdxAttrs\\.getInt\\(compiler,\\s*sink,\\s*el,\\s*\"([^\"]+)\"").findAll(text).forEach { container[it.groupValues[1]] = number() }
            Regex("MdxAttrs\\.getBoolean\\(compiler,\\s*sink,\\s*el,\\s*\"([^\"]+)\"").findAll(text).forEach { match -> val name = match.groupValues[1]; (if (name in functionContainerNames) container else plot)[name] = boolean("expression") }
        }
        mergeAttrs(tags, "FunctionGraph", container); mergeAttrs(tags, "Function", container + plot)
        define(tags, "Plot", "GuideNH function graph plot", plot - "name")
        removeAttrs(tags, "Plot", listOf("name")); removeAttrs(tags, "Function", listOf("name"))
        mergeChildren(tags, "FunctionGraph", listOf("Plot", "Function", "Point"))
    }

    private fun recipes(tags: JsonObject) {
        val attrs = mapOf("input" to string(), "output" to string(), "align" to string(), "float" to string(), "wrap" to string())
        listOf("Recipe", "Usage", "RecipeFor", "RecipeUsage", "RecipesFor", "RecipesUsage").forEach { mergeAttrs(tags, it, attrs) }
    }

    private fun references(tags: JsonObject) {
        listOf("GameScene", "Scene").forEach { removeAttrs(tags, it, listOf("background")) }
        mergeAttrs(tags, "Block", mapOf("ore" to ore())); mergeAttrs(tags, "BlockImage", mapOf("ore" to ore(), "align" to string(), "wrap" to string()))
        mergeAttrs(tags, "Recipe", mapOf("align" to string(), "wrap" to string())); mergeAttrs(tags, "Column", mapOf("align" to string(), "wrap" to string()))
        mergeAttrs(tags, "ItemImage", mapOf("align" to string(), "nbt" to string(), "noTooltip" to boolean("expression"), "showTooltip" to boolean(), "show_tooltip" to boolean(), "tooltip" to string()))
        listOf("ItemLink", "QuestCard", "QuestLink").forEach { mergeAttrs(tags, it, mapOf("showTooltip" to boolean(), "show_tooltip" to boolean())) }
        mergeAttrs(tags, "ItemLink", mapOf("showIcon" to string("Icon side, or a truthy value for the right side.")))
        mergeAttrs(tags, "br", mapOf("clear" to enum(listOf("none", "left", "right", "all"))))
        mergeAttrs(tags, "FloatingImage", mapOf("alt" to string(), "displayHeight" to number(), "displayWidth" to number(), "h" to number(), "height" to number(), "scaleX" to number(), "scaleY" to number(), "sound" to string(), "soundSrc" to resource(), "src" to resource(), "trigger" to string(), "volume" to number("expression"), "w" to number(), "wrap" to string(), "width" to number(), "x" to number(), "y" to number()))
        mergeAttrs(tags, "SoundLink", sound())
        define(tags, "ImageAnnotation", "GuideNH floating image annotation", mapOf("border" to boolean("bare"), "borderColor" to color(), "borderThickness" to number(), "h" to number(), "sound" to string(), "src" to resource(), "trigger" to string(), "volume" to number("expression"), "w" to number(), "x" to number(), "y" to number()))
        define(tags, "SoundArea", "GuideNH floating image sound area", sound() + mapOf("h" to number(), "w" to number(), "trigger" to string(), "x" to number(), "y" to number()))
        mergeChildren(tags, "FloatingImage", listOf("ImageAnnotation", "SoundArea"))
    }

    private fun attributes(text: String): Map<String, JsonObject> = buildMap {
        Regex("MdxAttrs\\s*\\.\\s*([A-Za-z0-9_]+)\\s*\\([^;]*?[\"']([^\"']+)[\"'][^;]*?\\)", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { put(it.groupValues[2], reader(it.groupValues[1])) }
    }
    private fun axes(text: String): Map<String, JsonObject> = buildMap {
        Regex("parseAxisOptions\\([^;]*?\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { match ->
            val prefix = match.groupValues[1]; put("${prefix}Label", string()); put("${prefix}Min", number("expression")); put("${prefix}Max", number("expression")); put("${prefix}Step", number("expression")); put("${prefix}Unit", string()); put("${prefix}TickFormat", string()); put(match.groupValues[2], boolean("expression")); put(match.groupValues[3], string())
        }
    }
    private fun tagNames(text: String): Set<String> = buildSet {
        Regex("[\"']([A-Za-z][A-Za-z0-9]*)[\"']\\s*,\\s*new\\s+[A-Za-z0-9_]+Compiler").findAll(text).forEach { add(it.groupValues[1]) }
        Regex("getTagNames\\s*\\(\\s*\\)\\s*\\{").findAll(text).forEach { match -> matchingBrace(text, text.indexOf('{', match.range.first))?.let { closing -> Regex("[\"']([A-Za-z][A-Za-z0-9]*)[\"']").findAll(text.substring(match.range.last + 1, closing)).forEach { name -> add(name.groupValues[1]) } } }
    }
    private fun sceneNames(text: String): Set<String> = buildSet { Regex("\"([A-Z][A-Za-z0-9]*)\"\\.equals\\(name\\)|(?:s\\.add|Collections\\.singleton)\\(\"([A-Z][A-Za-z0-9]*)\"\\)").findAll(text).forEach { add(it.groupValues[1].ifBlank { it.groupValues[2] }) } }
    private fun matchingBrace(text: String, opening: Int): Int? { var depth = 0; if (opening < 0) return null; for (index in opening until text.length) when (text[index]) { '{' -> depth++; '}' -> if (--depth == 0) return index }; return null }
    private fun source(sources: List<JavaSource>, name: String) = sources.firstOrNull { it.path.replace('\\', '/').endsWith("/$name.java") }

    private fun define(tags: JsonObject, name: String, description: String, attrs: Map<String, JsonObject> = emptyMap(), children: List<String> = emptyList()) { val tag = tag(tags, name, description); mergeAttrs(tag, attrs); mergeChildren(tag, children) }
    private fun mergeAttrs(tags: JsonObject, name: String, attrs: Map<String, JsonObject>) = mergeAttrs(tag(tags, name), attrs)
    private fun mergeAttrs(tag: JsonObject, attrs: Map<String, JsonObject>) { val target = tag.getAsJsonObject("attributes") ?: JsonObject().also { tag.add("attributes", it) }; attrs.forEach { (name, value) -> target.add(name, value.deepCopy()) } }
    private fun removeAttrs(tags: JsonObject, name: String, names: List<String>) { val tag = tag(tags, name); val removed = tag.getAsJsonArray("removeAttributes") ?: JsonArray().also { tag.add("removeAttributes", it) }; names.filterNot { name -> removed.any { it.asString.equals(name, true) } }.forEach(removed::add) }
    private fun mergeChildren(tags: JsonObject, name: String, children: Collection<String>) = mergeChildren(tag(tags, name), children)
    private fun mergeChildren(tag: JsonObject, children: Collection<String>) { val all = (tag.getAsJsonArray("children")?.map { it.asString }.orEmpty() + children).distinct().sorted(); tag.add("children", JsonArray().also { array -> all.forEach(array::add) }) }
    private fun tag(tags: JsonObject, name: String, description: String? = null): JsonObject = tags.entrySet().firstOrNull { it.key.equals(name, true) }?.value?.asJsonObject ?: JsonObject().also { value -> value.addProperty("name", name); description?.let { value.addProperty("description", it) }; value.add("attributes", JsonObject()); value.add("children", JsonArray()); tags.add(name, value) }

    /**
     * Reads the syntax declared by GuideNH syntax contributors.
     *
     * Compiler sources only describe the tags a compiler owns. Attributes read by shared parsers or by
     * the scene runtime, container children and container-only tags live in a `SyntaxContributor`, which
     * is declarative data: `sink.attributes("Tag", AttributeSyntax.of("name", SyntaxValueKind.KIND))`
     * plus shared `private static final AttributeSyntax NAME = AttributeSyntax.of(...)` declarations.
     * Reading it keeps this schema in step with the mod, including what third-party mods contribute.
     */
    private fun contributors(tags: JsonObject, sources: List<JavaSource>) {
        for (source in sources) {
            if (!source.text.contains("SyntaxContributor")) continue
            val shared = LinkedHashMap<String, Pair<String, JsonObject>>()
            Regex(
                "AttributeSyntax\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*AttributeSyntax\\s*\\.\\s*of\\(\\s*\"([^\"]+)\"\\s*,\\s*SyntaxValueKind\\s*\\.\\s*([A-Z0-9_]+)(?:\\s*,\\s*([\\s\\S]*?))?\\)\\s*;",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(source.text).forEach { match ->
                shared[match.groupValues[1]] = match.groupValues[2] to contributorAttr(match.groupValues[3], quoted(match.groupValues[4]))
            }
            Regex("sink\\s*\\.\\s*(?:tags|containerTags)\\(([\\s\\S]*?)\\)\\s*;").findAll(source.text).forEach { call ->
                quoted(call.groupValues[1]).forEach { name -> tag(tags, name) }
            }
            Regex("sink\\s*\\.\\s*attributes\\(\\s*\"([^\"]+)\"\\s*,([\\s\\S]*?)\\)\\s*;").findAll(source.text).forEach { call ->
                val attrs = LinkedHashMap<String, JsonObject>()
                for (argument in splitArguments(call.groupValues[2])) {
                    val inline = Regex(
                        "^AttributeSyntax\\s*\\.\\s*of\\(\\s*\"([^\"]+)\"\\s*,\\s*SyntaxValueKind\\s*\\.\\s*([A-Z0-9_]+)(?:\\s*,\\s*([\\s\\S]*))?\\)$",
                        RegexOption.DOT_MATCHES_ALL
                    ).find(argument)
                    if (inline != null) {
                        attrs[inline.groupValues[1]] = contributorAttr(inline.groupValues[2], quoted(inline.groupValues[3]))
                        continue
                    }
                    val declared = shared[argument]
                    if (declared != null) attrs[declared.first] = declared.second
                }
                if (attrs.isNotEmpty()) mergeAttrs(tags, call.groupValues[1], attrs)
            }
            Regex("sink\\s*\\.\\s*children\\(\\s*\"([^\"]+)\"\\s*,([\\s\\S]*?)\\)\\s*;").findAll(source.text).forEach { call ->
                mergeChildren(tags, call.groupValues[1], quoted(call.groupValues[2]))
            }
        }
    }

    /**
     * Reads the markdown syntax the GuideNH sources declare.
     *
     * Inline markers come from paired `MarkdownSnippet.inline("trigger", "label", "replacement",
     * caretOffset)` calls: a snippet whose replacement is its own trigger twice wraps the selection in
     * that marker. Fence names come from `sink.fenceLanguages(...)`, resolving literal names, constants
     * read from the class that declares them and the entries of the code block language registry. A
     * third-party contributor is read exactly like the built-in one.
     */
    private fun markdownExtensions(root: JsonObject, sources: List<JavaSource>) {
        val section = root.getAsJsonObject("markdownExtensions") ?: JsonObject().also { root.add("markdownExtensions", it) }
        val markers = section.getAsJsonObject("inlineMarkers") ?: JsonObject().also { section.add("inlineMarkers", it) }
        val fences = section.getAsJsonObject("fencedCodeBlocks") ?: JsonObject().also { section.add("fencedCodeBlocks", it) }
        for (source in sources) {
            if (source.text.contains("MarkdownSnippet")) {
                Regex("MarkdownSnippet\\s*\\.\\s*inline\\(\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"").findAll(source.text).forEach { match ->
                    val trigger = match.groupValues[1]
                    val name = markerKey(match.groupValues[2])
                    if (trigger.isNotEmpty() && match.groupValues[3] == trigger + trigger && markers.get(name) == null) {
                        markers.add(name, JsonObject().apply {
                            addProperty("open", trigger)
                            addProperty("close", trigger)
                            addProperty("description", "${match.groupValues[2]}.")
                        })
                    }
                }
            }
            Regex("sink\\s*\\.\\s*fenceLanguages\\(([\\s\\S]*?)\\)\\s*;").findAll(source.text).forEach { call ->
                for (argument in splitArguments(call.groupValues[1])) {
                    for (fence in fenceLanguages(argument, sources)) {
                        if (fences.get(fence.first) == null) fences.add(fence.first, JsonObject().apply { addProperty("description", fence.second) })
                    }
                }
            }
        }
    }

    private fun fenceLanguages(argument: String, sources: List<JavaSource>): List<Pair<String, String>> {
        val literals = quoted(argument)
        if (literals.isNotEmpty()) return literals.map { it to fenceDescription(it) }
        val constant = Regex("^([A-Za-z0-9_]+)\\s*\\.\\s*([A-Z0-9_]+)\\b").find(argument)
        if (constant != null) {
            val values = stringConstant(sources, constant.groupValues[1], constant.groupValues[2])
            if (values.isNotEmpty()) return values.map { it to fenceDescription(it) }
        }
        val call = Regex("^([A-Za-z0-9_]+)\\s*\\.\\s*([A-Za-z0-9_]+)").find(argument) ?: return emptyList()
        return registeredLanguages(sources, call.groupValues[1], call.groupValues[2])
    }

    /** Reads `static final List<String> NAME = List.of("a", "b");` from the class that declares it. */
    private fun stringConstant(sources: List<JavaSource>, className: String, constantName: String): List<String> {
        val text = source(sources, className)?.text ?: return emptyList()
        val declaration = Regex("\\b$constantName\\b\\s*=\\s*([^;]+);").find(text) ?: return emptyList()
        return quoted(declaration.groupValues[1])
    }

    /**
     * Reads the fence names a registry class exposes. A method that announces aliases answers with the
     * alias names it maps, written as `registerAlias(result, "languageId", "alias", ...)`; any other
     * method answers with the registered languages, written as `new CodeBlockLanguage("id", "Label")`.
     */
    private fun registeredLanguages(sources: List<JavaSource>, className: String, methodName: String): List<Pair<String, String>> {
        val text = source(sources, className)?.text ?: return emptyList()
        if (methodName.contains("alias", true)) {
            val aliases = LinkedHashSet<String>()
            Regex("registerAlias\\s*\\(\\s*[A-Za-z0-9_]+,\\s*([^;]*)\\)\\s*;").findAll(text).forEach { call ->
                quoted(call.groupValues[1]).drop(1).forEach { aliases.add(it) }
            }
            return aliases.map { it to fenceDescription(it) }
        }
        return Regex("new\\s+[A-Za-z0-9_]*Language\\s*\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*\"([^\"]+)\")?").findAll(text).map { match ->
            val label = match.groupValues[2]
            match.groupValues[1] to if (label.isEmpty()) fenceDescription(match.groupValues[1]) else "$label fenced code block."
        }.toList()
    }

    private fun markerKey(label: String): String = label.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        .mapIndexed { index, word -> if (index == 0) word.lowercase() else word.substring(0, 1).uppercase() + word.substring(1).lowercase() }
        .joinToString("")

    /** One Java string literal, including escaped characters such as a quote or a line break. */
    private val JAVA_STRING_LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun fenceDescription(name: String) = "$name fenced code block."

    /**
     * Reads the insert templates the GuideNH sources declare.
     *
     * A contributor writes the text a tag completes as with `InsertTemplate.caretAfter(tag, text, marker)`,
     * `InsertTemplate.of(tag, text)` or `new InsertTemplate(tag, text, caretOffset)`. The overlay carries
     * them as snippets, so the editor inserts the same form with the caret at the same place.
     */
    private fun insertTemplates(root: JsonObject, sources: List<JavaSource>) {
        val snippets = root.getAsJsonObject("snippets") ?: JsonObject().also { root.add("snippets", it) }
        for (source in sources) {
            if (!source.text.contains("InsertTemplate")) continue
            Regex("sink\\s*\\.\\s*insertTemplates\\(([\\s\\S]*?)\\)\\s*;").findAll(source.text).forEach { call ->
                for (argument in splitArguments(call.groupValues[1])) {
                    val template = readInsertTemplate(argument) ?: continue
                    val key = "guidenh." + template.tagName.replaceFirstChar { it.lowercase() }
                    if (snippets.get(key) != null) continue
                    snippets.add(key, JsonObject().apply {
                        addProperty("prefix", template.tagName)
                        add("body", JsonArray().also { array -> tabStoppedBody(template).forEach(array::add) })
                        addProperty("description", "Insert a <${template.tagName}> tag.")
                    })
                }
            }
        }
    }

    private data class InsertTemplateDeclaration(val tagName: String, val text: String, val caretOffset: Int)

    private fun readInsertTemplate(argument: String): InsertTemplateDeclaration? {
        val literals = JAVA_STRING_LITERAL.findAll(argument).map { unescapeJava(it.groupValues[1]) }.toList()
        if (literals.size < 2) return null
        val tagName = literals[0]
        val text = literals[1]
        if (argument.contains("caretAfter")) {
            val marker = literals.getOrNull(2) ?: return null
            val index = if (marker.isEmpty()) -1 else text.indexOf(marker)
            return InsertTemplateDeclaration(tagName, text, if (index >= 0) index + marker.length else text.length)
        }
        val declared = Regex(",\\s*(\\d+)\\s*\\)\\s*$").find(argument.trim())
        return InsertTemplateDeclaration(tagName, text, declared?.groupValues?.get(1)?.toIntOrNull() ?: text.length)
    }

    /** Splits the template into the lines the editor inserts, with the final tab stop at the caret. */
    private fun tabStoppedBody(template: InsertTemplateDeclaration): List<String> {
        val offset = template.caretOffset.coerceIn(0, template.text.length)
        return (template.text.substring(0, offset) + "\$0" + template.text.substring(offset)).split("\n")
    }

    /** Resolves the escapes a Java string literal carries, so the exported snippet keeps its line breaks. */
    private fun unescapeJava(text: String) = text
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun contributorAttr(kind: String, values: List<String>): JsonObject = when (kind) {
        "INT", "FLOAT" -> number()
        "BOOLEAN" -> boolean()
        "COLOR" -> color()
        "ENUM" -> if (values.isEmpty()) string() else enum(values)
        "ITEM_ID", "BLOCK_ID" -> item()
        "ORE_DICT" -> ore()
        "PAGE_PATH" -> attr("page")
        "FILE_PATH" -> resource()
        "SNBT", "VECTOR3" -> attr("string", "bare")
        else -> string()
    }

    private fun quoted(text: String): List<String> = Regex("\"([^\"\n]*)\"").findAll(text).map { it.groupValues[1] }.toList()

    /** Splits an argument list on commas that sit outside parentheses and strings. */
    private fun splitArguments(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var inString = false
        var escaped = false
        val current = StringBuilder()
        for (character in text) {
            if (inString && escaped) {
                escaped = false
                current.append(character)
                continue
            }
            if (inString && character == '\\') {
                escaped = true
                current.append(character)
                continue
            }
            if (character == '"') inString = !inString
            if (!inString) {
                if (character == '(') depth++
                if (character == ')') depth--
                if (character == ',' && depth == 0) {
                    parts.add(current.toString().trim())
                    current.setLength(0)
                    continue
                }
            }
            current.append(character)
        }
        if (current.toString().trim().isNotEmpty()) parts.add(current.toString().trim())
        return parts
    }
    private fun attr(type: String, style: String = "string", description: String? = null, values: List<String> = emptyList()) = JsonObject().apply { addProperty("type", type); addProperty("valueStyle", style); description?.let { addProperty("description", it) }; if (values.isNotEmpty()) add("values", JsonArray().also { values.forEach(it::add) }) }
    private fun string(description: String? = null) = attr("string", description = description); private fun number(style: String = "string") = attr("number", style); private fun boolean(style: String = "string", description: String? = null) = attr("boolean", style, description); private fun color() = attr("color"); private fun item() = attr("item"); private fun ore() = attr("ore"); private fun resource() = attr("resource"); private fun enum(values: List<String>) = attr("enum", values = values)
    private fun reader(name: String) = when { name.contains("boolean", true) -> boolean("expression"); name.contains("int", true) || name.contains("float", true) || name.contains("double", true) || name.contains("number", true) -> number(); name.contains("color", true) -> color(); name.contains("itemstack", true) -> item(); name.contains("ore", true) -> ore(); name.contains("resource", true) -> resource(); name.contains("page", true) -> attr("page"); else -> string() }
    private fun sound() = mapOf("cooldown" to number(), "minVolume" to number("expression"), "pitch" to number("expression"), "radius" to number("expression"), "sound" to string(), "src" to resource(), "volume" to number("expression"), "x" to number("expression"), "y" to number("expression"), "z" to number("expression"))
    private val functionContainerNames = setOf("axisColor", "background", "border", "cornerLegend", "cornerLegendBackground", "cornerLegendHeight", "cornerLegendWidth", "domain", "gridColor", "height", "quadrants", "showAxes", "showGrid", "title", "width", "xLabel", "xMax", "xMin", "xRange", "xStep", "yMax", "yMin", "yLabel", "yRange", "yStep")
    private fun functionContainer() = mapOf("axisColor" to color(), "background" to color(), "border" to color(), "cornerLegend" to string(), "cornerLegendBackground" to color(), "cornerLegendHeight" to number(), "cornerLegendWidth" to number(), "domain" to string(), "gridColor" to color(), "height" to number(), "quadrants" to string(), "showAxes" to boolean("expression"), "showGrid" to boolean("expression"), "title" to string(), "width" to number(), "xLabel" to string(), "xMax" to string(), "xMin" to string(), "xRange" to string(), "xStep" to string(), "yMax" to string(), "yMin" to string(), "yLabel" to string(), "yRange" to string(), "yStep" to string())
    private fun functionPlot() = mapOf("autoPointColor" to color(), "autoPointLabel" to string(), "color" to color(), "domain" to string(), "expr" to string(), "inverse" to boolean("expression"), "label" to string(), "pointEveryX" to string(), "pointEveryY" to string(), "showFunction" to boolean("expression"), "showValues" to boolean("expression"), "tooltip" to string())
}

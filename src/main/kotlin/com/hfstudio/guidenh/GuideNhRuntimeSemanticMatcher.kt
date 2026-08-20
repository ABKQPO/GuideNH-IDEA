package com.hfstudio.guidenh

/** Keeps runtime completion ranking consistent with GuideVSC without coupling it to bridge transport. */
object GuideNhRuntimeSemanticMatcher {
    fun query(capability: String, entries: List<GuideNhRuntimeEntry>, prefix: String, limit: Int): List<GuideNhRuntimeEntry> {
        if (limit <= 0) return emptyList()
        if (prefix.isBlank()) return entries.take(limit)
        return if (capability == "items") queryItems(entries, prefix, limit) else queryPrefix(entries, prefix, limit)
    }

    private fun queryPrefix(entries: List<GuideNhRuntimeEntry>, prefix: String, limit: Int): List<GuideNhRuntimeEntry> {
        val lowered = prefix.lowercase()
        return entries.asSequence()
            .filter { entry ->
                entry.id.startsWith(lowered, true) ||
                    entry.label?.startsWith(lowered, true) == true ||
                    entry.detail?.startsWith(lowered, true) == true
            }
            .take(limit)
            .toList()
    }

    private fun queryItems(entries: List<GuideNhRuntimeEntry>, prefix: String, limit: Int): List<GuideNhRuntimeEntry> {
        val lowered = prefix.lowercase()
        val compactPrefix = compact(prefix)
        val familySizes = entries.groupingBy { familyKey(it.id) }.eachCount()
        return entries.mapIndexedNotNull { index, entry ->
            itemScore(entry, lowered, compactPrefix)?.let { score ->
                ItemMatch(
                    entry = entry,
                    score = score,
                    index = index,
                    pathLength = pathKey(entry.id).length,
                    structuredSpecificity = structuredSpecificity(path(entry.id), compactPrefix, score),
                    familySize = familySizes[familyKey(entry.id)] ?: 0
                )
            }
        }.sortedWith(compareBy<ItemMatch> { it.score }
            .thenByDescending { if (it.score == 9) it.familySize else 0 }
            .thenByDescending { if (it.score == 9) it.structuredSpecificity else 0 }
            .thenBy { if (it.score in 9..12) it.pathLength else 0 }
            .thenBy { it.index })
            .take(limit)
            .map { it.entry }
    }

    private fun itemScore(entry: GuideNhRuntimeEntry, prefix: String, compactPrefix: String): Int? {
        val id = entry.id.lowercase()
        val namespace = namespace(entry.id)
        val label = entry.label.orEmpty().lowercase()
        val detail = entry.detail.orEmpty().lowercase()
        val path = pathKey(entry.id)
        val rawPath = path(entry.id)
        val tokenInitials = initials(rawPath)
        val labelInitials = initials(label)
        val shortPrefix = prefix.isNotEmpty() && prefix.length <= 4 && ':' !in prefix
        return when {
            label == prefix -> 0
            id == prefix || detail == prefix -> 1
            namespace.startsWith(prefix) && shortPrefix -> 2
            label.startsWith(prefix) -> 3
            tokenPrefix(label, prefix) -> 4
            id.startsWith(prefix) -> 5
            path.startsWith(prefix) -> 6
            tokenPrefix(id, prefix) || tokenPrefix(path, prefix) -> 7
            detail.startsWith(prefix) -> 8
            structuredAbbreviation(rawPath, compactPrefix) && compactPrefix.length >= 2 -> 9
            compactPrefix.isEmpty() -> null
            tokenInitials.startsWith(compactPrefix) && compactPrefix.length >= 2 -> 10
            compact(id).startsWith(compactPrefix) -> 11
            compact(path).startsWith(compactPrefix) -> 12
            labelInitials.startsWith(compactPrefix) && compactPrefix.length >= 2 -> 13
            compact(label).startsWith(compactPrefix) -> 14
            compact(detail).startsWith(compactPrefix) -> 15
            else -> null
        }
    }

    private fun namespace(value: String): String = value.substringBefore(':').lowercase()
    private fun path(value: String): String = value.substringAfter(':', value)
    private fun pathKey(value: String): String = path(value).lowercase()
    private fun compact(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
    private fun tokenPrefix(value: String, prefix: String): Boolean = tokens(value).any { it.startsWith(prefix) }
    private fun initials(value: String): String = tokens(value).joinToString("") { it.take(1) }

    private fun familyKey(id: String): String {
        val namespace = id.substringBefore(':', "").lowercase().let { if (it.isEmpty()) "" else "$it:" }
        val rawPath = path(id).replace(Regex(":\\d+$"), "")
        return namespace + rawPath.lowercase()
    }

    private fun structuredAbbreviation(rawPath: String, compactPrefix: String): Boolean {
        val tokens = tokens(rawPath)
        if (compactPrefix.length < 2 || tokens.size < 2) return false
        val first = tokens.first()
        if (!compactPrefix.startsWith(first) || compactPrefix.length <= first.length) return false
        var queryIndex = first.length
        for (token in tokens.drop(1)) {
            if (queryIndex >= compactPrefix.length) break
            if (!token.startsWith(compactPrefix[queryIndex])) return false
            queryIndex++
        }
        return queryIndex == compactPrefix.length
    }

    private fun structuredSpecificity(rawPath: String, compactPrefix: String, score: Int): Int {
        if (score != 9) return 0
        val tokens = tokens(rawPath)
        if (compactPrefix.length < 2 || tokens.size < 2) return 0
        val first = tokens.first()
        if (!compactPrefix.startsWith(first) || compactPrefix.length <= first.length) return 0
        var queryIndex = first.length
        var specificity = first.length
        for (token in tokens.drop(1)) {
            if (queryIndex >= compactPrefix.length) break
            if (!token.startsWith(compactPrefix[queryIndex])) return 0
            specificity += token.length
            queryIndex++
        }
        return if (queryIndex == compactPrefix.length) specificity else 0
    }

    private fun tokens(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) tokens += current.toString().lowercase()
            current.clear()
        }
        value.forEachIndexed { index, character ->
            if (!character.isLetterOrDigit()) {
                flush()
                return@forEachIndexed
            }
            val previous = current.lastOrNull()
            val next = value.getOrNull(index + 1)
            val split = previous != null && (
                previous.isDigit() != character.isDigit() ||
                    (previous.isLowerCase() && character.isUpperCase()) ||
                    (previous.isUpperCase() && character.isUpperCase() && next?.isLowerCase() == true)
                )
            if (split) flush()
            current.append(character)
        }
        flush()
        return tokens
    }

    private data class ItemMatch(
        val entry: GuideNhRuntimeEntry,
        val score: Int,
        val index: Int,
        val pathLength: Int,
        val structuredSpecificity: Int,
        val familySize: Int
    )
}

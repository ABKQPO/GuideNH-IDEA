package com.hfstudio.guidenh

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.util.IncorrectOperationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class GuideNhPersistedRuntimeEntry(val capability: String, val id: String, val label: String?, val detail: String?)

/** Project-scoped, durable runtime symbols used as targets for Find Usages. */
@Service(Service.Level.PROJECT)
class GuideNhRuntimeSemanticIndex(private val project: Project) {
    private val entries = ConcurrentHashMap<String, GuideNhPersistedRuntimeEntry>()
    private val elements = ConcurrentHashMap<String, GuideNhRuntimeSemanticElement>()

    init { load() }

    fun record(capability: String, values: Collection<GuideNhRuntimeEntry>) {
        if (capability !in PERSISTED_CAPABILITIES) return
        var changed = false
        values.forEach { value ->
            val entry = GuideNhPersistedRuntimeEntry(capability, value.id, value.label, value.detail)
            val key = key(capability, value.id)
            if (entries.put(key, entry) != entry) changed = true
        }
        if (changed) save()
    }

    fun resolve(reference: GuideNhReference): PsiElement? {
        val capability = when (reference.kind) {
            GuideNhReferenceKind.ITEM -> "items"
            GuideNhReferenceKind.ORE -> "ores"
            else -> return null
        }
        val entry = entries[key(capability, reference.value)] ?: return null
        return elements.computeIfAbsent(key(capability, entry.id)) {
            GuideNhRuntimeSemanticElement(PsiManager.getInstance(project), entry)
        }
    }

    private fun load() {
        val file = storageFile() ?: return
        val root = runCatching { Gson().fromJson(Files.readString(file), JsonObject::class.java) }.getOrNull() ?: return
        root.getAsJsonArray("entries")?.forEach { value ->
            val objectValue = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val capability = objectValue.get("capability")?.asString ?: return@forEach
            val id = objectValue.get("id")?.asString ?: return@forEach
            if (capability !in PERSISTED_CAPABILITIES || id.isBlank()) return@forEach
            entries[key(capability, id)] = GuideNhPersistedRuntimeEntry(capability, id, objectValue.get("label")?.asString, objectValue.get("detail")?.asString)
        }
    }

    private fun save() {
        val file = storageFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val root = JsonObject().apply {
                addProperty("schemaVersion", 1)
                add("entries", Gson().toJsonTree(entries.values.sortedWith(compareBy({ it.capability }, { it.id.lowercase() }))))
            }
            Files.writeString(file, Gson().newBuilder().setPrettyPrinting().create().toJson(root))
        }
    }

    private fun storageFile(): Path? = project.basePath?.let { Path.of(it, ".idea", "guidenh", "runtime-semantic-index.json") }
    private fun key(capability: String, id: String) = "${capability.lowercase()}\u0000${id.lowercase()}"

    companion object {
        private val PERSISTED_CAPABILITIES = setOf("items", "ores")
        fun get(project: Project): GuideNhRuntimeSemanticIndex = project.getService(GuideNhRuntimeSemanticIndex::class.java)
    }
}

class GuideNhRuntimeSemanticElement(
    manager: PsiManager,
    val entry: GuideNhPersistedRuntimeEntry
) : LightElement(manager, PlainTextLanguage.INSTANCE), PsiNamedElement {
    override fun getName(): String = entry.id
    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Runtime semantic symbols are read-only")
    override fun getText(): String = entry.label?.takeIf { it.isNotBlank() } ?: entry.id
    override fun toString(): String = "GuideNH runtime ${entry.capability}: ${entry.id}"
}

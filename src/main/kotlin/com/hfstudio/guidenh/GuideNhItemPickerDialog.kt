package com.hfstudio.guidenh

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Image
import java.awt.Component
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JComboBox
import javax.swing.ListSelectionModel
import javax.swing.JList
import javax.swing.TransferHandler
import java.awt.datatransfer.StringSelection
import javax.swing.event.DocumentEvent
import com.hfstudio.MyMessageBundle

/** Native IDEA replacement for GuideVSC's ItemStack picker webview. */
data class GuideNhItemStackSelection(val id: String, val meta: Int?, val count: Int, val nbt: String?, val renderVariant: String)

class GuideNhItemPickerDialog(project: Project, initialValue: String) : DialogWrapper(project) {
    private data class Entry(val id: String, val label: String?, val detail: String?) {
        override fun toString(): String = label?.takeIf { it.isNotBlank() }?.let { "$it  ($id)" } ?: id
    }

    private val initial = parseItemStack(initialValue)
    private val query = JBTextField(initial.id)
    private val meta = JBTextField(initial.meta?.toString().orEmpty())
    private val count = JBTextField("1")
    private val nbt = JBTextArea(initial.nbt.orEmpty())
    private val renderVariant = JComboBox(arrayOf("picker", "inline", "default"))
    private val entries = DefaultListModel<Entry>()
    private val resultList = JBList(entries)
    private val gridIcons = ConcurrentHashMap<String, ImageIcon>()
    private val previewIcon = JBLabel(MyMessageBundle.message("picker.unavailable"), JBLabel.CENTER)
    private val previewName = JBLabel()
    private val previewDetail = JBLabel()
    private val previewTooltip = JBTextArea()
    private val requestVersion = AtomicInteger()

    init {
        title = MyMessageBundle.message("picker.title")
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.layoutOrientation = JList.HORIZONTAL_WRAP
        resultList.visibleRowCount = 0
        resultList.fixedCellWidth = 104
        resultList.fixedCellHeight = 96
        resultList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val entry = value as? Entry
                component.horizontalAlignment = JLabel.CENTER
                component.verticalAlignment = JLabel.TOP
                component.iconTextGap = 4
                component.icon = entry?.let { gridIcons[it.id] }
                component.text = entry?.label?.takeIf { it.isNotBlank() } ?: entry?.id.orEmpty()
                component.toolTipText = entry?.id
                component.border = com.intellij.util.ui.JBUI.Borders.empty(5)
                return component
            }
        }
        resultList.dragEnabled = true
        resultList.transferHandler = object : TransferHandler() {
            override fun getSourceActions(component: JComponent): Int = COPY
            override fun createTransferable(component: JComponent) = resultList.selectedValue?.id?.let(::StringSelection)
        }
        query.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = loadEntries(query.text)
        })
        listOf(meta, count).forEach { field -> field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refreshSelectedPreview()
        }) }
        nbt.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refreshSelectedPreview()
        })
        renderVariant.addActionListener { refreshSelectedPreview() }
        resultList.addListSelectionListener { if (!it.valueIsAdjusting) loadPreview(resultList.selectedValue) }
        previewIcon.preferredSize = Dimension(72, 72)
        previewTooltip.isEditable = false
        previewTooltip.isOpaque = false
        previewTooltip.lineWrap = true
        previewTooltip.wrapStyleWord = true
        init()
        loadEntries(initial.id)
    }

    val selection: GuideNhItemStackSelection? get() = resultList.selectedValue?.id?.let { id ->
        val parsedMeta = meta.text.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        val parsedCount = count.text.trim().toIntOrNull()?.coerceIn(1, 64) ?: 1
        GuideNhItemStackSelection(id, parsedMeta, parsedCount, nbt.text.trim().takeIf(String::isNotEmpty), renderVariant.selectedItem as String)
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(0, 8)).apply {
            add(query, BorderLayout.NORTH)
            add(JBScrollPane(resultList).apply { preferredSize = Dimension(360, 360) }, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout(10, 0)).apply {
            add(previewIcon, BorderLayout.WEST)
            add(JPanel(GridLayout(0, 1, 0, 3)).apply {
                add(previewName)
                add(previewDetail)
            }, BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout(0, 10)).apply {
            border = com.intellij.util.ui.JBUI.Borders.emptyLeft(14)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(previewTooltip).apply { border = com.intellij.util.ui.JBUI.Borders.empty() }, BorderLayout.CENTER)
            preferredSize = Dimension(360, 360)
        }
        val controls = JPanel(GridLayout(0, 2, 8, 6)).apply {
            add(JBLabel(MyMessageBundle.message("picker.meta"))); add(meta)
            add(JBLabel(MyMessageBundle.message("picker.count"))); add(count)
            add(JBLabel(MyMessageBundle.message("picker.variant"))); add(renderVariant)
            add(JBLabel(MyMessageBundle.message("picker.nbt"))); add(JBScrollPane(nbt).apply { preferredSize = Dimension(180, 58) })
        }
        right.add(controls, BorderLayout.SOUTH)
        return JPanel(BorderLayout()).apply {
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.CENTER)
            preferredSize = Dimension(760, 380)
        }
    }

    private fun loadEntries(prefix: String) {
        val version = requestVersion.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = runCatching { GuideNhRuntimeBridgeService.get().previewSearch("items", prefix, 80, mapOf("source" to "picker")) }
                .getOrDefault(emptyList())
                .mapNotNull { json -> json.get("id")?.asString?.let { Entry(it, json.get("label")?.asString, json.get("detail")?.asString) } }
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed || version != requestVersion.get()) return@invokeLater
                entries.clear()
                gridIcons.clear()
                found.forEach(entries::addElement)
                if (!entries.isEmpty) resultList.selectedIndex = 0 else clearPreview()
                loadGridIcons(found, version)
            }
        }
    }

    private fun loadGridIcons(found: List<Entry>, version: Int) {
        found.take(MAX_GRID_PREVIEWS).forEach { entry ->
            ApplicationManager.getApplication().executeOnPooledThread {
                val icon = runCatching {
                    GuideNhRuntimeBridgeService.get().previewResolve(
                        "items", entry.id, count = 1, renderVariant = "picker-grid", filters = mapOf("source" to "picker")
                    )?.stringValue("iconPngBase64")?.let(::decodeGridIcon)
                }.getOrNull() ?: return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed || version != requestVersion.get()) return@invokeLater
                    gridIcons[entry.id] = icon
                    resultList.repaint()
                }
            }
        }
    }

    private fun loadPreview(entry: Entry?) {
        if (entry == null) return clearPreview()
        val version = requestVersion.incrementAndGet()
        previewName.text = entry.label ?: entry.id
        previewDetail.text = entry.detail ?: entry.id
        previewTooltip.text = MyMessageBundle.message("picker.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            val preview = runCatching {
                val previewId = entry.id + meta.text.trim().toIntOrNull()?.let { ":$it" }.orEmpty()
                GuideNhRuntimeBridgeService.get().previewResolve(
                    "items", previewId, count = count.text.trim().toIntOrNull()?.coerceIn(1, 64) ?: 1,
                    nbt = nbt.text.trim().takeIf(String::isNotEmpty), renderVariant = renderVariant.selectedItem as String,
                    filters = mapOf("source" to "picker")
                )
            }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed || version != requestVersion.get() || resultList.selectedValue?.id != entry.id) return@invokeLater
                previewName.text = preview?.stringValue("displayName") ?: entry.label ?: entry.id
                previewDetail.text = preview?.stringValue("detail") ?: entry.detail ?: entry.id
                previewIcon.icon = preview?.stringValue("iconPngBase64")?.let(::decodeIcon)
                previewIcon.text = if (previewIcon.icon == null) MyMessageBundle.message("picker.no.image") else ""
                previewTooltip.text = buildString {
                    preview?.get("tooltipLines")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { line ->
                        line.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let {
                            append(GuideNhMinecraftText.strip(it.asString)).append('\n')
                        }
                    }
                    preview?.intValue("meta")?.let { append(MyMessageBundle.message("documentation.meta", it)).append('\n') }
                    preview?.intValue("count")?.let { append(MyMessageBundle.message("documentation.count", it)).append('\n') }
                    preview?.stringValue("nbt")?.let { append("NBT: ").append(it) }
                }.trim().ifBlank { MyMessageBundle.message("picker.no.tooltip") }
            }
        }
    }

    private fun refreshSelectedPreview() {
        val selected = resultList.selectedValue ?: return
        loadPreview(selected)
    }

    private fun clearPreview() {
        previewIcon.icon = null
        previewIcon.text = MyMessageBundle.message("picker.no.selection")
        previewName.text = ""
        previewDetail.text = ""
        previewTooltip.text = MyMessageBundle.message("picker.search.hint")
    }

    private fun decodeIcon(value: String): ImageIcon? = runCatching {
        val image: Image = ImageIcon(Base64.getDecoder().decode(value)).image
        ImageIcon(image.getScaledInstance(64, 64, Image.SCALE_SMOOTH))
    }.getOrNull()

    private fun decodeGridIcon(value: String): ImageIcon? = runCatching {
        val image: Image = ImageIcon(Base64.getDecoder().decode(value)).image
        ImageIcon(image.getScaledInstance(32, 32, Image.SCALE_SMOOTH))
    }.getOrNull()

    private fun com.google.gson.JsonObject.stringValue(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun com.google.gson.JsonObject.intValue(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private data class ParsedItemStack(val id: String, val meta: Int?, val nbt: String?)

    private fun parseItemStack(value: String): ParsedItemStack {
        val match = Regex("""^([^:]+:[^:]+?)(?::(-?\d+))?(?::(\{.*\}))?$""").matchEntire(value.trim())
        return if (match == null) ParsedItemStack(value.trim().ifBlank { "minecraft:" }, null, null)
        else ParsedItemStack(match.groupValues[1], match.groupValues[2].toIntOrNull(), match.groupValues[3].takeIf(String::isNotBlank))
    }
}

private const val MAX_GRID_PREVIEWS = 32

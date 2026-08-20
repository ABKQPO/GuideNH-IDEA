package com.hfstudio.guidenh

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.hfstudio.MyMessageBundle
import javax.swing.JComponent
import javax.swing.JPanel

class GuideNhSettingsConfigurable : Configurable {
    private val host = JBTextField()
    private val port = JBTextField()
    private val token = JBPasswordField()
    private val allowRemote = JBCheckBox(MyMessageBundle.message("settings.remote"))
    private val autoConnect = JBCheckBox(MyMessageBundle.message("settings.autostart"))
    private val sourcePath = JBTextField()
    private val resourcePackPath = JBTextField()
    private val locale = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "GuideNH"
    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("settings.host"), host)
            .addLabeledComponent(MyMessageBundle.message("settings.port"), port)
            .addLabeledComponent(MyMessageBundle.message("settings.token"), token)
            .addComponent(allowRemote)
            .addComponent(autoConnect)
            .addLabeledComponent(MyMessageBundle.message("settings.source"), sourcePath)
            .addLabeledComponent(MyMessageBundle.message("settings.resourcepack"), resourcePackPath)
            .addLabeledComponent(MyMessageBundle.message("settings.locale"), locale)
            .panel.also { panel = it }
    }
    override fun isModified(): Boolean {
        val state = GuideNhSettings.get().state
        return host.text != state.host.orEmpty() || port.text.toIntOrNull() != state.port || String(token.password) != state.token.orEmpty() ||
            allowRemote.isSelected != state.allowRemote || autoConnect.isSelected != state.autoConnectOnStartup || sourcePath.text != state.sourcePath.orEmpty() || resourcePackPath.text != state.resourcePackPath.orEmpty() || locale.text != state.locale.orEmpty()
    }
    override fun apply() {
        val parsedPort = port.text.toIntOrNull()
        if (parsedPort !in 1..65535) throw com.intellij.openapi.options.ConfigurationException(MyMessageBundle.message("settings.port.invalid"))
        val state = GuideNhSettings.get().state
        state.host = host.text.trim(); state.port = parsedPort!!; state.token = String(token.password)
        state.allowRemote = allowRemote.isSelected; state.autoConnectOnStartup = autoConnect.isSelected; state.sourcePath = sourcePath.text.trim(); state.resourcePackPath = resourcePackPath.text.trim(); state.locale = locale.text.trim()
    }
    override fun reset() {
        val state = GuideNhSettings.get().state
        host.text = state.host.orEmpty(); port.text = state.port.toString(); token.text = state.token.orEmpty(); allowRemote.isSelected = state.allowRemote; autoConnect.isSelected = state.autoConnectOnStartup
        sourcePath.text = state.sourcePath.orEmpty(); resourcePackPath.text = state.resourcePackPath.orEmpty(); locale.text = state.locale.orEmpty()
    }
    override fun disposeUIResources() { panel = null }
}

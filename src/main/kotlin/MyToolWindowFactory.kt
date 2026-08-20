package com.hfstudio

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import javax.swing.JButton
import javax.swing.BoxLayout
import com.hfstudio.guidenh.GuideNhRuntimeBridgeService

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow()
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow {
        private val content = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val label = JBLabel()
            fun refresh() { label.text = MyMessageBundle.message("toolwindow.runtime", GuideNhRuntimeBridgeService.get().status.state) }
            refresh()
            add(label)
            add(JButton(MyMessageBundle.message("toolwindow.refresh")).apply { addActionListener { refresh() } })
            add(JButton(MyMessageBundle.message("toolwindow.disconnect")).apply { addActionListener { GuideNhRuntimeBridgeService.get().disconnect(); refresh() } })
            add(JBLabel(MyMessageBundle.message("toolwindow.protocol")))
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}

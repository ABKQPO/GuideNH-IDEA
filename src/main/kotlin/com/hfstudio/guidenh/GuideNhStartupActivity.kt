package com.hfstudio.guidenh

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class GuideNhStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val state = GuideNhSettings.get().state
        if (!state.autoConnectOnStartup || state.token.orEmpty().isBlank()) return
        runCatching { GuideNhRuntimeBridgeService.get().connect(state.host.orEmpty(), state.port, state.token.orEmpty(), state.allowRemote) }
    }
}

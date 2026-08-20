package com.hfstudio.guidenh

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.BaseState

@Service(Service.Level.APP)
@State(name = "GuideNhSettings", storages = [Storage("guidenh.xml")])
class GuideNhSettings : SimplePersistentStateComponent<GuideNhSettings.State>(State()) {
    class State : BaseState() {
        var host by string("127.0.0.1")
        var port by property(25575)
        var token by string("")
        var allowRemote by property(false)
        var autoConnectOnStartup by property(false)
        var sourcePath by string("")
        var resourcePackPath by string("")
        var locale by string("")
    }

    companion object {
        fun get(): GuideNhSettings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(GuideNhSettings::class.java)
    }
}

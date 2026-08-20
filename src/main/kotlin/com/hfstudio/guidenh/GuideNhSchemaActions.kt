package com.hfstudio.guidenh

import com.google.gson.Gson
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.hfstudio.MyMessageBundle
import java.nio.file.Files
import java.nio.file.Path

/** Generates a project-local additive schema overlay from GuideNH Java compiler source. */
class GenerateSchemaAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val configured = GuideNhSettings.get().state.sourcePath.orEmpty().trim()
        if (configured.isBlank()) {
            Messages.showErrorDialog(project, MyMessageBundle.message("schema.source.required"), MyMessageBundle.message("schema.title"))
            return
        }
        val source = Path.of(configured)
        val javaSource = locateJavaSource(source)
        if (javaSource == null) {
            Messages.showErrorDialog(project, MyMessageBundle.message("schema.source.missing", source), MyMessageBundle.message("schema.title"))
            return
        }
        val target = project.basePath?.let { Path.of(it, ".idea", "guidenh", "schema", "generated-tags.json") }
        if (target == null) {
            Messages.showErrorDialog(project, MyMessageBundle.message("schema.project.missing"), MyMessageBundle.message("schema.title"))
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, MyMessageBundle.message("schema.task"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = MyMessageBundle.message("schema.scanning")
                runCatching {
                    val generated = GuideNhJavaSchemaExtractor.extract(javaSource)
                    val tagCount = generated.getAsJsonObject("tags")?.size() ?: 0
                    check(tagCount > 0) { MyMessageBundle.message("schema.empty", javaSource) }
                    Files.createDirectories(target.parent)
                    Files.writeString(target, Gson().newBuilder().setPrettyPrinting().create().toJson(generated))
                    tagCount
                }.onSuccess { tagCount ->
                    ApplicationManager.getApplication().invokeLater {
                        GuideNhSchemaService.get(project).reload()
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                        Messages.showInfoMessage(project, MyMessageBundle.message("schema.generated", tagCount, javaSource), MyMessageBundle.message("schema.title"))
                    }
                }.onFailure { error ->
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, error.message ?: MyMessageBundle.message("schema.failed"), MyMessageBundle.message("schema.title"))
                    }
                }
            }
        })
    }

    private fun locateJavaSource(source: Path): Path? = listOf(
        source.resolve("src/main/java"), source.resolve("src/java"), source,
    ).firstOrNull(Files::isDirectory)
}

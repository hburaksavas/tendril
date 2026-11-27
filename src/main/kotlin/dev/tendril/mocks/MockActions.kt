package dev.tendril.mocks

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.tendril.calls.FindServiceCallsAction
import dev.tendril.recordings.Recording

/**
 * Right-click | Mock Response inside a Feign client method or a controller handler: a mock file with a sample body
 * from the return type, opened for editing. While the application runs with the recorder, the mock answers
 * instead of the service (Feign) or the controller (endpoint). When the method has mocks already, they are offered
 * first.
 */
class MockResponseAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = e.project?.basePath != null && editor != null && file != null &&
            FindServiceCallsAction.serviceMethodAt(file, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val method = FindServiceCallsAction.serviceMethodAt(file, editor.caretModel.offset) ?: return
        val definition = MockFactory.forMethod(project, method)
        if (definition == null) {
            Messages.showInfoMessage(project, "This method is not a Feign client method or a mapped controller method.", "Mock Response")
            return
        }
        val store = MockStore.getInstance(project)
        val existing = store.entries.filter { it.definition?.let { d -> sameTarget(d, definition) } == true }
        if (existing.isEmpty()) {
            store.create(definition)
            return
        }
        val newMock = "New mock…"
        val choices = listOf(newMock) + existing.map { it.name }
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<String>("Mocks of ${definition.label}", choices) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                if (selectedValue == newMock) store.create(definition)
                else existing.firstOrNull { it.name == selectedValue }?.let(store::open)
            }
        }).showInBestPositionFor(editor)
    }

    private fun sameTarget(a: MockDefinition, b: MockDefinition) =
        a.kind == b.kind && if (a.isFeign) a.target == b.target else a.label == b.label || a.path == b.path

    companion object {
        /** "Mock This Response" in the Recordings window: a mock answering like the recorded call did. */
        fun fromRecording(project: Project, r: Recording) {
            val definition = MockFactory.fromRecording(r)
            if (definition == null) {
                Messages.showInfoMessage(
                    project, "Only HTTP requests and Feign calls with a recorded text response can be mocked.", "Mock This Response",
                )
                return
            }
            MockStore.getInstance(project).create(definition)
        }
    }
}

package dev.tendril.http

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/** Sends the request that contains [line] of [text]; the response shows in the endpoints window. */
internal fun runRequestAt(project: Project, text: String, line: Int) {
    HttpResponseToolWindowFactory.run(project, HttpRequestText.block(text, line), text)
}

/** A run arrow in the gutter of every request line of a `.http` file. */
class HttpLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = "Tendril: send HTTP request"

    override fun getIcon() = AllIcons.Actions.Execute

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val type = element.node?.elementType
        if (type != HttpTokens.METHOD && type != HttpTokens.URL && type != HttpTokens.URL_VARIABLE) return null

        // only the first token of the request line carries the icon
        var previous = element.prevSibling
        while (previous is PsiWhiteSpace && !previous.text.contains('\n')) previous = previous.prevSibling
        if (previous != null && previous !is PsiWhiteSpace) return null

        val handler = GutterIconNavigationHandler<PsiElement> { _, e ->
            val file = e.containingFile ?: return@GutterIconNavigationHandler
            val document = PsiDocumentManager.getInstance(e.project).getDocument(file) ?: return@GutterIconNavigationHandler
            runRequestAt(e.project, document.text, document.getLineNumber(e.textOffset))
        }
        return LineMarkerInfo(
            element, element.textRange, AllIcons.Actions.Execute,
            { "Send request" }, handler, GutterIconRenderer.Alignment.LEFT, { "Send request" },
        )
    }
}

/** Ctrl+Enter in a `.http` file: sends the request under the caret. Does nothing (and stays out of the way) elsewhere. */
class SendHttpRequestAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null &&
            file != null && file.fileType == HttpFileType
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        runRequestAt(project, document.text, document.getLineNumber(editor.caretModel.offset))
    }
}

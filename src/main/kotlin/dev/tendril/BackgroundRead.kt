package dev.tendril

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * A plain navigation target for [element], resolved off the EDT (PSI `canNavigate()` / `navigate()` touch indexes
 * and are slow operations on the EDT). Open the result with `navigate(true)` on the EDT. Needs a read action.
 */
fun descriptorOf(element: PsiElement?): Navigatable? =
    element?.let { PsiNavigationSupport.getInstance().getDescriptor(it) }

/**
 * Runs [compute] in a cancellable read action on a pooled thread (waits for indexing) and hands the result to
 * [onUi] on the EDT, so PSI work never blocks the UI. Cancelled automatically when [owner] is disposed or a write
 * action comes in (the read action restarts).
 */
fun <T> readInBackground(project: Project, owner: Disposable, compute: () -> T, onUi: (T) -> Unit) {
    ReadAction.nonBlocking<T> { compute() }
        .inSmartMode(project)
        .expireWith(owner)
        .finishOnUiThread(ModalityState.any(), onUi)
        .submit(AppExecutorUtil.getAppExecutorService())
}

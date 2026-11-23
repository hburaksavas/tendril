package dev.tendril.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI

/** Real editors for requests and payloads: the IDE's font and colours, selection, search and syntax highlighting. */
internal object Editors {

    /** JSON when the IDE has JSON support, plain text otherwise. */
    fun jsonType(): FileType {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("json")
        return if (type.name == "UNKNOWN" || type.name == "ObjectFileType") PlainTextFileType.INSTANCE else type
    }

    /** JSON for bodies that look like JSON, plain text for everything else. */
    fun typeOf(body: String): FileType {
        val t = body.trimStart()
        return if (t.startsWith("{") || t.startsWith("[")) jsonType() else PlainTextFileType.INSTANCE
    }

    fun viewer(project: Project): EditorTextField = create(project, PlainTextFileType.INSTANCE, viewer = true)

    fun editable(project: Project, type: FileType): EditorTextField = create(project, type, viewer = false)

    /** Replaces the content and the highlighting of [field]. */
    fun show(field: EditorTextField, text: String, type: FileType) {
        field.setNewDocumentAndFileType(type, EditorFactory.getInstance().createDocument(text))
    }

    private fun create(project: Project, type: FileType, viewer: Boolean): EditorTextField {
        val field = EditorTextField(EditorFactory.getInstance().createDocument(""), project, type, viewer, false)
        field.setFontInheritedFromLAF(false)
        field.border = JBUI.Borders.emptyLeft(6)
        field.addSettingsProvider { editor ->
            editor.setBorder(null)
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isLineMarkerAreaShown = false
                isIndentGuidesShown = false
                additionalLinesCount = 0
                isAdditionalPageAtBottom = false
            }
        }
        return field
    }
}

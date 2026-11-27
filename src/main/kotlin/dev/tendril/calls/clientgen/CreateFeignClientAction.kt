package dev.tendril.calls.clientgen

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tendril.calls.CallMatching
import dev.tendril.calls.FindServiceCallsAction
import dev.tendril.calls.ServiceMapStore
import dev.tendril.config.TendrilSettings
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Right-click | Create Feign Client Method inside a controller handler of the backend: the matching Feign method,
 * with copies of the request and response classes, written into an open project (the BFF), in an existing client
 * of this service or a new one.
 */
class CreateFeignClientAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val method = if (editor != null && file != null) FindServiceCallsAction.serviceMethodAt(file, editor.caretModel.offset) else null
        e.presentation.isEnabledAndVisible = e.project != null && method?.containingClass?.isInterface == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val method = FindServiceCallsAction.serviceMethodAt(file, editor.caretModel.offset) ?: return
        val extractor = ClientExtractor(project)
        val endpoint = extractor.endpoint(method) ?: return
        val targets = targets(project)
        val dialog = CreateClientDialog(project, endpoint, extractor.dtoSpecs, targets)
        if (!dialog.showAndGet()) return
        val plan = dialog.plan() ?: return
        ClientTargets.write(plan).onSuccess {
            val notes = plan.notes.takeIf { it.isNotEmpty() }?.joinToString("<br>", "<br>")
            NotificationGroupManager.getInstance().getNotificationGroup("Tendril")
                ?.createNotification("${plan.summary} in ${plan.info.project.name}.${notes.orEmpty()}", NotificationType.INFORMATION)
                ?.notify(plan.info.project)
        }.onFailure { Messages.showErrorDialog(project, it.message ?: it.javaClass.simpleName, "Create Feign Client Method") }
    }

    /** Open projects to write to: the linked ones first, then the others, this one last (a client inside one repository). */
    private fun targets(project: Project): List<Project> {
        val linked = TendrilSettings.getInstance(project).state.linkedProjects.map(ServiceMapStore::normalise).toSet()
        return ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
            .sortedWith(compareBy({ it == project }, { it.basePath?.let(ServiceMapStore::normalise) !in linked }, { it.name }))
    }
}

internal class CreateClientDialog(
    private val source: Project,
    private val endpoint: EndpointSpec,
    private val dtos: List<DtoSpec>,
    targets: List<Project>,
) : DialogWrapper(source) {
    private sealed interface ClientItem {
        data class Existing(val client: TargetClient) : ClientItem
        data object New : ClientItem
    }

    private val target = ComboBox(targets.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { p ->
            if (p == source) "${p.name} (this project)" else p.name
        }
    }
    private val client = ComboBox<ClientItem>().apply {
        renderer = SimpleListCellRenderer.create("") { item ->
            when (item) {
                is ClientItem.Existing -> item.client.name + if (item.client.callsService) "   calls ${endpoint.service}" else ""
                ClientItem.New, null -> "New client…"
            }
        }
    }
    private val clientName = JBTextField(defaultClientName())
    private val packageName = JBTextField()
    private val dtoPackage = JBTextField()
    private val methodName = JBTextField(endpoint.methodName)
    private val withUrl = JBCheckBox("Give a url property with a local default (\${${endpoint.service}.url:http://localhost:${endpoint.port}})")
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val warning = JBLabel().apply { foreground = com.intellij.ui.JBColor.ORANGE }
    private var info: TargetInfo? = null
    private var dtoPackageTouched = false

    init {
        title = "Create Feign Client Method for ${endpoint.httpMethod} ${CallMatching.join(endpoint.contextPath, endpoint.path)}"
        setOKButtonText("Create")
        target.addActionListener { analyze() }
        client.addActionListener { refresh() }
        val listener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        }
        listOf(clientName, methodName, dtoPackage).forEach { it.document.addDocumentListener(listener) }
        packageName.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!dtoPackageTouched) dtoPackage.text = packageName.text.trim().let { if (it.isEmpty()) "dto" else "$it.dto" }
                refresh()
            }
        })
        dtoPackage.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyTyped(e: java.awt.event.KeyEvent) {
                dtoPackageTouched = true
            }
        })
        withUrl.addItemListener { refresh() }
        init()
        analyze()
    }

    private fun defaultClientName(): String =
        endpoint.service.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Backend" }
            .let { if (it.endsWith("Client")) it else "${it}Client" }

    override fun createCenterPanel(): JComponent = panel {
        row("Project:") { cell(target).align(Align.FILL) }
        row("Client:") { cell(client).align(Align.FILL) }
        row("New client name:") { cell(clientName).align(Align.FILL) }
        row("Package:") { cell(packageName).align(Align.FILL) }
        row("") { cell(withUrl) }
        row("Method name:") { cell(methodName).align(Align.FILL) }
        row("DTO package:") { cell(dtoPackage).align(Align.FILL) }
        row { cell(warning) }
        row { cell(summary).align(Align.FILL) }
    }.apply { preferredSize = JBUI.size(640, preferredSize.height) }

    private fun selectedTarget(): Project? = target.selectedItem as? Project

    private fun analyze() {
        val project = selectedTarget() ?: return
        if (DumbService.isDumb(project)) {
            info = null
            warning.text = "${project.name} is indexing: try again in a moment"
            return
        }
        val analyzed = ClientTargets.analyze(project, endpoint)
        info = analyzed
        client.removeAllItems()
        analyzed.clients.forEach { client.addItem(ClientItem.Existing(it)) }
        client.addItem(ClientItem.New)
        client.selectedIndex = if (analyzed.clients.firstOrNull()?.callsService == true) 0 else analyzed.clients.size
        packageName.text = analyzed.defaultPackage
        withUrl.isSelected = analyzed.usesUrl
        refresh()
    }

    private fun existing(): TargetClient? = (client.selectedItem as? ClientItem.Existing)?.client

    private fun refresh() {
        val isNew = existing() == null
        clientName.isEnabled = isNew
        packageName.isEnabled = isNew
        withUrl.isEnabled = isNew
        warning.text = existing()?.existingMethod?.let { "${existing()!!.name}.$it calls this endpoint already" }.orEmpty()
        summary.text = runCatching { plan()?.let { "<html>${it.summary}</html>" } }.getOrNull().orEmpty()
    }

    fun choice(): ClientTargets.Choice? {
        val selected = existing()
        val pkg = (selected?.packageName ?: packageName.text.trim())
        return ClientTargets.Choice(
            client = selected, newClientName = clientName.text.trim(), packageName = pkg,
            dtoPackage = dtoPackage.text.trim().ifEmpty { if (pkg.isEmpty()) "dto" else "$pkg.dto" },
            methodName = methodName.text.trim(), withUrl = withUrl.isSelected,
        )
    }

    fun plan(): ClientTargets.Plan? {
        val i = info ?: return null
        val c = choice() ?: return null
        return ClientTargets.plan(i, endpoint, dtos, c)
    }

    override fun doValidate(): ValidationInfo? {
        val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val pkg = Regex("([A-Za-z_][A-Za-z0-9_]*)(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        if (info == null) return ValidationInfo("Choose a project that has finished indexing", target)
        if (!identifier.matches(methodName.text.trim())) return ValidationInfo("Not a method name", methodName)
        if (existing() == null && !identifier.matches(clientName.text.trim())) return ValidationInfo("Not a class name", clientName)
        if (existing() == null && packageName.text.isNotBlank() && !pkg.matches(packageName.text.trim())) return ValidationInfo("Not a package name", packageName)
        if (dtos.isNotEmpty() && !pkg.matches(dtoPackage.text.trim())) return ValidationInfo("Not a package name", dtoPackage)
        existing()?.pointer?.element?.findMethodsByName(methodName.text.trim(), false)?.takeIf { it.isNotEmpty() }?.let {
            return ValidationInfo("${existing()!!.name} has a method ${methodName.text.trim()} already", methodName)
        }
        return null
    }
}

package dev.tendril.startup

import com.intellij.icons.AllIcons
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.tendril.beans.BeanModels
import dev.tendril.beans.SpringNames
import dev.tendril.beans.addAnnotation
import dev.tendril.config.ConfigMetadata
import dev.tendril.config.ConfigNavigation
import dev.tendril.config.ConfigProfiles
import dev.tendril.config.MetadataIndex
import dev.tendril.config.SpringFiles
import dev.tendril.descriptorOf
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.swing.Icon

/**
 * One entry of the popup a console link opens: a place to go to, or a fix ([fix] = true) that changes the project.
 * An entry with [children] opens them as a submenu instead of performing anything.
 */
class StartupChoice(
    val text: String,
    val icon: Icon?,
    val fix: Boolean = false,
    val children: List<StartupChoice> = emptyList(),
    val perform: (Project) -> Unit = {},
)

/**
 * Turns a [StartupTarget] into what the user can do with it, against the project's code and configuration.
 * [scope] is the run configuration's scope, which narrows config files to the application that failed.
 */
class StartupChoices(private val project: Project, private val scope: GlobalSearchScope) {

    private val fileIndex = ProjectFileIndex.getInstance(project)

    /** Call in a read action in smart mode. [listener] is what holds the port, for a [StartupTarget.Port]. */
    fun of(target: StartupTarget, listener: PortListener? = null): List<StartupChoice> = when (target) {
        is StartupTarget.Type -> type(target)
        is StartupTarget.InjectionPoint -> injectionPoint(target)
        is StartupTarget.BeanDefinition -> beanDefinition(target)
        is StartupTarget.ConfigKey -> configKey(target.key)
        is StartupTarget.Origin -> origin(target)
        is StartupTarget.Port -> port(target.port, listener)
    }

    // ---- code ----

    private fun type(target: StartupTarget.Type): List<StartupChoice> {
        val cls = findClass(target.fqn) ?: return emptyList()
        val choices = mutableListOf(goTo("Go to ${cls.name}", cls))
        if (!target.missingBean || !inSource(cls)) return choices

        val concrete = !cls.isInterface && !cls.hasModifierProperty(PsiModifier.ABSTRACT)
        if (concrete) {
            componentFix(cls)?.let { choices += it }
        } else {
            val model = BeanModels.get(project)
            val implementations = ClassInheritorsSearch.search(cls, GlobalSearchScope.projectScope(project), true).findAll()
                .filter { !it.isInterface && !it.hasModifierProperty(PsiModifier.ABSTRACT) }
                .sortedBy { it.qualifiedName }
                .take(MAX_CHOICES)
            for (impl in implementations) {
                choices += goTo("Go to implementation ${impl.name}", impl)
                if (model.candidates(impl).isEmpty()) componentFix(impl)?.let { choices += it }
            }
        }
        return choices
    }

    private fun componentFix(cls: PsiClass): StartupChoice? {
        if (!isJava(cls) || cls.isEnum || cls.isAnnotationType) return null
        val pointer = SmartPointerManager.createPointer(cls)
        return StartupChoice("Annotate '${cls.name}' with @Component", AllIcons.Actions.IntentionBulb, fix = true) {
            pointer.element?.let { c -> addAnnotation(project, c, SpringNames.COMPONENT) }
        }
    }

    private fun injectionPoint(target: StartupTarget.InjectionPoint): List<StartupChoice> {
        val cls = findClass(target.fqn) ?: return emptyList()
        val element: PsiElement = when (val m = target.member) {
            is Member.Field -> cls.findFieldByName(m.name, false)
            is Member.Method -> cls.findMethodsByName(m.name, false).firstOrNull()
            is Member.MethodParameter -> cls.findMethodsByName(m.method, false)
                .firstOrNull { it.parameterList.parametersCount > m.index }?.parameterList?.getParameter(m.index)
            is Member.ConstructorParameter -> injectionConstructor(cls, m.index)?.parameterList?.getParameter(m.index)
            Member.None -> null
        } ?: cls
        val label = (element as? PsiNamedElement)?.name ?: cls.name
        return listOf(goTo("Go to $label in ${cls.name}", element))
    }

    /** The constructor Spring uses: the `@Autowired` one, else the only one with enough parameters. */
    private fun injectionConstructor(cls: PsiClass, index: Int): PsiMethod? {
        val fitting = cls.constructors.filter { it.parameterList.parametersCount > index }
        return fitting.firstOrNull { it.hasAnnotation(SpringNames.AUTOWIRED) } ?: fitting.singleOrNull() ?: fitting.firstOrNull()
    }

    private fun beanDefinition(target: StartupTarget.BeanDefinition): List<StartupChoice> {
        val cls = target.classes.firstNotNullOfOrNull { findClass(it) }
        val element: PsiModifierListOwner = when {
            cls != null && target.method != null -> cls.findMethodsByName(target.method, false).firstOrNull() ?: cls
            cls != null -> cls
            // "a programmatically registered singleton" or an unknown location: look the name up in the bean model
            else -> BeanModels.get(project).beans.firstOrNull { it.name == target.name }?.element as? PsiModifierListOwner
        } ?: return emptyList()

        val choices = mutableListOf(goTo("Go to bean '${target.name}'", element))
        if (isJava(element) && inSource(element) && !element.hasAnnotation(SpringNames.PRIMARY)) {
            val pointer = SmartPointerManager.createPointer(element)
            choices += StartupChoice("Mark '${target.name}' as @Primary", AllIcons.Actions.IntentionBulb, fix = true) {
                pointer.element?.let { e -> addAnnotation(project, e, SpringNames.PRIMARY) }
            }
        }
        return choices
    }

    // ---- configuration ----

    private fun configKey(key: String): List<StartupChoice> {
        val choices = mutableListOf<StartupChoice>()
        val entries = configEntries(key)
        for (entry in entries.take(MAX_CHOICES)) choices += goTo("Go to $key in ${where(entry)}", entry)

        val readers = ConfigMetadata.ownScan(project).byKey[MetadataIndex.normalize(key)].orEmpty().distinct()
        for (reader in readers.take(MAX_CHOICES)) choices += goTo("Go to ${describe(reader)}, which reads it", reader)

        if (entries.isEmpty()) {
            for (file in mainConfigFiles()) {
                choices += StartupChoice("Add '$key' to ${label(file)}", AllIcons.Actions.IntentionBulb, fix = true) {
                    ConfigWriter.add(it, file, key, "")
                }
            }
        }
        return choices
    }

    private fun origin(target: StartupTarget.Origin): List<StartupChoice> {
        val file = originFile(target.resource) ?: return emptyList()
        return listOf(StartupChoice("Go to ${label(file)}:${target.line}", file.fileType.icon) {
            OpenFileDescriptor(it, file, (target.line - 1).coerceAtLeast(0), (target.column - 1).coerceAtLeast(0)).navigate(true)
        })
    }

    /** The project's source copy of a resource named in an `Origin:` line (not the copy in the build output). */
    internal fun originFile(resource: String): VirtualFile? {
        val path = resource.removePrefix("file:").replace('\\', '/').let { if (Regex("^/[A-Za-z]:/").containsMatchIn(it)) it.drop(1) else it }
        val name = path.substringAfterLast('/')
        val inProject = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
            .filter { fileIndex.isInContent(it) }
        // "application.yml", or "/x/target/classes/config/application.yml": match the longest tail of the path
        val tails = path.split('/').filter { it.isNotEmpty() }.let { s -> s.indices.map { s.drop(it).joinToString("/") } }
        for (tail in tails) {
            val matching = inProject.filter { it.path.endsWith("/$tail") || it.path == tail }
            if (matching.isNotEmpty()) return matching.firstOrNull { scope.contains(it) && !fileIndex.isInTestSourceContent(it) } ?: matching.first()
        }
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    private fun port(port: Int, listener: PortListener?): List<StartupChoice> {
        val choices = mutableListOf<StartupChoice>()
        listener?.runningIn?.let { run ->
            choices += StartupChoice("Stop '${run.name}', which is listening on $port", AllIcons.Actions.Suspend, fix = true) { run.stop() }
        }
        val free = freePort(port)
        val entries = configEntries("server.port")
        if (free != null) {
            if (entries.isNotEmpty()) {
                for (entry in entries.take(MAX_CHOICES)) {
                    val pointer = SmartPointerManager.createPointer(entry)
                    choices += StartupChoice("Set server.port to $free in ${where(entry)}", AllIcons.Actions.IntentionBulb, fix = true) {
                        pointer.element?.let { e -> ConfigWriter.set(it, e, free.toString()) }
                    }
                }
            } else {
                for (file in mainConfigFiles()) {
                    choices += StartupChoice("Add server.port=$free to ${label(file)}", AllIcons.Actions.IntentionBulb, fix = true) {
                        ConfigWriter.add(it, file, "server.port", free.toString())
                    }
                }
            }
        }
        for (entry in entries.take(MAX_CHOICES)) choices += goTo("Go to server.port in ${where(entry)}", entry)
        if (listener != null && listener.runningIn == null) {
            choices += StartupChoice("Port $port is used by ${listener.description}", AllIcons.General.Information) {}
        }
        return choices
    }

    /** Config file entries for [key] in the failed application's files, all of them if none is in its scope. */
    internal fun configEntries(key: String): List<PsiElement> {
        val all = ConfigNavigation.allConfigElements(project, key)
        val inScope = all.filter { e -> e.containingFile?.virtualFile?.let { scope.contains(it) } == true }
        return (inScope.ifEmpty { all }).filter { e -> e.containingFile?.virtualFile?.let { !fileIndex.isInTestSourceContent(it) } != false }
    }

    /** `application.properties` / `.yml` (not profile files) of the main sources, those of the failed application first. */
    internal fun mainConfigFiles(): List<VirtualFile> {
        val main = SpringFiles.appConfigFiles(project)
            .filter { MAIN_CONFIG.matches(it.name) && !fileIndex.isInTestSourceContent(it) }
        return main.filter { scope.contains(it) }.ifEmpty { main }.sortedBy { it.path }.take(MAX_CHOICES)
    }

    internal fun where(entry: PsiElement): String {
        val file = entry.containingFile?.virtualFile ?: return "configuration"
        val profile = ConfigProfiles.profileOf(entry)?.takeIf { !file.name.contains("-$it.") }
        return label(file) + (profile?.let { " (profile $it)" } ?: "")
    }

    internal fun label(file: VirtualFile): String {
        val modules = SpringFiles.appConfigFiles(project).mapNotNull { fileIndex.getModuleForFile(it) }.distinct()
        val module = fileIndex.getModuleForFile(file)
        return if (modules.size > 1 && module != null) "${file.name} of ${module.name}" else file.name
    }

    // ---- helpers ----

    private fun findClass(fqn: String): PsiClass? {
        val found = JavaPsiFacade.getInstance(project).findClasses(fqn, GlobalSearchScope.allScope(project))
        return found.firstOrNull { inSource(it) } ?: found.firstOrNull()
    }

    internal fun inSource(element: PsiElement): Boolean {
        val file = element.navigationElement.containingFile?.virtualFile ?: return false
        return fileIndex.isInSourceContent(file)
    }

    private fun isJava(element: PsiElement) = element.navigationElement.language == JavaLanguage.INSTANCE

    private fun describe(element: PsiElement): String = when (element) {
        is PsiField -> "${element.containingClass?.name}.${element.name}"
        is PsiParameter -> "parameter ${element.name} of ${PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.name}"
        is PsiMethod -> "${element.containingClass?.name}.${element.name}()"
        is PsiClass -> element.name ?: "class"
        is PsiNamedElement -> element.name ?: "code"
        else -> "code"
    }

    internal fun goTo(text: String, element: PsiElement): StartupChoice {
        val target = element.navigationElement
        val navigatable = descriptorOf(target)
        val icon = runCatching { target.getIcon(0) }.getOrNull()
        return StartupChoice(text, icon) { navigatable?.navigate(true) }
    }

    companion object {
        private const val MAX_CHOICES = 5
        private val MAIN_CONFIG = Regex("application\\.(properties|ya?ml)")

        /** The first port after [busy] that nothing listens on, or null. */
        fun freePort(busy: Int): Int? = (busy + 1..minOf(busy + 100, 65535)).firstOrNull { p ->
            runCatching { ServerSocket().use { s -> s.reuseAddress = false; s.bind(InetSocketAddress(p)) }; true }.getOrDefault(false)
        }
    }
}

/** Writes to application.properties / .yml and puts the caret on the value. */
object ConfigWriter {

    fun set(project: Project, entry: PsiElement, value: String) {
        val file = entry.containingFile ?: return
        WriteCommandAction.runWriteCommandAction(project, "Set Configuration Value", null, {
            when (entry) {
                is YAMLKeyValue -> YAMLElementGenerator.getInstance(project).createYamlKeyValue("k", value).value?.let { entry.setValue(it) }
                is Property -> entry.setValue(value)
            }
        }, file)
        reveal(project, entry)
    }

    /** Adds [key]; in yml as nested [segments] (by default one per dot of the key). */
    fun add(project: Project, vf: VirtualFile, key: String, value: String, segments: List<String> = key.split('.')) {
        val file = PsiManager.getInstance(project).findFile(vf) ?: return
        var added: PsiElement? = null
        WriteCommandAction.runWriteCommandAction(project, "Add Configuration Key", null, {
            added = when (file) {
                is PropertiesFile -> file.addProperty(key, value).psiElement
                is YAMLFile -> YAMLUtil.createI18nRecord(file, segments.toTypedArray(), value.ifEmpty { "\"\"" })
                else -> null
            }
        }, file)
        added?.let { reveal(project, it) }
    }

    /** Opens the file with the caret at the end of the value (inside the quotes of an empty yml value). */
    private fun reveal(project: Project, entry: PsiElement) {
        val vf = entry.containingFile?.virtualFile ?: return
        val end = when (entry) {
            is YAMLKeyValue -> entry.value?.let { v -> if (v.text == "\"\"") v.textRange.startOffset + 1 else v.textRange.endOffset }
                ?: entry.textRange.endOffset
            else -> entry.textRange.endOffset
        }
        OpenFileDescriptor(project, vf, end).navigate(true)
    }
}

package dev.tendril.beans

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

/** Base: visits injected fields and constructor / @Bean method parameters that the [BeanModel] knows about. */
abstract class InjectionPointInspection : LocalInspectionTool() {

    final override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitField(field: PsiField) = check(field)
            override fun visitParameter(parameter: PsiParameter) = check(parameter)

            private fun check(variable: PsiVariable) {
                val model = BeanModels.get(variable.project)
                val point = model.pointByElement[variable] ?: return
                val identifier = variable.nameIdentifier ?: return
                inspect(model, point, identifier, holder)
            }
        }

    protected abstract fun inspect(model: BeanModel, point: InjectionPoint, at: PsiElement, holder: ProblemsHolder)
}

/** `@Autowired` field in a bean class -> constructor injection. */
class FieldInjectionInspection : InjectionPointInspection() {
    override fun inspect(model: BeanModel, point: InjectionPoint, at: PsiElement, holder: ProblemsHolder) {
        if (point.kind != InjectionKind.FIELD || !point.viaAutowiredOrInject) return
        val field = point.element as? PsiField ?: return
        val file = field.containingFile?.virtualFile ?: return
        if (ProjectFileIndex.getInstance(field.project).isInTestSourceContent(file)) return

        val fixes = if (canConvert(field, point)) arrayOf<LocalQuickFix>(ConvertToConstructorInjectionFix()) else emptyArray()
        holder.registerProblem(
            at, "Field injection is not recommended; use constructor injection",
            ProblemHighlightType.WEAK_WARNING, *fixes,
        )
    }

    private fun canConvert(field: PsiField, point: InjectionPoint): Boolean {
        val cls = field.containingClass ?: return false
        if (field.initializer != null || point.optional || point.qualified) return false
        return hasLombokConstructor(cls) || cls.constructors.size <= 1
    }
}

/** Injected type has no bean candidate although it is a plain project class (or an interface with project implementations). */
class NoBeanFoundInspection : InjectionPointInspection() {
    override fun inspect(model: BeanModel, point: InjectionPoint, at: PsiElement, holder: ProblemsHolder) {
        if (point.optional || model.candidates(point.target).isNotEmpty()) return
        val target = point.target
        if (target.isEnum || target.isAnnotationType) return
        val file = target.containingFile?.virtualFile ?: return
        if (!ProjectFileIndex.getInstance(target.project).isInSourceContent(file)) return // may come from auto-configuration

        val concrete = !target.isInterface && !target.hasModifierProperty(PsiModifier.ABSTRACT)
        // An interface without any project implementation is usually proxied (Feign, MyBatis, ...): stay silent.
        val implemented = !concrete &&
            ClassInheritorsSearch.search(target, GlobalSearchScope.projectScope(target.project), true).findFirst() != null
        if (!concrete && !implemented) return

        val fixes = if (concrete) arrayOf<LocalQuickFix>(AnnotateAsComponentFix(target)) else emptyArray()
        holder.registerProblem(
            at, "No Spring bean of type '${target.name}' found in the project",
            ProblemHighlightType.WEAK_WARNING, *fixes,
        )
    }
}

/** Several beans of the injected type and nothing that picks one. */
class AmbiguousBeanInspection : InjectionPointInspection() {
    override fun inspect(model: BeanModel, point: InjectionPoint, at: PsiElement, holder: ProblemsHolder) {
        if (point.qualified) return
        val candidates = model.candidates(point.target)
        if (candidates.size < 2) return
        if (candidates.any { it.primary || it.conditional }) return
        if (candidates.any { it.name == point.name }) return // Spring falls back to matching the bean name

        holder.registerProblem(
            at,
            "Multiple beans of type '${point.target.name}' (${candidates.joinToString { it.name }}); " +
                "use @Primary or @Qualifier",
            ProblemHighlightType.WARNING,
        )
    }
}

/** The injection closes a cycle of eager bean dependencies (rejected by Spring Boot 2.6+ by default). */
class CircularDependencyInspection : InjectionPointInspection() {
    override fun inspect(model: BeanModel, point: InjectionPoint, at: PsiElement, holder: ProblemsHolder) {
        val cycle = model.cycleDescription(point) ?: return
        holder.registerProblem(
            at, "Circular bean dependency: $cycle (add @Lazy to break it)", ProblemHighlightType.WARNING,
        )
    }
}

private fun hasLombokConstructor(cls: PsiClass) =
    cls.getAnnotation(SpringNames.LOMBOK_REQUIRED) != null || cls.getAnnotation(SpringNames.LOMBOK_ALL) != null

private class AnnotateAsComponentFix(target: PsiClass) : LocalQuickFix {
    private val name = target.name ?: "class"
    private val pointer = SmartPointerManager.getInstance(target.project).createSmartPsiElementPointer(target)

    override fun getFamilyName() = "Tendril: register as bean"
    override fun getName() = "Annotate '$name' with @Component"
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor) = IntentionPreviewInfo.EMPTY
    override fun startInWriteAction() = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val cls = pointer.element ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val annotation = cls.modifierList?.addAnnotation(SpringNames.COMPONENT) ?: return@runWriteCommandAction
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(annotation)
        }
    }
}

private class ConvertToConstructorInjectionFix : LocalQuickFix {
    override fun getFamilyName() = "Tendril: constructor injection"
    override fun getName() = "Convert to constructor injection"
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor) = IntentionPreviewInfo.EMPTY
    override fun startInWriteAction() = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val field = descriptor.psiElement?.parent as? PsiField ?: return
        val cls = field.containingClass ?: return
        WriteCommandAction.runWriteCommandAction(project) { convert(project, field, cls) }
    }

    private fun convert(project: Project, field: PsiField, cls: PsiClass) {
        val factory = JavaPsiFacade.getElementFactory(project)
        val name = field.name

        for (fqn in SpringNames.FIELD_INJECT) field.getAnnotation(fqn)?.delete()
        field.modifierList?.setModifierProperty(PsiModifier.FINAL, true)
        if (hasLombokConstructor(cls)) return // Lombok generates the constructor from the final field

        val statement = { ctor: PsiMethod -> factory.createStatementFromText("this.$name = $name;", ctor) }
        val ctors = cls.constructors
        val target: PsiMethod
        if (ctors.isEmpty()) {
            val created = factory.createConstructor(cls.name ?: return)
            created.modifierList.setModifierProperty(PsiModifier.PUBLIC, true)
            target = cls.addAfter(created, cls.fields.lastOrNull() ?: field) as PsiMethod
        } else {
            target = ctors.single()
            if (target.parameterList.parameters.any { it.name == name }) return
        }
        target.parameterList.add(factory.createParameter(name, field.type))
        target.body?.add(statement(target))

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(target)
        CodeStyleManager.getInstance(project).reformat(target)
    }
}

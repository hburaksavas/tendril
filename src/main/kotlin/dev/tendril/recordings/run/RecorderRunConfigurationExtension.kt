package dev.tendril.recordings.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.JavaRunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import dev.tendril.recordings.RecorderAgent
import org.jdom.Element
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Whether a run configuration starts the application with the Tendril recorder; kept on the configuration itself. */
object RecorderRunOption {
    private val KEY = Key.create<Boolean>("tendril.recorder.enabled")
    private const val ATTRIBUTE = "recorder"

    // copyable, so that duplicating a configuration or editing a copy keeps the choice
    fun isEnabled(configuration: RunConfigurationBase<*>): Boolean = configuration.getCopyableUserData(KEY) == true

    fun setEnabled(configuration: RunConfigurationBase<*>, enabled: Boolean) =
        configuration.putCopyableUserData(KEY, if (enabled) true else null)

    /** Written only when on, so configurations that do not use the recorder are not touched. */
    fun write(configuration: RunConfigurationBase<*>, element: Element) {
        if (isEnabled(configuration)) element.setAttribute(ATTRIBUTE, "true")
    }

    fun read(configuration: RunConfigurationBase<*>, element: Element) =
        setEnabled(configuration, element.getAttributeValue(ATTRIBUTE) == "true")

    /** Adds the agent argument to [parameters] unless a recorder argument is already there (e.g. typed into the VM options). */
    fun inject(parameters: JavaParameters, argument: String) {
        val list = parameters.vmParametersList
        if (list.list.none { RecorderAgent.ARGUMENT_PATTERN.containsMatchIn(it) }) list.add(argument)
    }
}

/**
 * A checkbox in every Java-based run configuration (Application, Spring Boot, JUnit, Kotlin, ...): attaches the recorder
 * when the application starts. The agent argument is added at launch and is never written into the VM options, so
 * the shared configuration file holds nothing specific to this machine.
 */
class RecorderRunConfigurationExtension : RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration is JavaRunConfigurationBase

    override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean =
        RecorderRunOption.isEnabled(applicableConfiguration)

    override fun getSerializationId(): String = "tendril-recorder"

    override fun getEditorTitle(): String = "Tendril"

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) =
        RecorderRunOption.read(runConfiguration, element)

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) =
        RecorderRunOption.write(runConfiguration, element)

    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P> = RecorderEditor()

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
        if (!RecorderRunOption.isEnabled(configuration)) return
        val argument = try {
            RecorderAgent.rawArgument(configuration.project)
        } catch (e: Exception) {
            throw ExecutionException("The Tendril recorder could not be prepared: ${e.message ?: e.javaClass.simpleName}", e)
        }
        RecorderRunOption.inject(params, argument)
    }
}

private class RecorderEditor<P : RunConfigurationBase<*>> : SettingsEditor<P>() {
    private val checkBox = JBCheckBox("Attach the Tendril recorder (records requests, Feign calls and messages)")

    override fun resetEditorFrom(configuration: P) {
        checkBox.isSelected = RecorderRunOption.isEnabled(configuration)
    }

    override fun applyEditorTo(configuration: P) = RecorderRunOption.setEnabled(configuration, checkBox.isSelected)

    override fun createEditor(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4, 0)
        add(checkBox, BorderLayout.NORTH)
    }
}

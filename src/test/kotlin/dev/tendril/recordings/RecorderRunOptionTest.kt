package dev.tendril.recordings

import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.JavaParameters
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.tendril.recordings.run.RecorderRunConfigurationExtension
import dev.tendril.recordings.run.RecorderRunOption
import org.jdom.Element

class RecorderRunOptionTest : BasePlatformTestCase() {

    private fun configuration(): ApplicationConfiguration =
        ConfigurationTypeUtil.findConfigurationType(ApplicationConfigurationType::class.java)
            .configurationFactories.first().createTemplateConfiguration(project) as ApplicationConfiguration

    private val agent = "-javaagent:/home/me/.tendril/tendril-recorder.jar=port=17843,token=abc,project=xyz"

    fun `test the option is off by default and can be switched`() {
        val c = configuration()
        assertFalse(RecorderRunOption.isEnabled(c))
        RecorderRunOption.setEnabled(c, true)
        assertTrue(RecorderRunOption.isEnabled(c))
        RecorderRunOption.setEnabled(c, false)
        assertFalse(RecorderRunOption.isEnabled(c))
    }

    fun `test the option is saved only when on and read back`() {
        val c = configuration()
        val off = Element("configuration")
        RecorderRunOption.write(c, off)
        assertNull(off.getAttributeValue("recorder"))

        RecorderRunOption.setEnabled(c, true)
        val on = Element("extension")
        RecorderRunOption.write(c, on)
        assertEquals("true", on.getAttributeValue("recorder"))

        val other = configuration()
        RecorderRunOption.read(other, on)
        assertTrue(RecorderRunOption.isEnabled(other))
        RecorderRunOption.read(other, off)
        assertFalse(RecorderRunOption.isEnabled(other))
    }

    fun `test the option survives cloning the configuration`() {
        val c = configuration()
        RecorderRunOption.setEnabled(c, true)
        assertTrue(RecorderRunOption.isEnabled(c.clone() as ApplicationConfiguration))
    }

    fun `test the agent is added to the vm parameters once`() {
        val params = JavaParameters()
        params.vmParametersList.add("-Xmx512m")
        RecorderRunOption.inject(params, agent)
        RecorderRunOption.inject(params, agent)
        assertEquals(listOf("-Xmx512m", agent), params.vmParametersList.list)
    }

    fun `test an agent argument typed into the vm options is not duplicated`() {
        val params = JavaParameters()
        params.vmParametersList.add("-javaagent:C:/x/tendril-recorder.jar=port=1,token=t,project=p")
        RecorderRunOption.inject(params, agent)
        assertEquals(1, params.vmParametersList.list.size)
    }

    fun `test the extension applies to Java run configurations and is enabled by the option`() {
        val extension = RecorderRunConfigurationExtension()
        val c = configuration()
        assertTrue(extension.isApplicableFor(c))
        assertFalse(extension.isEnabledFor(c, null))
        RecorderRunOption.setEnabled(c, true)
        assertTrue(extension.isEnabledFor(c, null))
    }

    fun `test nothing is added while the option is off`() {
        val params = JavaParameters()
        RecorderRunConfigurationExtension().updateJavaParameters(configuration(), params, null)
        assertTrue(params.vmParametersList.list.isEmpty())
    }
}

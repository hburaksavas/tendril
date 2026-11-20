package dev.tendril.config

import dev.tendril.SpringTestCase
import org.jetbrains.yaml.psi.YAMLKeyValue

class ConfigProfilesTest : SpringTestCase() {

    private fun valueElement(file: com.intellij.psi.PsiFile, key: String): YAMLKeyValue =
        ConfigNavigation.allConfigElements(project, key).filterIsInstance<YAMLKeyValue>().single { it.containingFile == file }

    fun `test the profile comes from the file name`() {
        val file = yaml("application-dev.yml", "server:\n  port: 1")
        assertEquals("dev", ConfigProfiles.profileOf(valueElement(file, "server.port")))
        val plain = yaml("application.yml", "other:\n  key: 1")
        assertNull(ConfigProfiles.profileOf(valueElement(plain, "other.key")))
    }

    fun `test the profile comes from an on-profile document`() {
        val file = yaml(
            "application.yml",
            """
            a:
              key: default
            ---
            spring:
              config:
                activate:
                  on-profile: prod
            b:
              key: prod-only
            """,
        )
        assertNull(ConfigProfiles.profileOf(valueElement(file, "a.key")))
        assertEquals("prod", ConfigProfiles.profileOf(valueElement(file, "b.key")))
    }

    fun `test the legacy spring profiles document key works too`() {
        val file = yaml("application.yml", "---\nspring:\n  profiles: staging\nc:\n  key: 1")
        assertEquals("staging", ConfigProfiles.profileOf(valueElement(file, "c.key")))
    }

    fun `test known profiles come from file names and documents`() {
        yaml("application-dev.yml", "x: 1")
        yaml("application-test.properties", "y=1")
        yaml("application.yml", "---\nspring:\n  config:\n    activate:\n      on-profile: prod\nz: 1")
        // the light test project is shared between test classes, so names left behind by other tests may show up too
        assertTrue(ConfigProfiles.knownProfiles(project).containsAll(setOf("dev", "prod", "test")))
    }

    fun `test profile expressions yield the plain profile names`() {
        yaml("application.yml", "---\nspring:\n  config:\n    activate:\n      on-profile: \"cloud & !local\"\nz: 1")
        val profiles = ConfigProfiles.knownProfiles(project)
        assertTrue(profiles.containsAll(setOf("cloud", "local")))
        assertTrue(profiles.toString(), profiles.none { it.contains("&") || it.contains("!") || it.contains(" ") })
    }

    fun `test profile keys are recognised`() {
        for (key in listOf("spring.profiles.active", "Spring.Profiles.Include", "spring.config.activate.on-profile")) {
            assertTrue(key, MetadataIndex.normalize(key) in ConfigProfiles.PROFILE_KEYS)
        }
        assertFalse(MetadataIndex.normalize("spring.profile") in ConfigProfiles.PROFILE_KEYS)
    }
}

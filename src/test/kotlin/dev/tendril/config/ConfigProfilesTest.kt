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

    fun `test known profiles ignore profile files outside the project`() {
        // The file name index is shared by every open project and covers libraries: a library stands in for another project.
        val dir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("tendril-other", null)
        java.io.File(dir, "application-elsewhere.yml").writeText("x: 1")
        val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        com.intellij.openapi.roots.ModuleRootModificationUtil.addModuleLibrary(module, "other-project", listOf(root.url), emptyList())
        try {
            yaml("application-here.yml", "y: 1")
            assertNotEmpty(com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
                "application-elsewhere.yml", com.intellij.psi.search.GlobalSearchScope.allScope(project),
            ))
            val profiles = ConfigProfiles.knownProfiles(project)
            assertTrue(profiles.toString(), "here" in profiles)
            assertFalse(profiles.toString(), "elsewhere" in profiles)
        } finally {
            com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(module) { model ->
                model.moduleLibraryTable.getLibraryByName("other-project")?.let { model.moduleLibraryTable.removeLibrary(it) }
            }
        }
    }

    fun `test profile groups and their members are known profiles without files of their own`() {
        yaml(
            "application.yml",
            """
            spring:
              profiles:
                group:
                  production:
                    - "proddb"
                    - "prodmq"
                  Blue-Green: canary, stable
            """,
        )
        val profiles = ConfigProfiles.knownProfiles(project)
        assertTrue(profiles.toString(), profiles.containsAll(setOf("production", "proddb", "prodmq", "Blue-Green", "canary", "stable")))
    }

    fun `test profile groups written in properties and as a dotted yaml key are known profiles`() {
        yaml("application.properties", "spring.profiles.group.qa[0]=qadb\nspring.profiles.group.qa[1]=qamq")
        yaml("bootstrap.yml", "spring.profiles.group.edge: edgecache")
        val profiles = ConfigProfiles.knownProfiles(project)
        assertTrue(profiles.toString(), profiles.containsAll(setOf("qa", "qadb", "qamq", "edge", "edgecache")))
        assertTrue(profiles.toString(), profiles.none { '[' in it || '.' in it })
    }

    fun `test a group inside a profile document is not a known profile`() {
        yaml("application-staging.yml", "spring:\n  profiles:\n    group:\n      ignoredgroup: ignoredmember")
        val profiles = ConfigProfiles.knownProfiles(project)
        assertFalse(profiles.toString(), "ignoredgroup" in profiles || "ignoredmember" in profiles)
    }

    fun `test a selected group expands to its members`() {
        yaml("application.yml", "spring:\n  profiles:\n    group:\n      production:\n        - proddb\n        - prodmq\n")
        assertEquals(listOf("production", "proddb", "prodmq"), ConfigProfiles.effectiveProfiles(project, "production"))
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

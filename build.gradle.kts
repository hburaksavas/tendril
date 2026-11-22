plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.tendril"
// gradle.properties holds the current version; a release build overrides it with -PpluginVersion=<tag without the v>
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3.6")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.properties")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin { jvmToolchain(21) }

/** The section of CHANGELOG.md for this version (or Unreleased), as the HTML the Marketplace shows under "What's New". */
fun changelogHtml(): String {
    val lines = file("CHANGELOG.md").takeIf { it.exists() }?.readLines().orEmpty()
    val heading = lines.indexOfFirst { it.startsWith("## [${project.version}]") }.takeIf { it >= 0 }
        ?: lines.indexOfFirst { it.startsWith("## [Unreleased]") }
    if (heading < 0) return ""

    fun inline(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    val html = StringBuilder()
    var inList = false
    for (line in lines.drop(heading + 1).takeWhile { !it.startsWith("## [") }) {
        when {
            line.startsWith("### ") -> {
                if (inList) html.append("</ul>").also { inList = false }
                html.append("<h4>").append(inline(line.removePrefix("### "))).append("</h4>")
            }
            line.startsWith("- ") -> {
                if (!inList) html.append("<ul>").also { inList = true }
                html.append("<li>").append(inline(line.removePrefix("- "))).append("</li>")
            }
        }
    }
    if (inList) html.append("</ul>")
    return html.toString()
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider { changelogHtml() }
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    // Signing and publishing read their secrets from the environment, so a plain build needs none of them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 0.2.0-beta.1 goes to the "beta" channel, a plain 0.2.0 to the default one
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides { recommended() }
    }
}

// The recorder agent jar travels inside the plugin; the IDE copies it to a stable place (see RecorderAgent.kt).
evaluationDependsOn(":recorder")
val recorderJar = project(":recorder").tasks.named<Jar>("jar")
tasks.processResources {
    dependsOn(recorderJar)
    from(recorderJar.map { it.archiveFile }) { into("recorder") }
}

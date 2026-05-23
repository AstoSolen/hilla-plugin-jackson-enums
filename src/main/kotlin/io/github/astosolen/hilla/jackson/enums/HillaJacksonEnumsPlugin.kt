package io.github.astosolen.hilla.jackson.enums

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

class HillaJacksonEnumsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("java") {
            configure(project)
        }
    }

    private fun configure(project: Project) {
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val mainSourceSet = sourceSets.named("main").get()

        val runtimeClasspath = project.configurations.named(mainSourceSet.runtimeClasspathConfigurationName)

        val generateMappings = project.tasks.register<GenerateHillaJacksonEnumMappingsTask>(
            "generateHillaJacksonEnumMappings"
        ) {
            group = "hilla"
            description =
                "Emit sidecar JSON of Java enum constants → @JsonProperty value for the hilla-plugin-jackson-enums Vite plugin."

            classesDirs.from(mainSourceSet.output.classesDirs)
            this.runtimeClasspath.from(runtimeClasspath)
            outputFile.set(project.layout.buildDirectory.file("hilla-jackson-enum-mappings.json"))

            dependsOn(project.tasks.named(mainSourceSet.compileJavaTaskName))
        }

        project.tasks.matching {
            it.name in setOf(
                "hillaGenerate",
                "vaadinPrepareFrontend",
                "vaadinBuildFrontend"
            )
        }.configureEach {
            dependsOn(generateMappings)
        }
    }
}
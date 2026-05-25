package io.github.astosolen.hilla.jackson.enums

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Gradle plugin that produces a sidecar JSON describing how Java enum constants are
 * serialized by Jackson via `@JsonProperty`.
 *
 * The sidecar is consumed by the companion npm package
 * [`hilla-plugin-jackson-enums-vite`](https://www.npmjs.com/package/hilla-plugin-jackson-enums-vite),
 * which rewrites Hilla-generated TypeScript enum values at Vite serve/build time so that the
 * wire format the backend uses matches the values the frontend looks up in its registries.
 *
 * ## Behavior
 *
 * Applying this plugin to a project:
 *
 * - Activates only after the `java` plugin is present (it depends on compiled classes from the
 *   `main` source set).
 * - Registers a single task: [GenerateHillaJacksonEnumMappingsTask] under the name
 *   `generateHillaJacksonEnumMappings`, in the `hilla` task group.
 * - Wires that task as a dependency of `hillaGenerate`, `vaadinPrepareFrontend`, and
 *   `vaadinBuildFrontend` (whichever exist in the current project), so the sidecar is
 *   regenerated whenever Vaadin/Hilla touches the frontend pipeline.
 *
 * The default output path is `<projectBuildDir>/hilla-jackson-enum-mappings.json`.
 *
 * ## Usage
 *
 * ```kotlin
 * plugins {
 *     id("io.github.astosolen.hilla-jackson-enums") version "<version>"
 * }
 * ```
 *
 * No further configuration is required for typical projects. The Vite plugin picks the sidecar
 * up automatically from the conventional location.
 */
class HillaJacksonEnumsPlugin : Plugin<Project> {

    /**
     * Entry point invoked by Gradle when the plugin is applied to [project].
     *
     * Defers actual configuration until the `java` plugin is present, since this plugin operates
     * on compiled classes from the main source set.
     */
    override fun apply(project: Project) {
        project.plugins.withId("java") {
            configure(project)
        }
    }

    /**
     * Registers the [GenerateHillaJacksonEnumMappingsTask] and hooks it into the standard
     * Vaadin/Hilla frontend lifecycle tasks.
     *
     * Resolves the `main` source set's `classesDirs` and `runtimeClasspath` as inputs to the
     * generator task, so the task is properly incremental and re-runs only when relevant inputs
     * change.
     */
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
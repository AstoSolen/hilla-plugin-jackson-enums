package io.github.astosolen.hilla.jackson.enums

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * Scans compiled Java classes for enums whose constants carry Jackson's `@JsonProperty`
 * annotation and emits a sidecar JSON file mapping each constant to its wire value.
 *
 * The companion npm package `hilla-plugin-jackson-enums-vite` reads this file to rewrite
 * Hilla-generated TypeScript enums at Vite serve/build time, keeping the values seen by the
 * browser aligned with what Jackson actually sends on the wire.
 *
 * ## Output format
 *
 * ```json
 * {
 *   "<fully.qualified.EnumName>": {
 *     "<JAVA_CONSTANT>": "<wire-value>"
 *   }
 * }
 * ```
 *
 * Enums without any `@JsonProperty`-annotated constants are omitted. Constants without the
 * annotation (or with an empty value) are omitted from their enum's entry.
 *
 * ## Annotation resolution
 *
 * Looks for both `com.fasterxml.jackson.annotation.JsonProperty` (Jackson 2) and
 * `tools.jackson.annotation.JsonProperty` (Jackson 3). The first one found on the runtime
 * classpath is used. If neither is available, an empty sidecar is written and the task exits
 * gracefully.
 *
 * ## Incrementality
 *
 * The task is fully Gradle-incremental: [classesDirs] and [runtimeClasspath] are declared as
 * inputs, [outputFile] as the single output. The task re-runs only when classes or dependencies
 * change.
 */
abstract class GenerateHillaJacksonEnumMappingsTask : DefaultTask() {

    /**
     * Directories containing compiled `.class` files to scan for enums.
     *
     * Typically wired by [HillaJacksonEnumsPlugin] to the main source set's `output.classesDirs`,
     * which includes `build/classes/java/main` for Java projects.
     */
    @get:InputFiles
    abstract val classesDirs: ConfigurableFileCollection

    /**
     * Runtime classpath used when loading scanned classes via [URLClassLoader].
     *
     * Provides access to Jackson's `@JsonProperty` annotation class and any transitive
     * dependencies referenced by scanned enums. Marked as `@Classpath` so Gradle ignores
     * ordering changes when computing the input hash.
     */
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    /**
     * Target file for the emitted sidecar JSON.
     *
     * Defaults to `<projectBuildDir>/hilla-jackson-enum-mappings.json` when wired by
     * [HillaJacksonEnumsPlugin]. The Vite plugin's default search path resolves to this
     * location for Vaadin projects.
     */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /**
     * Walks every directory in [classesDirs], loads each `.class` file via an isolated
     * [URLClassLoader] backed by [runtimeClasspath], filters to enum types, and writes the
     * resulting `FQN → constant → wire value` map to [outputFile].
     *
     * The classloader uses [ClassLoader.getPlatformClassLoader] as its parent to isolate the
     * scan from Gradle's own classpath, preventing accidental resolution of Gradle-bundled
     * versions of Jackson or other libraries.
     */
    @TaskAction
    fun generate() {
        val existingClassesDirs = classesDirs.files.filter { it.exists() && it.isDirectory }
        val outputFile = outputFile.get().asFile

        if (existingClassesDirs.isEmpty()) {
            logger.lifecycle("No compiled classes found — writing empty Hilla Jackson enum mappings.")
            writeJson(outputFile, emptyMap())
            return
        }

        val urls = (existingClassesDirs + runtimeClasspath.files)
            .map { it.toURI().toURL() }
            .toTypedArray()

        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { classLoader ->
            val jsonPropertyClasses = loadJsonPropertyClasses(classLoader)

            if (jsonPropertyClasses.isEmpty()) {
                logger.lifecycle("No Jackson @JsonProperty class found on runtime classpath — nothing to scan.")
                writeJson(outputFile, emptyMap())
                return
            }

            val mappings = sortedMapOf<String, Map<String, String>>()

            existingClassesDirs.forEach { classesDir ->
                classesDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach scan@{ file ->
                        val fqn = file.toClassName(classesDir) ?: return@scan

                        val clazz = runCatching { classLoader.loadClass(fqn) }.getOrNull()
                            ?: return@scan

                        if (!clazz.isEnum) return@scan

                        val enumMap = extractEnumMapping(clazz, jsonPropertyClasses)

                        if (enumMap.isNotEmpty()) {
                            mappings[fqn] = enumMap
                        }
                    }
            }

            writeJson(outputFile, mappings)

            logger.lifecycle(
                "Wrote ${mappings.size} enum mapping(s) to ${
                    outputFile.relativeToOrSelf(project.projectDir)
                }"
            )
        }
    }

    /**
     * Resolves Jackson's `JsonProperty` annotation class from the project's runtime classpath.
     *
     * Tries Jackson 2's and Jackson 3's fully qualified names in order and returns all that
     * resolved successfully. Returning an empty list signals that Jackson is not on the
     * classpath at all — callers treat this as "nothing to scan" and exit gracefully.
     */
    private fun loadJsonPropertyClasses(
        classLoader: ClassLoader
    ): List<Class<out Annotation>> {
        return listOf(
            "com.fasterxml.jackson.annotation.JsonProperty",
            "tools.jackson.annotation.JsonProperty"
        ).mapNotNull { name ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                classLoader.loadClass(name) as Class<out Annotation>
            }.getOrNull()
        }
    }

    /**
     * Builds the `constant name → wire value` map for a single enum [clazz].
     *
     * Iterates over the enum constants reflectively, looks up the `value()` of any
     * `@JsonProperty` annotation present (checking each candidate annotation class from
     * [jsonPropertyClasses]), and collects only non-empty results. Constants without an
     * annotation are silently skipped.
     */
    private fun extractEnumMapping(
        clazz: Class<*>,
        jsonPropertyClasses: List<Class<out Annotation>>
    ): Map<String, String> {
        val constants = clazz.enumConstants ?: return emptyMap()
        val enumMap = sortedMapOf<String, String>()

        for (constant in constants) {
            val name = (constant as Enum<*>).name
            val field = runCatching<Field> { clazz.getField(name) }.getOrNull()
                ?: continue

            val value = findJsonPropertyValue(field, jsonPropertyClasses)

            if (!value.isNullOrEmpty()) {
                enumMap[name] = value
            }
        }

        return enumMap
    }

    /**
     * Reads the `value()` of the first matching Jackson `@JsonProperty` annotation on [field].
     *
     * Returns `null` if no candidate annotation is present, or if the annotation's `value()`
     * is missing or empty. Empty strings are treated as absent to avoid emitting useless
     * `"<constant>": ""` entries into the sidecar.
     */
    private fun findJsonPropertyValue(
        field: Field,
        jsonPropertyClasses: List<Class<out Annotation>>
    ): String? {
        for (annotationClass in jsonPropertyClasses) {
            val annotation = field.getAnnotation(annotationClass) ?: continue
            val valueMethod = runCatching<Method> {
                annotationClass.getMethod("value")
            }.getOrNull() ?: continue

            val value = valueMethod.invoke(annotation) as? String

            if (!value.isNullOrEmpty()) {
                return value
            }
        }

        return null
    }

    /**
     * Writes [mappings] to [outputFile] as pretty-printed JSON, creating parent directories
     * if necessary. Uses Groovy's `JsonOutput` because it is available without adding a
     * dependency to the Gradle plugin.
     */
    private fun writeJson(
        outputFile: File,
        mappings: Map<String, Map<String, String>>
    ) {
        outputFile.parentFile.mkdirs()

        val json = JsonOutput.toJson(mappings)
        outputFile.writeText(JsonOutput.prettyPrint(json))
    }

    /**
     * Converts a `.class` file path under [classesDir] to its fully qualified class name.
     *
     * Example: `com/example/Foo.class` (relative to `build/classes/java/main`) becomes
     * `com.example.Foo`. Returns `null` for any non-`.class` file so callers can filter
     * them out cleanly.
     */
    private fun File.toClassName(classesDir: File): String? {
        val relativePath = relativeTo(classesDir).path

        if (!relativePath.endsWith(".class")) {
            return null
        }

        return relativePath
            .removeSuffix(".class")
            .replace(File.separatorChar, '.')
    }
}
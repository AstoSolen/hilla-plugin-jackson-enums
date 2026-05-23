package io.github.lowsortwizard.hilla.jackson.enums

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

abstract class GenerateHillaJacksonEnumMappingsTask : DefaultTask() {

    @get:InputFiles
    abstract val classesDirs: ConfigurableFileCollection

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

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

    private fun writeJson(
        outputFile: File,
        mappings: Map<String, Map<String, String>>
    ) {
        outputFile.parentFile.mkdirs()

        val json = JsonOutput.toJson(mappings)
        outputFile.writeText(JsonOutput.prettyPrint(json))
    }

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
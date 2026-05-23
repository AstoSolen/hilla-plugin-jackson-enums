plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

group = "io.github.lowsortwizard"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

gradlePlugin {
    website.set("https://github.com/lowsortwizard/hilla-plugin-jackson-enums")
    vcsUrl.set("https://github.com/lowsortwizard/hilla-plugin-jackson-enums")

    plugins {
        create("hillaJacksonEnums") {
            id = "io.github.lowsortwizard.hilla-jackson-enums"
            implementationClass = "io.github.lowsortwizard.hilla.jackson.enums.HillaJacksonEnumsPlugin"
            displayName = "Hilla Jackson Enums"
            description = "Generates sidecar JSON mappings for Hilla TypeScript enums based on Jackson @JsonProperty annotations."
            tags.set(listOf("hilla", "vaadin", "jackson", "typescript", "enums"))
        }
    }
}
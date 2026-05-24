# hilla-jackson-enums (Gradle plugin)

Gradle plugin that scans your project's compiled enums for Jackson `@JsonProperty` annotations and emits a sidecar JSON file describing how each enum constant is serialized on the wire.

Pair it with the companion Vite plugin [`hilla-plugin-jackson-enums-vite`](https://www.npmjs.com/package/hilla-plugin-jackson-enums-vite), which reads that sidecar and rewrites Hilla-generated TypeScript enum values to match — fixing the silent mismatch where the frontend looks up a Java constant name (`FIRST_CONSTANT`) but the API actually sends a different string (`<wire-value>`).

## The mismatch

Given a Java enum where Jackson sends a different wire format than the constant name:

```java
public enum SomeEnum {
    @JsonProperty("...") FIRST_CONSTANT,
    @JsonProperty("...") SECOND_CONSTANT
}
```

Hilla generates TypeScript enums whose member values equal the Java constant names. Frontend code keyed off the API-returned string ends up looking up a key that isn't there, and every render of that enum value silently fails.

This Gradle plugin produces the `JAVA_CONSTANT → wire-value` map that the Vite plugin uses to fix the generated TypeScript.

## Apply

```kotlin
plugins {
    id("io.github.astosolen.hilla-jackson-enums") version "0.1.0"
}
```

Requires the `java` plugin to be applied in the same project — the task operates on compiled `.class` files from the `main` source set.

## What it does

Applying the plugin:

1. Registers a task **`generateHillaJacksonEnumMappings`** under the `hilla` group.
2. Wires that task as a dependency of `hillaGenerate`, `vaadinPrepareFrontend`, and `vaadinBuildFrontend` (whichever exist in the project), so the sidecar is regenerated whenever Vaadin/Hilla touches the frontend pipeline.

You generally don't run the task manually — it's pulled into your normal build automatically.

The default output path is **`<projectBuildDir>/hilla-jackson-enum-mappings.json`**.

## Output format

```json
{
  "<fully.qualified.EnumName>": {
    "<JAVA_CONSTANT>": "<wire-value>"
  }
}
```

- Top-level keys are fully qualified Java enum names.
- Inner keys are Java enum constant names.
- Inner values are the strings Jackson uses on the wire (resolved from `@JsonProperty("...")`).
- Enums without any annotated constants are omitted. Constants without an annotation are omitted from their enum's entry.

## How it works

1. Loads the project's compiled classes (`main` source set output) plus the runtime classpath into an isolated `URLClassLoader`. The parent is `ClassLoader.getPlatformClassLoader()` so Gradle's own classes don't leak into the scan.
2. Resolves Jackson's `@JsonProperty` annotation — tries both `com.fasterxml.jackson.annotation.JsonProperty` (Jackson 2) and `tools.jackson.annotation.JsonProperty` (Jackson 3); the first one available wins.
3. Walks every `.class` file under the compiled classes directories, filters to `enum` types, reads the `value()` of each constant's annotation, and accumulates the result.
4. Writes the JSON to the configured output path.

If neither Jackson annotation class is on the runtime classpath, the task writes an empty mapping and logs a notice — your build still succeeds, the Vite plugin then no-ops.

## Incrementality

The task declares `classesDirs` and `runtimeClasspath` as inputs and the JSON file as its single output, so Gradle re-runs it only when those inputs actually change.

## Customisation

For typical Vaadin/Hilla projects no configuration is needed. To override defaults (alternative output path, additional class directories, etc.) configure the task directly:

```kotlin
import io.github.lowsortwizard.hilla.jackson.enums.GenerateHillaJacksonEnumMappingsTask

tasks.named<GenerateHillaJacksonEnumMappingsTask>("generateHillaJacksonEnumMappings") {
    outputFile.set(layout.buildDirectory.file("custom-path/enum-mappings.json"))
}
```

If you change `outputFile`, set the corresponding `sidecarPath` option on the Vite plugin so the two halves stay in sync.

## Companion Vite plugin

This Gradle plugin only produces the sidecar JSON. The actual TypeScript rewriting is done by the npm package [`hilla-plugin-jackson-enums-vite`](https://www.npmjs.com/package/hilla-plugin-jackson-enums-vite), which you add to your frontend `vite.config.ts`:

```ts
import { hillaJacksonEnums } from "hilla-plugin-jackson-enums-vite";
// ...
plugins: [hillaJacksonEnums()],
```

The Vite plugin's default sidecar search path matches the Gradle plugin's default output location, so no extra wiring is required for typical Vaadin layouts.

## License

MIT.

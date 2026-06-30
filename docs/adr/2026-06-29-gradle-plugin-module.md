# 2026-06-29: Gradle Plugin Module

## Context

Users currently need to apply the KSP Gradle plugin manually, add `io.github.samhoby:annotations` to `commonMainImplementation`, and add `io.github.samhoby:processor` to `kspJs`. This couples the user to KSP internals and creates boilerplate in every consuming project.

## Options Considered

1. **Document setup steps only**: no change to the published artifacts, so the setup burden stays on the user.
2. **Gradle plugin module**: a separate `:gradle-plugin` module publishes a plugin under `io.github.samhoby.kotlintojs` that applies KSP and wires up both artifacts automatically. The user only needs `id("io.github.samhoby.kotlintojs")`.
3. **Fat jar or shadow jar**: embed all dependencies, meaning KSP, annotations, and processor, in a single artifact. This is complex, hides the dependency graph from Gradle, and conflicts with projects that already use KSP.

## Decision

Add a `:gradle-plugin` module that publishes a Gradle plugin with ID `io.github.samhoby.kotlintojs`. The plugin:

- Listens for `org.jetbrains.kotlin.multiplatform` to be applied, then applies `com.google.devtools.ksp` programmatically.
- Adds `io.github.samhoby:annotations` to `commonMainImplementation`.
- Adds `io.github.samhoby:processor` to `kspJs` inside `afterEvaluate`, because KSP creates the `kspJs` configuration lazily after the user declares a `js()` target.

`symbol-processing-gradle-plugin` is embedded as an `implementation` dependency of `:gradle-plugin` so that `pluginManager.apply("com.google.devtools.ksp")` resolves without the user declaring anything in their `pluginManagement`.

## Consequences

- **Three published artifacts** instead of two: `io.github.samhoby:annotations`, `io.github.samhoby:processor`, and the Gradle plugin marker `io.github.samhoby.kotlintojs:io.github.samhoby.kotlintojs.gradle.plugin`. All three must be released and versioned together.
- The plugin hardcodes `GROUP = "io.github.samhoby"` and `VERSION = "0.1.0"` for the annotations and processor coordinates it wires up. Any group or version change requires updating these constants, and they must stay in sync with the modules' published coordinates.
- `afterEvaluate` is used for `kspJs`. This is an acceptable trade-off at this stage and can be replaced with a reactive configuration callback in a future iteration.
- Users who prefer manual configuration can still depend on `annotations` and `processor` directly without applying the Gradle plugin.

## Migration Plan

Users currently configured manually can switch to:

```kotlin
plugins {
    kotlin("multiplatform")
    id("io.github.samhoby.kotlintojs")
}
```

and remove their explicit `ksp` plugin declaration along with the `kspJs` and `commonMainImplementation` entries for these artifacts.

## Owner
Thierry Simões

## Date
2026-06-29

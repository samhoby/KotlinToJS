# 2026-06-29: Android Target for the Annotations Module

## Context

The `annotations` module was published as a Kotlin Multiplatform artifact targeting jvm, js, and the iOS family. A real consumer, the Unravel app's `:shared` module, declares `androidTarget()` in addition to jvm, js, and iOS, and applies the annotations in `commonMain`.

When `commonMain` depends on a library that does not cover **every** target the consumer compiles for, the Kotlin metadata compilation `compileCommonMainKotlinMetadata` cannot assemble a common classpath. The symptom is a global collapse of the standard library: dozens of `Cannot access built-in declaration 'kotlin.String'` and `kotlin.Int` errors across unrelated files, plus `Unresolved reference` for `kotlin.time.Instant`, `Clock`, and `runCatching`. The failure was isolated to the `annotations` artifact alone, with no KSP and no Gradle plugin involved, and the only structural gap versus the consumer was the missing **android** target.

## Options Considered

1. **Add `androidTarget()` to `annotations`**: makes the artifact resolvable for any consumer whose `commonMain` targets Android. Requires applying the Android Gradle Plugin in the module.
2. **Tell consumers to drop `androidTarget()`**: not acceptable, because the consumer is a real KMP app that ships Android.
3. **Move the annotation usage out of `commonMain` into per-platform source sets**: invasive for consumers and defeats the point of a shared annotation applied to shared code.

## Decision

Add `androidTarget()` to the `annotations` module by applying `com.android.library` at AGP 8.13.2, with `namespace = "io.github.samhoby.kotlintojs.annotations"`, `compileSdk = 36`, and `minSdk = 24` to mirror the consumer. The `androidTarget` publishes its `release` variant. The annotations module now targets jvm, android, js, and the iOS family, so any KMP consumer's `commonMain` can resolve it for every target.

Developer justification, recorded per the constitution's ADR protocol: "For a general consumer that uses the annotations in its `shared` module, the `annotations` artifact must allow all targets so that metadata compilation resolves correctly."

## Consequences

- **New dependency:** the Android Gradle Plugin is now required to build and publish `annotations`. Building it requires an Android SDK, configured through a `local.properties` file with `sdk.dir` that stays out of version control.
- The `annotations` published Gradle module gains android variants such as `releaseApiElements-published`.
- `settings.gradle.kts` now declares `google()` in both `pluginManagement` and `dependencyResolutionManagement` so AGP and android dependencies resolve.
- This supersedes the constitution's "Kotlin Multiplatform: jvm, js, ios" description of the annotations artifact. The artifact now also targets android.

## Open Risk

The iOS klibs are cross-compiled on Windows, which is not officially supported. If a consumer's metadata compilation still fails after this change, the next suspect is those klibs, and the resolution would be to publish `annotations` from a macOS or CI host.

## Migration Plan

Consumers do not change anything. Existing jvm, js, and iOS consumers keep working, and android consumers now resolve. Republish all artifacts with `./gradlew publishToMavenLocal`.

## Owner
Thierry Simões

## Date
2026-06-29

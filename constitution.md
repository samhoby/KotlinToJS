# Constitution

## 1. Core Philosophy & Stack

- Clarity over cleverness. Generated code must be readable, predictable, and debuggable.
- Spec-driven: every non-trivial change starts with a short plan and acceptance criteria.
- Stack: Kotlin + KSP (Symbol Processing API) + KotlinPoet for code generation.
- Testing: compile-testing via `kctfork`. Always assert on generated code content, not just compilation success.
- Publishing: three artefacts under group `io.github.samhoby`. `annotations` (Kotlin Multiplatform: jvm, android, js, ios) is consumed in the user's `commonMain`, `processor` (JVM) is applied via `add("kspJs", ...)`, and the `:gradle-plugin` publishes the `io.github.samhoby.kotlintojs` plugin that wires both. Follow semantic versioning.
- Dependencies: add only when they remove complexity. Prefer removing libs over adding.
- KDoc is mandatory for public annotations, processor entry points, and non-obvious type mapping rules.

## 2. Architectural Patterns

- Annotation declarations live in the `:annotations` module (`annotations/src/commonMain/kotlin/io/github/samhoby/kotlintojs/annotations/`) with no logic and no imports beyond Kotlin stdlib. It is a separate multiplatform artefact (published as `io.github.samhoby:annotations`) so consumers can reference the annotations from `commonMain`. The processor lives in the JVM-only `:processor` module (published as `io.github.samhoby:processor`).
- Processing pipeline: `WrapperProcessorProvider` → `WrapperProcessor.process` → `generateWrapper` → KotlinPoet → disk.
- Each limitation handler lives in its own file under `processor/handlers/`, named after the limitation it solves (e.g., `CollectionHandler.kt`, `LongHandler.kt`, `SuspendHandler.kt`, `MapHandler.kt`, `ValueClassHandler.kt`). `WrapperProcessor` orchestrates them but contains no conversion logic itself.
- Type conversion logic is self-contained in `types/TypeMapping` and the respective handler. Do not scatter conversion decisions across files.
- When at least one `Map` type appears at a boundary, `MapHandler` writes a separate `TypeConversion.kt` in package `kotlintojs.generated`, containing one decode/encode function pair per distinct map signature encountered in the processing round. Each pair is named after its signature (for example `Json.toMap1()` and `Map<String, Long>.toJson1()`) rather than sharing a single name, because Kotlin cannot overload by return type: two `Json.toMap()` functions differing only in their `Map` return type are a compile error. Nested map signatures are registered recursively so the inner pair always exists. Wrappers import only the conversion functions they reference. See `docs/adr/2026-06-21-distinct-map-conversion-names.md`.
- `JsExportUtils.kt` is the shared file for standalone `@JsExportFunction`s: it is written only when at least one such function exists and always contains the `JsExportUtils` object. It never holds map conversion helpers.
- Standalone `@JsExportFunction`s (outside any class scope) must be collected into the `JsExportUtils` object in `JsExportUtils.kt`, regardless of how many source files they come from.
- Generated wrapper names follow the convention `{OriginalName}Js` and are always `@JsExport object`s with `@OptIn(ExperimentalJsExport::class)`. Class and value-class wrappers additionally carry `@JsName("{OriginalName}")` so JavaScript consumes them under the original source name rather than the internal `{OriginalName}Js` name. The shared `JsExportUtils` object is not renamed.
- Handle `object` vs `class` kind at generation time: objects must not be instantiated with `()`.
- When `@JsExportClass` is placed on a value class itself, `generateWrapper` omits the `service` property and instead prepends the underlying value as the first parameter of every generated function (named after the class, lower-camel-cased). The value class is constructed per-call inside each function body.

## 3. Strict Prohibitions / The Never List

- Never emit code that fails the Kotlin/JS compiler or produces unusable types at the JS boundary.
- Never expose `Long`, `List`, `Set`, `Map`, value classes, or other unsupported types directly in `@JsExport` declarations. Always convert at the wrapper boundary.
- Never silently convert `Long` to `Double`: `LongHandler` must emit a build **warning** with BigInt guidance when `Long` appears at a direct export boundary and `longAsBigInt` is not set. The build still succeeds with a `Double` fallback. The conversion must never happen without the warning.
- The `longAsBigInt` KSP option (`ksp { arg("longAsBigInt", "true") }`) activates BigInt passthrough mode. Consumers must also add `"-Xes-long-as-bigint"` to their Kotlin/JS `freeCompilerArgs` and target ES2015.
- Never generate more than one `TypeConversion.kt` or more than one `JsExportUtils.kt` per processing round.
- Never silently swallow a name-mangling conflict. Log an error via `KSPLogger` and let the build fail.
- Never leave public annotation semantics or processor entry points without KDoc.
- Never publish a breaking change to the plugin without a major version bump.

## 4. Mandatory Requirements / The Always List

- Always document KSP processor logic with KDoc for public functions, classes, and non-obvious mapping rules.
- Always write a `kctfork` compile test for every new type mapping or code generation path, covering both parameter and return type positions.
- Always assert on generated code content (`assertTrue(code.contains(...))`), not just that compilation succeeded.
- Always respect `@JsName` on source functions when determining the exported JS name.
- Always update `README.md` when a change affects usage, supported types, or generated output shape.

## 5. Definition of Done

- [ ] `./gradlew build` passes (compile + all tests).
- [ ] New type mappings have tests for parameter position and return type position.
- [ ] Generated code is valid Kotlin/JS with no unsupported types at the `@JsExport` boundary.
- [ ] KDoc added for any new public annotation, processor method, or type mapping rule.
- [ ] `README.md` updated if the change affects usage or supported types.

**Pre-change checklist (spec-driven)**

- Confirm the change scope and review the diff before implementation.
- Write a short plan with acceptance criteria for non-trivial changes.
- Apply this checklist before any major or non-trivial change.
- Determine if an ADR trigger applies. If so, collect justification first.

**Automated ADR protocol**

- Trigger on: new dependency, new supported type, change to generated wrapper structure, change to annotation semantics, publishing or versioning strategy shift.
- ADR must record: context, options considered, decision, consequences, migration plan, owner, date.
- Major decisions include: adding new annotation types, changing wrapper naming convention, changing `Map` conversion strategy, adding a coroutine runtime dependency, changing JVM toolchain version, changing the plugin group/artefact ID.
- Major decisions must be captured in ADRs. Agents must not revert them without explicit permission from the developer.
- Store ADRs in `docs/adr/` as `YYYY-MM-DD-short-title.md`.
- AI tools must ask the developer for justification of triggered changes before drafting the ADR, and then use that justification in the ADR.
- AI tools must create the ADR entry automatically when triggers are met and link it from the change description.

**ADR justification prompt**

- Problem statement: what issue or goal prompted the change?
- Options considered: what alternatives were evaluated?
- Decision rationale: why was this option chosen?
- Impact and risks: expected effects and trade-offs.
- Migration/rollout: any steps, sequencing, or compatibility notes.
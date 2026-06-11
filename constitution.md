# Constitution

## 1. Core Philosophy & Stack

- Clarity over cleverness. Generated code must be readable, predictable, and debuggable.
- Spec-driven: every non-trivial change starts with a short plan and acceptance criteria.
- Stack: Kotlin + KSP (Symbol Processing API) + KotlinPoet for code generation.
- Testing: compile-testing via `kctfork`; always assert on generated code content, not just compilation success.
- Publishing: two Maven artefacts via `maven-publish` under group `pt.kotlintojs` — `annotations` (Kotlin Multiplatform: jvm, js, ios) consumed in the user's `commonMain`, and `processor` (JVM) applied via `add("kspJs", ...)`. Follow semantic versioning.
- Dependencies: add only when they remove real complexity; prefer removing libs over adding.
- KDoc is mandatory for public annotations, processor entry points, and non-obvious type mapping rules.

## 2. Architectural Patterns

- Annotation declarations live in the `:annotations` module (`annotations/src/commonMain/kotlin/annotations/`) — no logic, no imports beyond Kotlin stdlib. It is a separate multiplatform artefact (published as `pt.kotlintojs:annotations`) so consumers can reference the annotations from `commonMain`; the processor lives in the JVM-only `:processor` module (published as `pt.kotlintojs:processor`).
- Processing pipeline: `WrapperProcessorProvider` → `WrapperProcessor.process` → `generateWrapper` → KotlinPoet → disk.
- Each limitation handler lives in its own file under `processor/handlers/`, named after the limitation it solves (e.g., `CollectionHandler.kt`, `LongHandler.kt`, `SuspendHandler.kt`, `MapHandler.kt`, `ValueClassHandler.kt`). `WrapperProcessor` orchestrates them but contains no conversion logic itself.
- Type conversion logic is self-contained in `types/TypeMapping` and the respective handler; do not scatter conversion decisions across files.
- `TypeConversion.kt` is only generated when `Map` types are present; never generate it unnecessarily.
- Standalone `@JsExportFunction`s (outside any class scope) must be collected into a single `JsExportUtils` object — one file, regardless of how many source files they come from.
- Generated wrapper names follow the convention `{OriginalName}Js` and are always `@JsExport object`s with `@OptIn(ExperimentalJsExport::class)`.
- Handle `object` vs `class` kind at generation time: objects must not be instantiated with `()`.
- When `@JsExportClass` is placed on a value class itself, `generateWrapper` omits the `service` property and instead prepends the underlying value as the first parameter of every generated function (named after the class, lower-camel-cased). The value class is constructed per-call inside each function body.

## 3. Strict Prohibitions / The Never List

- Never emit code that fails the Kotlin/JS compiler or produces unusable types at the JS boundary.
- Never expose `Long`, `List`, `Set`, `Map`, value classes, or other unsupported types directly in `@JsExport` declarations — always convert at the wrapper boundary.
- Never silently convert `Long` to `Double`: `LongHandler` must emit a build **warning** (with actionable BigInt guidance) when `Long` appears at a direct export boundary and `longAsBigInt` is not set. The build still succeeds with a `Double` fallback — the conversion must never happen without the warning.
- The `longAsBigInt` KSP option (`ksp { arg("longAsBigInt", "true") }`) activates BigInt passthrough mode; consumers must also add `"-Xes-long-as-bigint"` to their Kotlin/JS `freeCompilerArgs` and target ES2015.
- Never generate multiple `TypeConversion.kt` files in a single processing round.
- Never silently swallow a name-mangling conflict — log an error via `KSPLogger` and let the build fail.
- Never leave public annotation semantics or processor entry points without KDoc.
- Never publish a breaking change to the plugin without a major version bump.

## 4. Mandatory Requirements / The Always List

- Always document KSP processor logic with KDoc for public functions, classes, and non-obvious mapping rules.
- Always write a `kctfork` compile test for every new type mapping or code generation path — covering both parameter and return type positions.
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
- Determine if an ADR trigger applies; if so, collect justification first.

**Automated ADR protocol**

- Trigger on: new dependency, new supported type, change to generated wrapper structure, change to annotation semantics, publishing or versioning strategy shift.
- ADR must record: context, options considered, decision, consequences, migration plan, owner, date.
- Major decisions include: adding new annotation types, changing wrapper naming convention, changing `Map` conversion strategy, adding a coroutine runtime dependency, changing JVM toolchain version, changing the plugin group/artefact ID.
- Major decisions must be captured in ADRs; agents must not revert them without explicit permission from the developer.
- Store ADRs in `docs/adr/` as `YYYY-MM-DD-short-title.md`.
- AI tools must ask the developer for justification of triggered changes before drafting the ADR, and then use that justification in the ADR.
- AI tools must create the ADR entry automatically when triggers are met and link it from the change description.

**ADR justification prompt**

- Problem statement: what issue or goal prompted the change?
- Options considered: what alternatives were evaluated?
- Decision rationale: why was this option chosen?
- Impact and risks: expected effects and trade-offs.
- Migration/rollout: any steps, sequencing, or compatibility notes.
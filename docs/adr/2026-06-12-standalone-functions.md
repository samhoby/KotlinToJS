# JsExportUtils for standalone functions

- Date: 2026-06-12
- Owner: Thierry Simões
- Status: accepted

## Context

The README, the `@JsExportFunction` KDoc, and the constitution all stated that top-level
functions annotated with `@JsExportFunction` are collected into a shared `JsExportUtils` object.
`WrapperProcessor` only grouped functions whose parent was a class and dropped top-level
functions, so the object was never generated and no test covered it.

## Options considered

1. Implement the documented behaviour in `WrapperProcessor`.
2. Remove the `JsExportUtils` statements from the README, the KDoc, and the constitution.

## Decision

Option 1. `process` now routes functions without an enclosing class into a `standaloneFunctions`
list and calls `generateUtils`, which emits a single `@JsExport object JsExportUtils` in the root
package. Each generated function delegates to the original top-level function, imported from its
source package. `ManglingHandler.checkConflicts` accepts a nullable class declaration so the same
overload and duplicate-name checks apply to standalone functions.

## Consequences

- Top-level `@JsExportFunction` declarations now produce a `JsExportUtils.kt` file.
- Standalone functions from every source file land in one object, per the constitution.
- Suspend and collection conversions apply inside `JsExportUtils` through the existing handlers.
- The documentation now matches the implementation.

## Migration plan

No consumer action. Projects relying on the documented behaviour now receive the generated object.

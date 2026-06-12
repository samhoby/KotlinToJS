# Recursive collection conversion

- Date: 2026-06-12
- Owner: Thierry Simões
- Status: accepted

## Context

`CollectionHandler` converted only the outermost collection. For `List<List<String>>` the
generated return body was `service.getMatrix().toTypedArray()`, which produces
`Array<List<String>>`. The declared boundary type was `Array<Array<String>>`, so the generated
Kotlin/JS did not compile. Parameter position had the same flaw with `.toList()` / `.toSet()`.
The README documented nested conversion as supported, and a test asserted the nested type
signature but never compiled the generated wrapper, so the defect went undetected.

## Options considered

1. Make element conversion recursive in `CollectionHandler` so inner collections, and `Long`
   elements, are converted at every depth.
2. Remove `List<List<T>>` from the supported set and document a single-level limit.

## Decision

Option 1. `resolveMapping` now delegates to `fromKotlinExpr` and `toKotlinExpr`, which recurse
through `List` and `Set` element types and apply the `Long` conversion per element. When an
element needs no conversion the output collapses to a plain `toTypedArray()` / `toList()` /
`toSet()`, keeping the simple cases unchanged.

## Consequences

- `List<List<T>>`, `List<Set<T>>`, and `Long` element types now generate compiling Kotlin/JS.
- The generated body shape changed for nested collections (it now contains `.map { ... }`).
- Tests assert the recursive body, not only the type signature.
- Value classes and `Map` as collection element types remain unconverted and are still listed
  as a known limitation.

## Migration plan

No consumer action. Regenerating wrappers produces compiling output where nested collections
previously failed.

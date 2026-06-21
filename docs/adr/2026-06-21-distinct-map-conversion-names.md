# Distinct map conversion function names

- Date: 2026-06-21
- Owner: Thierry Simões
- Status: accepted

## Context

`MapHandler` generated map decode functions as overloads sharing the name `toMap`, distinguished
only by their `Map` return type (`fun Json.toMap(): Map<String, String>`,
`fun Json.toMap(): Map<Long, Long>`, ...). Kotlin cannot overload by return type: two such
functions are a `conflicting overloads` compile error. Any processing round with two or more
distinct map signatures therefore emitted a `TypeConversion.kt` that did not compile. The defect
was invisible because the test suite asserts on generated source text and never compiles the
generated Kotlin/JS. The `toJson` direction was unaffected because each overload had a distinct
`Map` receiver. The same handler also never registered nested map signatures, so
`Map<String, Map<String, Int>>` called an inner `toMap()` overload that was never generated, and
non-`String`/`Long` key types produced type-mismatched bodies.

## Options considered

1. Inline the conversion at each wrapper call site, emitting no shared functions.
2. Give every map signature a distinct, signature-derived function name pair (e.g., `toStringLongMap`).
3. Give every map signature a distinct, simple function name pair suffixed with a sequential numeric ID (e.g., `toMap1`, `toJson1`).

## Decision

Option 3. Each distinct `Map<K, V>` produces a decode function `Json.toMap{id}()` and an encode
function `Map<K, V>.toJson{id}()`. The `{id}` is a simple sequential integer assigned during the KSP
processing round. A cache (`typeIds`) binds each unique map signature to its assigned ID to ensure
consistency across the file.

Option 1 was rejected because converting a nested JS object back to a `Map` needs `Object.keys`
on arbitrary sub-expressions, which is awkward to inline readably. Option 2 was rejected because
complex nested types result in excessively long and unreadable function names; numeric IDs provide
a simpler and cleaner alternative.

Nested map signatures are registered recursively so the inner pair always exists, and the outer
function calls it by its specific ID. Key conversion was extended to all primitive key types, with
a `KSPLogger` error for unsupported keys. Wrappers import only the specific numeric conversion functions
they reference.

## Consequences

- `TypeConversion.kt` compiles for any number of distinct map signatures.
- The generated names changed shape: `toMap` / `toJson` became `toMap{id}` / `toJson{id}`.
- `MapHandler` now manages state (`currentId` and `typeIds`), which must be cleared between processing rounds.
- Nested maps and primitive key types now generate compiling Kotlin/JS.
- `MapHandler` now takes a `KSPLogger` to report unsupported key types.

## Migration plan

No consumer action beyond regenerating wrappers. Handwritten JavaScript was already calling the
exported wrapper methods, not the internal conversion functions, so the rename is not a public API
change. Kotlin/JS that previously failed to compile with multiple maps now compiles.
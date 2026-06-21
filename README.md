# KotlinToJS

A KSP processor for Kotlin Multiplatform projects with a JavaScript target.

`@JsExport` silently rejects or mangles several common Kotlin types. **Suspend functions**,
**value classes**, **`Long`**, **`List` / `Set` / `Map`**, **enums**, and **sealed classes** either
fail to compile at the export boundary or surface in JS as opaque objects that JavaScript cannot
use. Most developers hit these constraints only after they have written the Kotlin API and first try
to call it from JS.

Annotate your class with `@JsExportClass` or a standalone function with `@JsExportFunction`. The
processor generates the conversion layer. Your Kotlin code stays unchanged.

## Table Of Contents

- [Usage](#usage)
  - [What the processor generates](#what-the-processor-generates)
  - [A complete example](#a-complete-example)
  - [@JsExportClass vs @JsExport](#jsexportclass-vs-jsexport)
- [Getting Started](#getting-started)
  - [Installation](#installation)
  - [Where does generated code go?](#where-does-generated-code-go)
  - [Plugin configuration](#plugin-configuration)
  - [Full example setup](#full-example-setup)
  - [Migrating from a manual KSP setup](#migrating-from-a-manual-ksp-setup)
- [Limitations](#limitations)
  - [Collections](#collections)
  - [Long](#long)
  - [Value classes](#value-classes)
  - [Interface](#interface)
    - [Solution-Using Implementation class](#solution-using-implementation-class)
    - [Solution-Using Expect-Actual](#solution-using-expect-actual)
  - [Enum](#enum)
  - [Sealed classes](#sealed-classes)
  - [Code mangling](#code-mangling)
  - [Suspended functions](#suspended-functions)

---

## Usage

`@JsExportClass` wraps an entire class and exports every public function. `@JsExportFunction`
wraps a single function: a top-level function is collected into the shared `JsExportUtils` object,
while a function inside a non-annotated class is added to that class's own `{ClassName}Js` wrapper.

```kotlin
package com.example

import annotations.JsExportClass
import annotations.JsExportFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Wraps the entire class, all public functions are exported
@JsExportClass
class UserService {
    fun getUser(id: String): String = "user-$id"
    fun listUsers(): List<String> = listOf("alice", "bob")
    suspend fun fetchUser(id: String): String = withContext(Dispatchers.Default) { "user-$id" }
}

// Standalone top-level functions are collected into a single JsExportUtils object
@JsExportFunction
fun greet(name: String): String = "Hello, $name"
```

### What the processor generates

For `UserService`, the processor generates `UserServiceJs.kt`. `List<String>` becomes
`Array<String>`, the suspend function becomes a `Promise`, and the source class keeps no JS
annotations:

```kotlin
// build/generated/ksp/js/jsMain/kotlin/com/example/UserServiceJs.kt
package com.example

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise

@JsExport
@OptIn(ExperimentalJsExport::class)
object UserServiceJs {
    private val service: UserService = UserService()
    private val scope: CoroutineScope = MainScope()

    fun getUser(id: String): String = service.getUser(id)

    fun listUsers(): Array<String> = service.listUsers().toTypedArray()

    fun fetchUser(id: String): Promise<String> = scope.promise { service.fetchUser(id) }
}
```

All standalone `@JsExportFunction` declarations, regardless of source file, are collected into a
single `JsExportUtils` object:

```kotlin
// build/generated/ksp/js/jsMain/kotlin/JsExportUtils.kt
import com.example.greet
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@JsExport
@OptIn(ExperimentalJsExport::class)
object JsExportUtils {
    fun greet(name: String): String = greet(name)
}
```

From JavaScript:

```js
const id    = UserServiceJs.getUser("42")         // string
const users = UserServiceJs.listUsers()           // string[]
const user  = await UserServiceJs.fetchUser("42") // string, resolved from a Promise
const hello = JsExportUtils.greet("world")        // string
```

### A complete example

Every supported conversion in a single service. Requires BigInt mode for `Long` passthrough, described under [Long](#long).

```kotlin
import annotations.JsExportClass
import annotations.JsExportFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@JvmInline
value class UserId(val value: String)

@JsExportClass
class CatalogService {
    fun count(): Int = 0                                   // passthrough, native JS type
    fun tags(): List<String> = listOf("a", "b")           // List  -> Array
    fun prices(): Map<String, Int> = mapOf("apple" to 3)  // Map   -> Json via generated TypeConversion.kt
    fun total(): Long = 42L                               // Long  -> BigInt in BigInt mode
    fun owner(id: UserId): String = id.value              // value class -> underlying type
    suspend fun search(q: String): List<String> =         // suspend -> Promise
        withContext(Dispatchers.Default) { listOf(q) }
}

@JsExportFunction
fun greet(name: String): String = "Hello, $name"
```

**1. The class wrapper.** Every signature converted, `suspend` wrapped in `Promise`:

```kotlin
// build/generated/ksp/js/jsMain/kotlin/CatalogServiceJs.kt
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlintojs.generated.toJson1

@JsExport
@OptIn(ExperimentalJsExport::class)
object CatalogServiceJs {
    private val service: CatalogService = CatalogService()
    private val scope: CoroutineScope = MainScope()

    fun count(): Int = service.count()

    fun tags(): Array<String> = service.tags().toTypedArray()

    fun prices(): Json = (service.prices()).toJson1()

    fun total(): Long = service.total()

    fun owner(id: String): String = service.owner(UserId(id))

    fun search(q: String): Promise<Array<String>> = scope.promise { service.search(q).toTypedArray() }
}
```

**2. `TypeConversion.kt`.** Because a `Map` appears at a boundary, a separate file in package
`kotlintojs.generated` is generated, holding one decode/encode pair per distinct map signature. Each
pair is named after its signature so different maps never clash (Kotlin cannot overload by return
type, so a single shared `toMap` name would not compile):

```kotlin
// build/generated/ksp/js/jsMain/kotlin/kotlintojs/generated/TypeConversion.kt
package kotlintojs.generated

import kotlin.js.Json

fun Json.toMap1(): Map<String, Int> =
    js("Object.keys(this)").unsafeCast<Array<String>>().associateWith { this.asDynamic()[it] as Int }

fun Map<String, Int>.toJson1(): Json = entries.fold(js("{}")) { acc, (k, v) ->
    acc.asDynamic()[k] = v
    acc
}
```

Wrappers import only the conversion functions they reference. A second map such as `Map<Long, String>`
adds a distinct `toLongStringMap` / `longStringMapToJson` pair to the same file. Nested maps such as
`Map<String, Map<String, Int>>` additionally generate the inner pair, which the outer functions call
by name. The standalone-function file `JsExportUtils.kt` never holds map conversions.

Full behaviour of each conversion is documented in [Limitations](#limitations).

### @JsExportClass vs @JsExport

With `@JsExport`, every declaration needs `@OptIn(ExperimentalJsExport::class)` and unsupported
types require manual conversion. With this plugin, annotating with `@JsExportClass` or
`@JsExportFunction` generates the `*Js` wrapper automatically. The source class is not annotated.

|                         | `@JsExport`                                     | This plugin                                                                                |
|-------------------------|-------------------------------------------------|--------------------------------------------------------------------------------------------|
| Annotation on your code | `@JsExport @OptIn(ExperimentalJsExport::class)` | `@JsExportClass` / `@JsExportFunction`                                                     |
| Collections             | Manual conversion required                      | Automatic: `List`/`Set` → `Array`, `Map` → `Json`                                          |
| `Long`                  | Unusable opaque JS object, or precision loss    | Warning plus `Double` fallback, or opt-in native BigInt passthrough                        |
| Value classes           | Opaque wrapper exposed to JS                    | Unwrap to underlying type, or annotate the value class directly for static method wrappers |
| Suspend functions       | Manual `Promise` wrapping required              | Automatic                                                                                  |
| Code mangling           | Manual `@JsName` everywhere                     | Conflict detection plus `@JsName` passthrough                                              |

---

## Getting Started

### Installation

The plugin ships as **two artefacts**:

| Artifact                    | What it is                                                                                 | Where it goes                         |
|-----------------------------|--------------------------------------------------------------------------------------------|---------------------------------------|
| `pt.kotlintojs:annotations` | The `@JsExportClass` / `@JsExportFunction` annotations, multiplatform for jvm, js, and ios | `commonMain` or `jsMain` dependencies |
| `pt.kotlintojs:processor`   | The KSP processor that generates the wrappers, JVM only                                    | `add("kspJs", ...)`                   |

Apply `com.google.devtools.ksp` and wire both:

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
}

kotlin {
    js(IR) {
        browser()   // or nodejs
        binaries.executable()
    }
    // your other targets here, such as jvm or iosArm64

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            // Annotations are visible from common code on every target
            implementation("pt.kotlintojs:annotations:<version>")
        }
    }
}

dependencies {
    // Processor runs on the JS target only. Other targets are unaffected
    add("kspJs", "pt.kotlintojs:processor:<version>")
}
```

The `@JsExportClass` and `@JsExportFunction` annotations can be placed in `commonMain` or `jsMain`. The processor runs for the JS compilation regardless of where the annotations are placed.

### Where does generated code go?

KSP writes the generated `.kt` files to `build/generated/ksp/` and **adds that directory to the JS compilation source set**. You do not need to configure any `srcDir`.

After `./gradlew build`, find the generated wrappers at:

```
build/generated/ksp/js/jsMain/kotlin/
```

IntelliJ IDEA and Android Studio index this directory, so **Go to Declaration** and **Find Usages** navigate into generated files.

> If the IDE does not pick up the directory, add it to `jsMain`:
> ```kotlin
> kotlin {
>     sourceSets {
>         jsMain { kotlin.srcDir("build/generated/ksp/js/jsMain/kotlin") }
>     }
> }
> ```

### Plugin configuration

The `ksp { }` block is a **top-level** block in `build.gradle.kts`, outside `kotlin { }` and `dependencies { }`. Options here apply to all KSP processors in the project:

```kotlin
// build.gradle.kts, top level
ksp {
    arg("longAsBigInt", "true")   // required only when Long appears at export boundaries
}
```

### Full example setup

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
}

kotlin {
    jvm()
    js(IR) {
        browser()
        binaries.executable()
        // Required only when Long appears at export boundaries:
        compilerOptions {
            freeCompilerArgs.add("-Xes-long-as-bigint")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("pt.kotlintojs:annotations:<version>")
        }
    }
}

dependencies {
    add("kspJs", "pt.kotlintojs:processor:<version>")
}

// Required only when Long appears at export boundaries:
ksp {
    arg("longAsBigInt", "true")
}
```

### Migrating from a manual KSP setup

If you currently have a custom KSP processor generating `@JsExport` wrappers:

1. Remove the existing processor from your `add("kspJs", ...)` dependencies.
2. Add `add("kspJs", "pt.kotlintojs:processor:<version>")` instead, and `implementation("pt.kotlintojs:annotations:<version>")` to `commonMain` or `jsMain`.
3. Replace your custom annotations with `@JsExportClass` for an entire class or `@JsExportFunction` for a single method.
4. Remove manual `@JsName` disambiguation. The plugin detects overload conflicts and fails the build when `@JsName` is missing, instead of generating mangled names.

The generated output is a `{OriginalName}Js` object in the same package as the source class, written to the KSP output directory.

---

## Limitations

The following sections describe Kotlin/JS limitations when using `@JsExport` directly, and how this plugin handles each one. For more detail, see this [link](https://dev.to/touchlab/jsexport-guide-for-exposing-kotlin-to-js-20l9).

### Collections

Kotlin collections `List`, `Set`, and `Map` are not supported at `@JsExport` boundaries. Exporting them directly produces a compiler warning or error, and the resulting JS types are not usable.

**Problem with raw `@JsExport`:**

```kotlin
@JsExport // compiler error: List is not exportable
fun getItems(): List<String> = listOf("a", "b")
```

**What this plugin generates:**

| Kotlin type     | JS boundary type  | Conversion                                                                      |
|-----------------|-------------------|---------------------------------------------------------------------------------|
| `List<T>`       | `Array<T>`        | `.toTypedArray()` / `.toList()`                                                 |
| `Set<T>`        | `Array<T>`        | `.toTypedArray()` / `.toSet()`                                                  |
| `Map<K, V>`     | `Json`            | numeric ID decode/encode functions in TypeConversion.kt (e.g., toMap1, toJson1) |
| `List<List<T>>` | `Array<Array<T>>` | nested conversion                                                               |
| `Set<Long>`     | `Array<Long>`     | combined BigInt and Set conversion, needs BigInt mode                           |

```kotlin
@JsExportClass
class ItemService {
    fun getItems(): List<String> = listOf("a", "b")
    fun getMatrix(): List<List<Int>> = listOf(listOf(1, 2))
    fun getConfig(): Map<String, String> = mapOf("key" to "value")
}
```

Generated wrapper, simplified:

```kotlin
@JsExport
@OptIn(ExperimentalJsExport::class)
object ItemServiceJs {
    private val service = ItemService()

    fun getItems(): Array<String> = service.getItems().toTypedArray()
    fun getMatrix(): Array<Array<Int>> = service.getMatrix().map { elem -> elem.toTypedArray() }.toTypedArray()
    fun getConfig(): Json = (service.getConfig()).stringStringMapToJson()
}
```

For `Map` types, a decode/encode function pair assigned a unique numeric ID (here `toMap1` / `toJson1`) 
is written to `TypeConversion.kt` in package `kotlintojs.generated`, 
and the wrapper imports the ones it uses.

---

### Long

JavaScript has no native 64-bit integer type. Kotlin's `Long` compiles to a runtime class in JS that JavaScript code cannot use as a number. Converting it to `Double` loses precision for integers above 2^53. The plugin reports this instead of converting silently.

**Problem with raw `@JsExport`:**

```kotlin
@JsExport
fun getUserId(): Long = 42L // JS receives an opaque Kotlin Long object, not a number
```

**What this plugin does:**

When `Long` appears at a direct `@JsExport` boundary, the plugin emits a **build warning** and falls back to a `Double` conversion, valid for integers up to 2^53:

```
warning: Long is not supported at @JsExport boundaries without precision loss.
Enable BigInt support: add "-Xes-long-as-bigint" to your Kotlin/JS target's
freeCompilerArgs and set ksp { arg("longAsBigInt", "true") } in your build file.
```

The build still succeeds. If your values fit in 53 bits, the `Double` fallback works. For 64-bit precision, enable BigInt mode below.

**Enable BigInt support:**

Add the compiler flag and the plugin option to your `build.gradle.kts`:

```kotlin
// build.gradle.kts, inside your KMP module
kotlin {
    js(IR) {
        browser() // or nodejs
        compilerOptions {
            freeCompilerArgs.add("-Xes-long-as-bigint")
        }
    }
    // other targets such as jvm or iosArm64 are unaffected
}

// top-level, outside kotlin { }
ksp {
    arg("longAsBigInt", "true")
}
```

BigInt output requires an ES2015 target, so the JS target must compile to ES2015. With both options set, `Long` passes through the wrapper boundary unchanged and Kotlin/JS compiles it to the native JS `BigInt` type:

```kotlin
@JsExportClass
class UserService {
    fun getUserId(): Long = 42L
    fun findUser(id: Long): String = "user-$id"
}
```

Generated in BigInt mode:

```kotlin
fun getUserId(): Long = service.getUserId()
fun findUser(id: Long): String = service.findUser(id)
```

---

### Value classes

Kotlin value classes, also called inline classes, are not directly exportable to JavaScript. The class wrapper disappears at runtime and JS sees the underlying representation, but the generated TypeScript definition still refers to the value class type, which JS cannot construct.

**Problem with raw `@JsExport`:**

```kotlin
@JvmInline
value class UserId(val value: String)

@JsExport
fun getUser(id: UserId): String = "user-${id.value}"
// d.ts says `UserId`, but JS has no way to create one
```

This plugin handles value classes in two ways, depending on where the annotation is placed.

#### Scenario 1: value class as a parameter or return type

Annotate the service class that uses the value class. The plugin unwraps the value class at the JS boundary and re-wraps it before calling the service:

```kotlin
@JvmInline value class UserId(val value: String)

@JsExportClass
class UserService {
    fun getUser(id: UserId): String = "user-${id.value}"
    fun createUser(): UserId = UserId("123")
}
```

Generated:

```kotlin
fun getUser(id: String): String = service.getUser(UserId(id))
fun createUser(): String = service.createUser().value
```

#### Scenario 2: annotate the value class itself

Annotate the value class directly with `@JsExportClass`. The plugin generates a `*Js` object where each method becomes a static function that takes the underlying value as its first argument:

```kotlin
@JvmInline
@JsExportClass
value class Score(val points: Int) {
    fun doubled(): Score = Score(points * 2)
    fun label(): String = "Score: $points"
}
```

Generated:

```kotlin
@JsExport
@OptIn(ExperimentalJsExport::class)
object ScoreJs {
    fun doubled(score: Int): Int = Score(score).doubled().points
    fun label(score: Int): String = Score(score).label()
}
```

The first parameter is named after the class, lower-camel-cased. JS callers invoke `ScoreJs.doubled(10)` without constructing a Kotlin `Score` object.

Type conversions on the underlying property compose in both scenarios. A `value class BigId(val id: Long)` in BigInt mode exposes `Long` at the boundary. A `value class Price(val cents: Int)` exposes `Int`.

> **Known limitation:** value classes used as element types inside `List<MyValueClass>` or
> `Set<MyValueClass>` are not yet unwrapped by the collection handler. Only direct parameter
> and return type positions are supported in this version.

---

### Interface

Kotlin interfaces cannot be exported with `@JsExport`. The generated JS does not expose the interface in a usable form, and implementations cannot be used from JS.

#### Solution-Using Implementation class

Annotate the implementation class instead of the interface. The plugin generates a wrapper for the implementation.

```kotlin
interface Repository {
    fun findById(id: String): String
}

@JsExportClass // annotate the implementation, not the interface
class UserRepository : Repository {
    override fun findById(id: String): String = "user-$id"
}
```

The generated `UserRepositoryJs` wraps `UserRepository`, with type conversions applied.

#### Solution-Using Expect-Actual

In a KMP project, you can declare the interface in `commonMain` and provide a JS-specific implementation in `jsMain` annotated with `@JsExportClass`:

```kotlin
// commonMain
interface AnalyticsService {
    fun track(event: String, properties: Map<String, String>)
}

// jsMain
@JsExportClass
actual class AnalyticsServiceImpl : AnalyticsService {
    actual override fun track(event: String, properties: Map<String, String>) {
        // JS-specific implementation
    }
}
```

The plugin processes the `jsMain` actual class and generates the wrapper with the `Map → Json` conversion.

---

### Enum

Kotlin enums exported with `@JsExport` produce JavaScript objects with internal fields such as `$ordinal`, `$name`, and companion object entries that JS cannot consume directly.

```kotlin
@JsExport
enum class Status { ACTIVE, INACTIVE } // JS receives an opaque enum class, not a string or number
```

**Workaround with this plugin:**

Annotate a wrapper class that exposes the enum values as strings:

```kotlin
enum class Status { ACTIVE, INACTIVE }

@JsExportClass
class StatusService {
    fun getStatus(): String = Status.ACTIVE.name
    fun isActive(status: String): Boolean = Status.valueOf(status) == Status.ACTIVE
}
```

The plugin wraps `StatusService` and exposes the enum as strings.

> Full enum support, auto-generating string and ordinal accessors, is planned for a future release.

---

### Sealed classes

Sealed classes have the same problem as enums when exported directly. The generated JS type hierarchy requires knowledge of Kotlin's internal class naming.

```kotlin
@JsExport
sealed class Result {
    class Success(val value: String) : Result()
    class Failure(val error: String) : Result()
}
// JS consumers must navigate Kotlin-generated class names like Result.Success
```

**Workaround with this plugin:**

Wrap the sealed class in a service that returns discriminated values:

```kotlin
sealed class Result {
    data class Success(val value: String) : Result()
    data class Failure(val error: String) : Result()
}

@JsExportClass
class ResultService {
    fun fetchData(): String = when (val r = loadResult()) {
        is Result.Success -> r.value
        is Result.Failure -> throw IllegalStateException(r.error)
    }

    private fun loadResult(): Result = Result.Success("payload")
}
```

Alternatively, expose a discriminated union using `Json`:

```kotlin
@JsExportClass
class ResultService {
    fun fetchData(): Map<String, String> = when (val r = loadResult()) {
        is Result.Success -> mapOf("type" to "success", "value" to r.value)
        is Result.Failure -> mapOf("type" to "failure", "error" to r.error)
    }

    private fun loadResult(): Result = Result.Success("payload")
}
```

> Native sealed class support, auto-generating union-type wrappers, is planned for a future release.

---

### Code mangling

Kotlin mangles function names to support overloading and special characters. An overloaded `process(String)` / `process(Int)` appears in JS with compiler-generated names like `process_za3rmp$`, which breaks the JS API contract.

**Problem:**

```kotlin
@JsExport
class Service {
    fun process(value: String): String = value  
    fun process(value: Int): Int = value        
}
```

**What this plugin does:**

- If a function has `@JsName("name")`, that name is used as the exported JS name.
- If multiple overloads exist without `@JsName`, the plugin emits a **compile error** instead of generating mangled wrappers.

```kotlin
@JsExportClass
class Service {
    @JsName("processString")
    fun process(value: String): String = value  // exported as processString

    @JsName("processInt")
    fun process(value: Int): Int = value        // exported as processInt
}
```

If you forget `@JsName` on any overload, the build fails with:

```
Kotlin/JS name mangling conflict: Overloaded function 'process' must be annotated with @JsName("uniqueName").
```

---

### Suspended functions

Kotlin suspend functions cannot be called from JavaScript. They require a coroutine context that JS does not have. They must be wrapped in `Promise` for JS `async/await` or `.then`.

**Problem:**

```kotlin
import kotlinx.coroutines.delay

// A plain suspend export is not callable from JS
@JsExport
suspend fun fetchUser(id: Long): String {
    delay(10)
    return "user-$id"
}
```

**What this plugin generates:**

Suspend functions are wrapped in `scope.promise { }` using a `MainScope`. The return type becomes `Promise<T>`, with the other type conversions for collections and BigInt applied inside the promise body.

```kotlin
@JsExportClass
class UserService {
    suspend fun fetchUser(id: String): String = withContext(Dispatchers.Default) { "user-$id" }
    suspend fun listUsers(): List<String> = withContext(Dispatchers.Default) { listOf("alice") }
}
```

Generated:

```kotlin
@JsExport
@OptIn(ExperimentalJsExport::class)
object UserServiceJs {
    private val service = UserService()
    private val scope: CoroutineScope = MainScope()

    fun fetchUser(id: String): Promise<String> =
        scope.promise { service.fetchUser(id) }

    fun listUsers(): Promise<Array<String>> =
        scope.promise { service.listUsers().toTypedArray() }
}
```

From JavaScript:

```js
const users = await UserServiceJs.listUsers()  // string[]
const user  = await UserServiceJs.fetchUser(42) // string
```

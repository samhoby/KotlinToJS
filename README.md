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
  - [The example we build on](#the-example-we-build-on)
  - [What the processor generates](#what-the-processor-generates)
  - [Consuming it from JavaScript](#consuming-it-from-javascript)
  - [Custom type replacement](#custom-type-replacement)
  - [@JsExportClass vs @JsExport](#jsexportclass-vs-jsexport)
- [Getting Started](#getting-started)
  - [Installation](#installation)
  - [Where does generated code go?](#where-does-generated-code-go)
  - [Full example setup](#full-example-setup)
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

Every example in this document is one slice of the same class: `UserAPI`, a Kotlin Multiplatform
HTTP client. It lives in `commonMain`, is constructed with a shared `httpClient`, runs every call
as a `suspend` function, and returns `Either<ProblemDetail, T>` so callers get a typed error
instead of an exception. Android and iOS consume it directly; this plugin generates a JS-callable
wrapper on top without touching the source.

`@JsExportClass` wraps an entire class and exports every public function. `@JsExportFunction`
wraps a single function: a top-level function is collected into the shared `JsExportUtils` object,
while a function inside a non-annotated class is added to that class's own `{ClassName}Js` wrapper.
`@JsExportReplacement` declares a JS-friendly stand-in type for a Kotlin type that cannot cross the
`@JsExport` boundary cleanly (such as a sealed class or a generic union), paired with a
`@JsExportConverter` function that performs the conversion.

### The example we build on

These are the domain types and the client the rest of the document refers back to:

```kotlin
import kotlinx.coroutines.delay
import io.github.samhoby.kotlintojs.annotations.JsExportClass
import io.github.samhoby.kotlintojs.annotations.JsExportFunction

/** A user as returned by the backend. */
data class UserOutputModel(val id: Long, val name: String)

/** RFC 7807 style error payload returned when a call fails. */
data class ProblemDetail(val status: Int, val title: String)

/** Typed success or failure, returned by every API call. */
sealed class Either<out E, out T> {
    data class Left<E>(val value: E) : Either<E, Nothing>()
    data class Right<T>(val value: T) : Either<Nothing, T>()
}

/** Shared HTTP client injected into every API class. */
class SharedHttpClient {
    /** Runs [block] and wraps its result, so failures never cross the boundary as exceptions. */
    suspend fun <T> safeGet(block: suspend () -> T): Either<ProblemDetail, T> {
        delay(10)
        return Either.Right(block())
    }
}

@JsExportClass
class UserAPI(private val httpClient: SharedHttpClient) {
    /** Returns a single user by their unique [id]. */
    suspend fun getUserById(id: Long): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(id, "Alice") }

    /** Returns the currently authenticated user. */
    suspend fun getCurrentUser(): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(1L, "Alice") }

    /** Permanently deletes the authenticated user's account. */
    suspend fun deleteCurrentUser(): Either<ProblemDetail, Unit> =
        httpClient.safeGet { }

    // Collections are returned directly, not wrapped in Either: a replacement type
    // carries its type arguments over verbatim, so it must wrap an already-exportable
    // type (see "Custom type replacement"). List and Map are converted at the boundary.

    /** Returns the names of all registered users. */
    suspend fun getUserNames(): List<String> {
        delay(10)
        return listOf("Alice", "Bob")
    }

    /** Maps each topic id to whether the current user is an expert in it. */
    suspend fun getExpertiseFlags(): Map<String, Boolean> {
        delay(10)
        return mapOf("1" to true, "2" to false)
    }
}

/** Standalone top-level functions are collected into a single JsExportUtils object. */
@JsExportFunction
fun apiVersion(): String = "1.0.0"
```

### What the processor generates

For `UserAPI`, the processor generates `UserAPIJs.kt`. Every `suspend` becomes a `Promise`,
`Either` becomes `JsEither` (see [Custom type replacement](#custom-type-replacement)), `List`
becomes `Array`, `Map` becomes `Json`, and the source class keeps no JS annotations:

```kotlin
// build/generated/ksp/js/jsMain/kotlin/UserAPIJs.kt
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlintojs.generated.toJson1

@JsExport
@OptIn(ExperimentalJsExport::class)
object UserAPIJs {
    private val service: UserAPI = UserAPI(httpClient = SharedHttpClient())
    private val scope: CoroutineScope = MainScope()

    fun getUserById(id: Long): Promise<JsEither<ProblemDetail, UserOutputModel>> =
        scope.promise { JsEither.fromEither(service.getUserById(id)) }

    fun getCurrentUser(): Promise<JsEither<ProblemDetail, UserOutputModel>> =
        scope.promise { JsEither.fromEither(service.getCurrentUser()) }

    fun deleteCurrentUser(): Promise<JsEither<ProblemDetail, Unit>> =
        scope.promise { JsEither.fromEither(service.deleteCurrentUser()) }

    fun getUserNames(): Promise<Array<String>> =
        scope.promise { service.getUserNames().toTypedArray() }

    fun getExpertiseFlags(): Promise<Json> =
        scope.promise { service.getExpertiseFlags().toJson1() }
}
```

> **How the service is constructed.** The generated wrapper is a parameterless `@JsExport object`,
> so it cannot receive `UserAPI`'s dependencies from JS. Instead, it builds the service eagerly,
> instantiating each required constructor parameter with that type's own no-argument constructor, 
> here `UserAPI(httpClient = SharedHttpClient())`. Parameters that declare a default value are
> omitted so their default applies. This requires every mandatory dependency to be constructible
> with no arguments (directly or through its own defaults).

Because `getExpertiseFlags` returns `Map<String, Boolean>`, a decode/encode pair is generated in a
separate `TypeConversion.kt`. Each pair is named after its signature so different map types never
clash (Kotlin cannot overload by return type, so a single shared `toMap` name would not compile):

```kotlin
// build/generated/ksp/js/jsMain/kotlin/kotlintojs/generated/TypeConversion.kt
package kotlintojs.generated

import kotlin.js.Json

fun Json.toMap1(): Map<String, Boolean> =
    js("Object.keys(this)").unsafeCast<Array<String>>().associateWith { this.asDynamic()[it] as Boolean }

fun Map<String, Boolean>.toJson1(): Json = entries.fold(js("{}")) { acc, (k, v) ->
    acc.asDynamic()[k] = v
    acc
}
```

The wrapper imports only the conversion functions it references. Standalone `@JsExportFunction`
declarations, regardless of source file, are collected into a single `JsExportUtils` object:

```kotlin
// build/generated/ksp/js/jsMain/kotlin/JsExportUtils.kt
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@JsExport
@OptIn(ExperimentalJsExport::class)
object JsExportUtils {
    fun apiVersion(): String = apiVersion()
}
```

### Consuming it from JavaScript

`Long` parameters are passed as `BigInt` literals, `Promise`s are awaited, and `JsEither` exposes a
flat `success` / `data` / `error` shape:

```js
const res = await UserAPIJs.getUserById(1n)
if (res.success) {
    console.log(res.data)    // UserOutputModel
} else {
    console.error(res.error) // ProblemDetail
}

const names = await UserAPIJs.getUserNames()       // string[]
const flags = await UserAPIJs.getExpertiseFlags()  // { "1": true, "2": false }
const ver   = JsExportUtils.apiVersion()           // "1.0.0"
```

Full behaviour of each conversion is documented in [Limitations](#limitations).

### Custom type replacement

`Either<E, T>` is a sealed class, and sealed classes do not cross the `@JsExport`
boundary cleanly: TypeScript would receive Kotlin's mangled subclass names instead of a usable
discriminated union. `@JsExportReplacement` solves this by declaring a flat, JS-friendly stand-in
class, and `@JsExportConverter` marks the companion function that converts from the original type.
Declared once, it is applied automatically wherever `Either` appears as a return type.

```kotlin
import io.github.samhoby.kotlintojs.annotations.JsExportConverter
import io.github.samhoby.kotlintojs.annotations.JsExportReplacement

@JsExport
@JsExportReplacement(replaces = Either::class)
class JsEither<out E, out T>(
    val success: Boolean,
    val data: T? = null,
    val error: E? = null,
) {
    companion object {
        @JsExportConverter
        @JsExport.Ignore
        fun <E, T> fromEither(either: Either<E, T>): JsEither<E, T> = when (either) {
            is Either.Left -> JsEither(success = false, error = either.value)
            is Either.Right -> JsEither(success = true, data = either.value)
        }
    }
}
```

With `JsEither` in place, `UserAPI.getUserById()` which returns
`Either<ProblemDetail, UserOutputModel>` generates:

```kotlin
fun getUserById(id: Long): Promise<JsEither<ProblemDetail, UserOutputModel>> =
    scope.promise { JsEither.fromEither(service.getUserById(id)) }
```

The converter is never exposed to JS (`@JsExport.Ignore`): it runs on the Kotlin side inside the
generated wrapper.

> **Type arguments are carried over verbatim.** The converter is generic
> (`fun <E, T> fromEither(...): JsEither<E, T>`), so it cannot transform the value it wraps. The
> replacement reuses the original type arguments as-is, so `Either<ProblemDetail, UserOutputModel>`
> becomes `JsEither<ProblemDetail, UserOutputModel>` without converting them. A replacement must
> therefore wrap an already-exportable type.
>
> If a type argument needs its own conversion at the boundary, the build fails with a clear error
> rather than producing a broken wrapper. This covers `List`, `Set`, `Map`, value classes, and
> `Long` without BigInt mode. For example `Either<ProblemDetail, Long>` without `-Xes-long-as-bigint`
> is rejected: the converter would hand JavaScript an opaque `Long` it cannot read. In BigInt mode
> `Long` passes through natively, so `Either<ProblemDetail, Long>` is allowed.
>
> Return collections and maps directly (as `getUserNames` and `getExpertiseFlags` do) so the
> collection handler converts them, rather than nesting them inside a replacement.

**Living in `commonMain`.** Both `@JsExportClass` and `@JsExportReplacement` have `SOURCE`
retention, so they are erased after compilation and cost nothing at runtime on any target. The KSP
processor is wired to `kspJs` only, so it runs exclusively during the JS compilation. Android and
iOS compile the exact same `UserAPI` source with no generated code:

```
commonMain/
  UserAPI.kt     ← @JsExportClass, returns Either<ProblemDetail, T>
  JsEither.kt    ← @JsExportReplacement(replaces = Either::class)

JS compilation   → UserAPIJs.kt generated (wrapper + type conversions)
Android / iOS    → UserAPI compiled as-is, no wrapper, no processor output
```

The same API class that Android and iOS consume directly is the one that becomes JS-callable, with
no separate JS-specific source to maintain.

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
| Sealed / union types    | Mangled subclass names, unusable from TS        | Declare a JS-friendly stand-in with `@JsExportReplacement` + `@JsExportConverter`          |

---

## Getting Started

### Installation

Apply the `io.github.samhoby.kotlintojs` plugin. It automatically applies KSP and wires the annotations and processor:

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.1.0"
    id("io.github.samhoby.kotlintojs") version "0.1.0"
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
        }
    }
}
```

> The plugin pulls in the matching `annotations` and `processor` artifacts for you and wires the
> processor to `kspJs`. You do not add them to your dependencies by hand. They resolve from Maven
> Central, so keep `mavenCentral()` in your repositories.

The plugin ships as **two artefacts**:

| Artifact                    | What it is                                                                                 | Where it goes                         |
|-----------------------------|--------------------------------------------------------------------------------------------|---------------------------------------|
| `io.github.samhoby:annotations` | The `@JsExportClass` / `@JsExportFunction` annotations, multiplatform for jvm, js, and ios | `commonMain` or `jsMain` dependencies |
| `io.github.samhoby:processor`   | The KSP processor that generates the wrappers, JVM only                                    | `add("kspJs", ...)`                   |

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

### Full example setup

**With the Gradle Plugin:**

```kotlin
// build.gradle.kts
plugins {
  kotlin("multiplatform") version "2.1.0"
  id("io.github.samhoby.kotlintojs") version "0.1.0"
}

kotlin {
  js(IR) {
    browser() // or nodejs
    binaries.executable()

    // Optional: Required only if you expose `Long` and want native JS BigInt
    compilerOptions { freeCompilerArgs.add("-Xes-long-as-bigint") }
  }
}
```

---

## Limitations

The following sections describe Kotlin/JS limitations when using `@JsExport` directly, and how this plugin handles each one. Each draws on the `UserAPI` from [Usage](#the-example-we-build-on). For more detail, see this [link](https://dev.to/touchlab/jsexport-guide-for-exposing-kotlin-to-js-20l9).

### Collections

`getUserNames()` returns `List<String>` and `getExpertiseFlags()` returns `Map<String, Boolean>`.
Kotlin collections are not supported at `@JsExport` boundaries, exporting them directly produces a
compiler error.

**Problem with raw `@JsExport`:**

```kotlin
@JsExport // compiler error: List is not exportable
fun getUserNames(): List<String> = listOf("Alice", "Bob")
```

**What this plugin generates:**

| Kotlin type     | JS boundary type  | Conversion                                                                       |
|-----------------|-------------------|----------------------------------------------------------------------------------|
| `List<T>`       | `Array<T>`        | `.toTypedArray()` / `.toList()`                                                  |
| `Set<T>`        | `Array<T>`        | `.toTypedArray()` / `.toSet()`                                                   |
| `Map<K, V>`     | `Json`            | numeric ID decode/encode functions in TypeConversion.kt (e.g., toMap1, toJson1)  |
| `List<List<T>>` | `Array<Array<T>>` | nested conversion                                                                |
| `Set<Long>`     | `Array<Long>`     | combined BigInt and Set conversion, needs BigInt mode                            |

Each `UserAPI` collection return is converted at the boundary:

```kotlin
fun getUserNames(): Promise<Array<String>> =
    scope.promise { service.getUserNames().toTypedArray() }

fun getExpertiseFlags(): Promise<Json> =
    scope.promise { service.getExpertiseFlags().toJson1() }
```

A nested `List<List<UserOutputModel>>` would convert recursively to `Array<Array<UserOutputModel>>`
via `.map { it.toTypedArray() }.toTypedArray()`. For `Map` types, the decode/encode pair (here
`toMap1` / `toJson1`) is written to `TypeConversion.kt` in package `kotlintojs.generated`, and the
wrapper imports the ones it uses. Collections are returned directly rather than wrapped in `Either`,
because a replacement type carries its arguments over unconverted (see
[Custom type replacement](#custom-type-replacement)).

A JS `Json` object only has string keys, so map keys are decoded back to the declared Kotlin type.
Supported key types are `String`, `Int`, `Long`, `Short`, `Byte`, `Float`, `Double`, and `Boolean`.
Any other key type fails the build with an error rather than producing a wrapper that loses the key.

---

### Long

`getUserById(id: Long)` takes a `Long` parameter. JavaScript has no native 64-bit integer type:
Kotlin's `Long` compiles to a runtime class in JS that cannot be used as a number, and converting it
to `Double` loses precision above 2^53. The plugin reports this instead of converting silently.

To isolate the behaviour, suppose `UserAPI` also exposes a raw count:

```kotlin
/** Total number of registered users. */
suspend fun getUserCount(): Long {
    delay(10)
    return 42L
}
```

**What this plugin does:**

When `Long` appears at a direct `@JsExport` boundary, the plugin emits a **build warning** and falls back to a `Double` conversion, valid for integers up to 2^53:

```
warning: Long is not supported at @JsExport boundaries without precision loss.
Enable BigInt support: add "-Xes-long-as-bigint" to your Kotlin/JS target's
freeCompilerArgs and set ksp { arg("longAsBigInt", "true") } in your build file.
```

```kotlin
// Default mode: Double fallback
fun getUserCount(): Promise<Double> = scope.promise { service.getUserCount().toDouble() }
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
```

BigInt output requires an ES2015 target. With both options set, `Long` passes through unchanged and
Kotlin/JS compiles it to the native JS `BigInt` type, both the `getUserCount` return and the
`getUserById(id: Long)` parameter:

```kotlin
// BigInt mode: Long passes through
fun getUserCount(): Promise<Long> = scope.promise { service.getUserCount() }

fun getUserById(id: Long): Promise<JsEither<ProblemDetail, UserOutputModel>> =
    scope.promise { JsEither.fromEither(service.getUserById(id)) }
```

---

### Value classes

Kotlin value classes are not directly exportable to JavaScript. The class wrapper disappears at
runtime and JS sees the underlying representation, but the generated TypeScript definition still
refers to the value class type, which JS cannot construct.

Suppose `UserAPI` identified users with a `UserId` value class instead of a raw `Long`:

```kotlin
@JvmInline
value class UserId(val value: Long)
```

**Problem with raw `@JsExport`:**

```kotlin
@JsExport
fun getUser(id: UserId): UserOutputModel = UserOutputModel(id.value, "Alice")
// d.ts says `UserId`, but JS has no way to create one
```

This plugin handles value classes in two ways, depending on where the annotation is placed.

#### Scenario 1: value class as a parameter or return type

Annotate the class that uses the value class. The plugin unwraps it at the JS boundary and
re-wraps it before calling through:

```kotlin
@JsExportClass
class UserAPI(private val httpClient: SharedHttpClient) {
    /** Returns a single user by their typed [id]. */
    suspend fun getUser(id: UserId): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(id.value, "Alice") }
}
```

Generated `UserId` is exposed as its underlying `Long` and re-wrapped before the call:

```kotlin
fun getUser(id: Long): Promise<JsEither<ProblemDetail, UserOutputModel>> =
    scope.promise { JsEither.fromEither(service.getUser(UserId(id))) }
```

#### Scenario 2: annotate the value class itself

Annotate the value class directly with `@JsExportClass`. The plugin generates a `*Js` object where
each method becomes a static function that takes the underlying value as its first argument:

```kotlin
@JvmInline
@JsExportClass
value class UserId(val value: Long) {
    /** Whether this id is the reserved admin account. */
    fun isAdmin(): Boolean = value == 1L
}
```

Generated:

```kotlin
@JsExport
@OptIn(ExperimentalJsExport::class)
object UserIdJs {
    fun isAdmin(userId: Long): Boolean = UserId(userId).isAdmin()
}
```

The first parameter is named after the class, lower-camel-cased. JS callers invoke
`UserIdJs.isAdmin(1n)` without constructing a Kotlin `UserId`.

Type conversions on the underlying property compose in both scenarios. `UserId(val value: Long)` in
BigInt mode exposes `Long` at the boundary; a `value class Price(val cents: Int)` would expose `Int`.

> **Known limitation:** value classes used as element types inside `List<MyValueClass>` or
> `Set<MyValueClass>` are not yet unwrapped by the collection handler. Only direct parameter
> and return type positions are supported in this version.

---

### Interface

Kotlin interfaces cannot be exported with `@JsExport`. The generated JS does not expose the
interface in a usable form, and implementations cannot be used from JS.

#### Solution-Using Implementation class

Annotate the implementation class instead of the interface. Extract `UserAPI`'s contract into an
interface and annotate the concrete class:

```kotlin
interface IUserAPI {
    suspend fun getUserById(id: Long): Either<ProblemDetail, UserOutputModel>
    suspend fun getCurrentUser(): Either<ProblemDetail, UserOutputModel>
}

@JsExportClass // annotate the implementation, not the interface
class UserAPI(private val httpClient: SharedHttpClient) : IUserAPI {
    override suspend fun getUserById(id: Long): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(id, "Alice") }

    override suspend fun getCurrentUser(): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(1L, "Alice") }
}
```

The generated `UserAPIJs` wraps `UserAPI`, with type conversions applied.

#### Solution-Using Expect-Actual

In a KMP project, declare the contract in `commonMain` and provide a JS-specific implementation in
`jsMain` annotated with `@JsExportClass`:

```kotlin
// commonMain
interface IUserAPI {
    suspend fun getUserNames(): List<String>
}

// jsMain
@JsExportClass
class JsUserAPI(private val httpClient: SharedHttpClient) : IUserAPI {
    override suspend fun getUserNames(): List<String> {
        delay(10)
        return listOf("Alice", "Bob")
    }
}
```

The plugin processes the `jsMain` class and generates the wrapper with the `List → Array` conversion.

---

### Enum

Kotlin enums exported with `@JsExport` produce JavaScript objects with internal fields such as
`$ordinal`, `$name`, and companion object entries that JS cannot consume directly.

Suppose `UserOutputModel` carried a role:

```kotlin
enum class UserRole { ADMIN, USER, GUEST }

@JsExport
data class UserOutputModel(val id: Long, val name: String, val role: UserRole)
// JS receives an opaque enum for `role`, not a string
```

**Workaround with this plugin:**

Expose the enum as a `String` in the value `UserAPI` returns:

```kotlin
@JsExportClass
class UserAPI(private val httpClient: SharedHttpClient) {
    /** Returns the role name of the user [id], as a plain string. */
    suspend fun getUserRole(id: Long): Either<ProblemDetail, String> =
        httpClient.safeGet { UserRole.ADMIN.name }
}
```

JS receives `"ADMIN"`. To go the other way, accept the string and resolve it with
`UserRole.valueOf(name)` inside the function.

> Full enum support, auto-generating string and ordinal accessors, is planned for a future release.

---

### Sealed classes

`Either<ProblemDetail, T>` returned by every `UserAPI` method, is itself a sealed class.
Exporting it directly with `@JsExport` produces unusable JS:

```kotlin
@JsExport
sealed class Either<out E, out T> {
    data class Left<E>(val value: E) : Either<E, Nothing>()
    data class Right<T>(val value: T) : Either<Nothing, T>()
}
// JS consumers must navigate Kotlin-generated class names like Either.Left
```

**Using `@JsExportReplacement` (recommended):**

This is exactly the pattern `UserAPI` relies on. Declare `JsEither` once with `@JsExportReplacement`
and a `@JsExportConverter`, and every `Either` return is converted automatically:

```kotlin
@JsExport
@JsExportReplacement(replaces = Either::class)
class JsEither<out E, out T>(
    val success: Boolean,
    val data: T? = null,
    val error: E? = null,
) {
    companion object {
        @JsExportConverter
        @JsExport.Ignore
        fun <E, T> fromEither(either: Either<E, T>): JsEither<E, T> = when (either) {
            is Either.Left -> JsEither(success = false, error = either.value)
            is Either.Right -> JsEither(success = true, data = either.value)
        }
    }
}
```

`UserAPI.getUserById()` then generates:

```kotlin
fun getUserById(id: Long): Promise<JsEither<ProblemDetail, UserOutputModel>> =
    scope.promise { JsEither.fromEither(service.getUserById(id)) }
```

See [Custom type replacement](#custom-type-replacement) for the full explanation, including the
verbatim handling of type arguments and `commonMain` usage.

**Alternative: return a primitive directly.** If you do not need the failure branch in JS, collapse
it inside the function:

```kotlin
@JsExportClass
class UserAPI(private val httpClient: SharedHttpClient) {
    /** Returns the user's name, throwing if the call failed. */
    suspend fun getUserName(id: Long): String =
        when (val result = getUserById(id)) {
            is Either.Right -> result.value.name
            is Either.Left -> throw RuntimeException(result.value.title)
        }

    suspend fun getUserById(id: Long): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(id, "Alice") }
}
```

---

### Code mangling

Kotlin mangles function names to support overloading. If `UserAPI` declares two overloaded `search`
methods, they appear in JS with compiler-generated names that break the API contract.

**Problem:**

```kotlin
@JsExport
class UserAPI {
    suspend fun search(name: String): UserOutputModel = UserOutputModel(1L, name)
    suspend fun search(id: Long): UserOutputModel = UserOutputModel(id, "Alice")
    // JS sees search_za3rmp$ and search_6ic1pp$ instead of readable names
}
```

**What this plugin does:**

- If a function has `@JsName("name")`, that name is used as the exported JS name.
- If multiple overloads exist without `@JsName`, the plugin emits a **compile error** instead of generating mangled wrappers.

```kotlin
@JsExportClass
class UserAPI(private val httpClient: SharedHttpClient) {
    @JsName("searchByName")
    suspend fun search(name: String): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(1L, name) }

    @JsName("searchById")
    suspend fun search(id: Long): Either<ProblemDetail, UserOutputModel> =
        httpClient.safeGet { UserOutputModel(id, "Alice") }
}
```

If you forget `@JsName` on any overload, the build fails with:

```
Kotlin/JS name mangling conflict: Overloaded function 'search' must be annotated with @JsName("uniqueName").
```

The names you choose must also be unique. If two overloads pick the same `@JsName`, for example both
`search` functions annotated `@JsName("search")`, the build fails the same way: the conflict moves
from the mangled names to the names you supplied, so each overload still needs a distinct one.

---

### Suspended functions

Every `UserAPI` method is `suspend`. Kotlin suspend functions cannot be called from JavaScript: they
require a coroutine context that JS does not have. They must be wrapped in `Promise` for JS
`async/await` or `.then`.

**Problem:**

```kotlin
import kotlinx.coroutines.delay

// A plain suspend export is not callable from JS
@JsExport
suspend fun getUserNames(): List<String> {
    delay(10)
    return listOf("Alice", "Bob")
}
```

**What this plugin generates:**

Suspend functions are wrapped in `scope.promise { }` using a `MainScope`. The return type becomes
`Promise<T>`, with every other conversion (collections, `Either`, `Map`) applied inside the
promise body:

```kotlin
@JsExport
@OptIn(ExperimentalJsExport::class)
object UserAPIJs {
    private val service: UserAPI = UserAPI(httpClient = SharedHttpClient())
    private val scope: CoroutineScope = MainScope()

    fun getUserById(id: Long): Promise<JsEither<ProblemDetail, UserOutputModel>> =
        scope.promise { JsEither.fromEither(service.getUserById(id)) }

    fun getUserNames(): Promise<Array<String>> =
        scope.promise { service.getUserNames().toTypedArray() }
}
```

From JavaScript:

```js
const user  = await UserAPIJs.getUserById(1n)  // JsEither<ProblemDetail, UserOutputModel>
const names = await UserAPIJs.getUserNames()   // string[]
```

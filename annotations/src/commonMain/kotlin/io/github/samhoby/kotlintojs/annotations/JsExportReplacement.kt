package io.github.samhoby.kotlintojs.annotations

import kotlin.reflect.KClass

/**
 * Declares that the annotated `@JsExport` type is the JS-facing replacement for [replaces].
 *
 * Some Kotlin types do not cross the `@JsExport` boundary cleanly — notably generic `sealed`
 * hierarchies, which TypeScript can only consume through Kotlin's mangled subclass names. The
 * usual fix is a flat, JS-friendly stand-in (e.g. a discriminated-union class) plus a converter.
 * This annotation tells the KSP processor about that pairing so the conversion is applied
 * automatically wherever a wrapped function returns [replaces].
 *
 * The converter itself is identified by symbol, not by name: declare a function in this type's
 * companion object that takes [replaces] and returns this type, and mark it with
 * [JsExportConverter]. The processor emits `Type.converter(value)` and types the return as this
 * class, carrying over the original type arguments.
 *
 * ```kotlin
 * @JsExport
 * @JsExportReplacement(replaces = Either::class)
 * class JsEither<out E, out T>(val success: Boolean, val data: T? = null, val error: E? = null) {
 *     companion object {
 *         @JsExportConverter
 *         @JsExport.Ignore
 *         fun <E, T> fromEither(either: Either<E, T>): JsEither<E, T> = /* ... */
 *     }
 * }
 *
 * // a wrapped `suspend fun login(): Either<ProblemDetail, AuthResponse>` then generates:
 * //   fun login(): Promise<JsEither<ProblemDetail, AuthResponse>> =
 * //       scope.promise { JsEither.fromEither(service.login()) }
 * ```
 *
 * @property replaces The Kotlin type this stand-in replaces at the JS boundary.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class JsExportReplacement(
    val replaces: KClass<*>,
)

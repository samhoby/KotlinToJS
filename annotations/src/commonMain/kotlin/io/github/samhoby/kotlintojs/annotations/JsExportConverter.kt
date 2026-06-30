package io.github.samhoby.kotlintojs.annotations

/**
 * Marks the function that converts a [JsExportReplacement]'s replaced type into the JS-facing type.
 *
 * Declare it in the replacement type's companion object, taking the replaced type and returning the
 * replacement (e.g. `fun <E, T> fromEither(either: Either<E, T>): JsEither<E, T>`). The processor
 * locates it by this marker — there is no name string to keep in sync — and emits
 * `Type.<function>(value)` in the generated wrapper.
 *
 * The function runs on the Kotlin side inside the generated wrapper, never from JS, so it should
 * also carry `@JsExport.Ignore` to stay out of the exported surface.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class JsExportConverter

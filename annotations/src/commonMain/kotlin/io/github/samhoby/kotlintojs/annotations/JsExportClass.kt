package io.github.samhoby.kotlintojs.annotations

/**
 * Marks a class for JS export wrapper generation.
 *
 * The KSP processor generates a `{ClassName}Js` object that wraps all public functions of the
 * annotated class, converting unsupported boundary types (`Long`, `List`, `Set`, `Map`, suspend)
 * to their JS-compatible equivalents automatically.
 *
 * ```kotlin
 * @JsExportClass
 * class UserService {
 *     fun listUsers(): List<String> = listOf("alice", "bob")
 * }
 * // generates: object UserServiceJs { fun listUsers(): Array<String> }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class JsExportClass

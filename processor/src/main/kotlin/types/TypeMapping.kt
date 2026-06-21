package types

import com.squareup.kotlinpoet.TypeName

/**
 * Describes how a Kotlin type maps to its JS-compatible equivalent at an `@JsExport` boundary.
 *
 * Each handler produces a [TypeMapping] that captures three things:
 * - [jsTypeName]: the [TypeName] to use in the generated wrapper's signature.
 * - [toKotlin]: a lambda that wraps a JS-side expression to convert it into the Kotlin type
 *   (e.g. `"(id).toLong()"`).
 * - [toJs]: a lambda that wraps a Kotlin-side expression to convert it into the JS type
 *   (e.g. `"(id).toDouble()"`).
 *
 * Both conversions are named after their destination: [toKotlin] produces a Kotlin value (used for
 * parameters coming from JS), [toJs] produces a JS value (used for return values going to JS).
 *
 * Types with a native JS equivalent (e.g. `String`, `Int`) use the default identity lambdas.
 *
 * Some conversions reference generated top-level functions that must be imported. [importsForToKotlin]
 * lists the `kotlintojs.generated` function names used by [toKotlin], and [importsForToJs] those used
 * by [toJs]. A wrapper imports only the side it actually emits, so a map used purely as a parameter
 * does not import its encode function and vice versa.
 */
data class TypeMapping(
    /** The type exposed at the `@JsExport` boundary in the generated wrapper. */
    val jsTypeName: TypeName,
    /** Converts a JS-side expression string to the corresponding Kotlin type. */
    val toKotlin: (String) -> String = { name -> name },
    /** Converts a Kotlin-side expression string to the corresponding JS type. */
    val toJs: (String) -> String = { name -> name },
    /** `kotlintojs.generated` function names that [toKotlin] references and the wrapper must import. */
    val importsForToKotlin: List<String> = emptyList(),
    /** `kotlintojs.generated` function names that [toJs] references and the wrapper must import. */
    val importsForToJs: List<String> = emptyList(),
)

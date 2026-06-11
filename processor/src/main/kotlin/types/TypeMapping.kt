package types

import com.squareup.kotlinpoet.TypeName

/**
 * Describes how a Kotlin type maps to its JS-compatible equivalent at an `@JsExport` boundary.
 *
 * Each handler produces a [TypeMapping] that captures three things:
 * - [jsTypeName]: the [TypeName] to use in the generated wrapper's signature.
 * - [toKotlin]: a lambda that wraps a JS-side expression to convert it into the Kotlin type
 *   (e.g. `"(id).toLong()"`).
 * - [fromKotlin]: a lambda that wraps a Kotlin-side expression to convert it into the JS type
 *   (e.g. `"(id).toDouble()"`).
 *
 * Types with a native JS equivalent (e.g. `String`, `Int`) use the default identity lambdas.
 */
data class TypeMapping(
    /** The type exposed at the `@JsExport` boundary in the generated wrapper. */
    val jsTypeName: TypeName,
    /** Converts a JS-side expression string to the corresponding Kotlin type. */
    val toKotlin: (String) -> String = { name -> name },
    /** Converts a Kotlin-side expression string to the corresponding JS type. */
    val fromKotlin: (String) -> String = { name -> name },
)

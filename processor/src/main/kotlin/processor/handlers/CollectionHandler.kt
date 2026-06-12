package processor.handlers

import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import processor.isList
import processor.isLong
import processor.isMap
import processor.isSet
import types.TypeMapping

/**
 * Handles `List<T>` and `Set<T>` → `Array<T>` boundary conversion.
 *
 * Kotlin collections are not supported at `@JsExport` boundaries. This handler maps both
 * `List` and `Set` to `Array`, applying element-level type conversions recursively so that
 * nested structures like `List<List<Long>>` become `Array<Array<Long>>` (BigInt mode) or
 * `Array<Array<Double>>` (default mode).
 *
 * Must be instantiated with the same [bigintEnabled] flag used by [LongHandler] so that
 * `Long` element types are resolved consistently.
 */
internal class CollectionHandler(private val bigintEnabled: Boolean) {
    private val arrayClass = Array::class.asClassName()
    private val longJsTypeName get() = if (bigintEnabled) Long::class.asClassName() else Double::class.asClassName()

    /** Returns `true` when [type] is a `kotlin.collections.List` or `kotlin.collections.Set`. */
    fun handles(type: KSType): Boolean = type.isList || type.isSet

    /**
     * Returns a [TypeMapping] that converts the collection to `Array<T>` at the JS boundary.
     *
     * Element conversions are applied recursively, so `List<List<Long>>` round-trips through
     * `.map { ... }.toTypedArray()` on the way out and `.map { ... }.toList()` on the way in.
     * Element types that need no conversion collapse to a plain `toTypedArray()` / `toList()`.
     */
    fun resolveMapping(type: KSType): TypeMapping =
        TypeMapping(
            jsTypeName = toJsName(type),
            toKotlin = { name -> toKotlinExpr(type, name) },
            fromKotlin = { expr -> fromKotlinExpr(type, expr) },
        )

    /** Builds the Kotlin → JS expression for a collection or element [expr] of [type]. */
    private fun fromKotlinExpr(type: KSType, expr: String): String = when {
        type.isList || type.isSet -> {
            val element = fromKotlinExpr(type.arguments.first().type!!.resolve(), "it")
            if (element == "it") "$expr.toTypedArray()" else "$expr.map { $element }.toTypedArray()"
        }
        type.isLong -> if (bigintEnabled) expr else "$expr.toDouble()"
        else -> expr
    }

    /** Builds the JS → Kotlin expression for a collection or element [expr] of [type]. */
    private fun toKotlinExpr(type: KSType, expr: String): String = when {
        type.isList || type.isSet -> {
            val element = toKotlinExpr(type.arguments.first().type!!.resolve(), "it")
            val collector = if (type.isList) "toList" else "toSet"
            if (element == "it") "$expr.$collector()" else "$expr.map { $element }.$collector()"
        }
        type.isLong -> if (bigintEnabled) expr else "$expr.toLong()"
        else -> expr
    }

    /**
     * Recursively resolves a Kotlin type to its JS-equivalent [TypeName] for use as an
     * `Array` element type. Does not produce conversion lambdas — use [resolveMapping] for that.
     */
    fun toJsName(type: KSType): TypeName = when {
        type.isList || type.isSet -> arrayClass.parameterizedBy(toJsName(type.arguments.first().type!!.resolve()))
        type.isMap -> MapHandler.jsonClass
        type.isLong -> longJsTypeName
        else -> type.toTypeName()
    }
}

package io.github.samhoby.kotlintojs.processor.handlers

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.samhoby.kotlintojs.processor.isValueClass
import io.github.samhoby.kotlintojs.processor.types.TypeMapping

/**
 * Handles Kotlin value (inline) classes at `@JsExport` boundaries.
 *
 * Value classes are erased at the JVM level but in Kotlin/JS they surface as an opaque wrapper
 * object that JavaScript cannot use as the underlying type directly. This handler unwraps the
 * value class at the export boundary (exposing the JS-compatible underlying type) and re-wraps
 * it when calling back into the service.
 *
 * Type conversions on the underlying property are applied transitively: a value class wrapping
 * `Long` in BigInt mode or wrapping `List<T>` will compose correctly with [LongHandler] and
 * [CollectionHandler] via the resolveInner delegate.
 *
 * **Known limitation:** value classes used as *element types* inside `List<ValueClass>` or
 * `Set<ValueClass>` are not yet unwrapped by [CollectionHandler]. Only direct parameter and
 * return type positions are supported in this version.
 */
internal object ValueClassHandler {
    /** Returns `true` when [type] is a Kotlin value class. */
    fun handles(type: KSType): Boolean = type.isValueClass

    /**
     * Returns a [TypeMapping] that exposes the underlying type at the JS boundary.
     *
     * - **Parameter position**: the JS caller passes the underlying type; the wrapper constructs
     *   the value class before delegating to the service.
     * - **Return position**: the wrapper accesses the underlying property of the value the service
     *   returns and converts it to its JS-compatible form.
     *
     * [resolveInner] must be [io.github.samhoby.kotlintojs.processor.WrapperProcessor]'s `resolveMapping` so that the
     * underlying type itself receives the correct handler (e.g. [LongHandler] for a
     * `value class BigId(val id: Long)`).
     */
    fun resolveMapping(
        type: KSType,
        resolveInner: (KSType) -> TypeMapping,
    ): TypeMapping {
        val decl = type.declaration as KSClassDeclaration
        val underlyingParam = decl.primaryConstructor!!.parameters.first()
        val underlyingType = underlyingParam.type.resolve()
        val propName = underlyingParam.name!!.asString()
        val simpleName = decl.simpleName.asString()
        val innerMapping = resolveInner(underlyingType)

        return TypeMapping(
            jsTypeName = innerMapping.jsTypeName,
            toKotlin = { name -> "$simpleName(${innerMapping.toKotlin(name)})" },
            toJs = { expr -> innerMapping.toJs("($expr).$propName") },
            importsForToKotlin = innerMapping.importsForToKotlin,
            importsForToJs = innerMapping.importsForToJs,
        )
    }
}

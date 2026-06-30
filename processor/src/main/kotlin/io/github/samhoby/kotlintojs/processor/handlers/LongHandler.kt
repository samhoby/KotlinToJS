package io.github.samhoby.kotlintojs.processor.handlers

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.asClassName
import io.github.samhoby.kotlintojs.processor.isLong
import io.github.samhoby.kotlintojs.processor.types.TypeMapping

/**
 * Handles `Long` at `@JsExport` boundaries.
 *
 * Without the `-Xes-long-as-bigint` Kotlin/JS compiler flag, `Long` compiles to an opaque runtime
 * class in JavaScript that is unusable as a number. This handler emits a build warning with
 * actionable guidance when `Long` is used directly at an export boundary, and falls back to a
 * `Double` conversion (which loses precision above 2^53).
 *
 * When [bigintEnabled] is `true` (via `ksp { arg("longAsBigInt", "true") }`), `Long` passes
 * through without conversion; the compiler flag maps it to the native JS `BigInt` type.
 */
internal class LongHandler(
    private val bigintEnabled: Boolean,
    private val logger: KSPLogger,
) {
    companion object {
        private val doubleTypeName = Double::class.asClassName()
        private val longTypeName = Long::class.asClassName()
    }

    /** Returns `true` when [type] is `kotlin.Long`. */
    fun handles(type: KSType): Boolean = type.isLong

    /**
     * Returns a [TypeMapping] for a direct `Long` boundary type.
     *
     * - **BigInt mode**: passthrough — no conversion lambdas, Long is exposed as-is.
     * - **Default mode**: emits a build warning and returns a `Double` fallback (precision loss
     *   above 2^53); the build still succeeds.
     */
    fun resolveMapping(): TypeMapping {
        if (!bigintEnabled) {
            logger.warn(
                "Long is not supported at @JsExport boundaries without precision loss. " +
                    "Enable BigInt support: add \"-Xes-long-as-bigint\" to your Kotlin/JS target's " +
                    "freeCompilerArgs and set ksp { arg(\"longAsBigInt\", \"true\") } in your build file.",
            )

            return TypeMapping(
                jsTypeName = doubleTypeName,
                toKotlin = { name -> "($name).toLong()" },
                toJs = { expr -> "($expr).toDouble()" },
            )
        }
        return TypeMapping(jsTypeName = longTypeName)
    }
}

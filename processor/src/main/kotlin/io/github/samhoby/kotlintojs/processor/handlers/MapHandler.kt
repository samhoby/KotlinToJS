package io.github.samhoby.kotlintojs.processor.handlers

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.samhoby.kotlintojs.processor.elementType
import io.github.samhoby.kotlintojs.processor.isList
import io.github.samhoby.kotlintojs.processor.isLong
import io.github.samhoby.kotlintojs.processor.isMap
import io.github.samhoby.kotlintojs.processor.isSet
import io.github.samhoby.kotlintojs.processor.isString
import io.github.samhoby.kotlintojs.processor.keyType
import io.github.samhoby.kotlintojs.processor.types.TypeMapping
import io.github.samhoby.kotlintojs.processor.valueType

/**
 * Handles `Map<K, V>` ⇄ `Json` boundary conversion.
 *
 * For each distinct map signature, generates a pair of extension functions whose names are derived
 * from a unique sequential ID, for example `Json.toMap1()` and `Map<String, Long>.toJson1()`,
 * written to `TypeConversion.kt`.
 *
 * Keys and values are converted recursively. Nested map signatures are registered as their own
 * conversion functions.
 */
internal class MapHandler(
    private val bigintEnabled: Boolean,
    private val logger: KSPLogger,
) {
    private var currentId = 1

    private val typeIds = mutableMapOf<String, Int>()

    private val conversions = linkedMapOf<String, Pair<FunSpec, FunSpec>>()

    /** `true` when at least one map signature has been registered. */
    val hasMapType: Boolean get() = conversions.isNotEmpty()

    /** Returns `true` if [type] is a `Map<*, *>`. */
    fun handles(type: KSType): Boolean = type.isMap

    /**
     * Registers conversion functions for [type] (and any nested maps) and returns a [TypeMapping]
     * whose [TypeMapping.toKotlin] / [TypeMapping.toJs] lambdas call the generated extension functions.
     */
    fun resolveMapping(type: KSType): TypeMapping {
        registerConversions(type)
        val decode = decodeName(type)
        val encode = encodeName(type)
        return TypeMapping(
            jsTypeName = jsonClass,
            toKotlin = { name -> "$name.$decode()" },
            toJs = { expr -> "($expr).$encode()" },
            importsForToKotlin = listOf(decode),
            importsForToJs = listOf(encode),
        )
    }

    /**
     * Writes `TypeConversion.kt` containing all registered decode/encode function pairs.
     * Does nothing if no map signatures were registered.
     */
    fun generateTypeConversion(codeGenerator: CodeGenerator) {
        if (!hasMapType) return
        val fileSpecBuilder =
            FileSpec
                .builder("kotlintojs.generated", "TypeConversion")
                .addImport("kotlin.js", "Json")

        conversions.values.forEach { (decodeSpec, encodeSpec) ->
            fileSpecBuilder.addFunction(decodeSpec)
            fileSpecBuilder.addFunction(encodeSpec)
        }
        fileSpecBuilder.build().writeTo(codeGenerator, aggregating = false)
    }

    /** Resets all state so the handler is ready for the next KSP processing round. */
    fun clear() {
        conversions.clear()
        typeIds.clear()
        currentId = 1
    }

    /** Registers decode+encode specs for [mapType] if not already present, then recurses into nested maps. */
    private fun registerConversions(mapType: KSType) {
        val key = signature(mapType)
        if (key in conversions) return
        conversions[key] = buildDecodeSpec(mapType) to buildEncodeSpec(mapType)
        registerNestedMaps(mapType.keyType)
        registerNestedMaps(mapType.valueType)
    }

    /** Walks [type] recursively, registering any `Map` signatures found inside collections or nested maps. */
    private fun registerNestedMaps(type: KSType) {
        when {
            type.isMap -> registerConversions(type)
            type.isList || type.isSet -> registerNestedMaps(type.elementType)
        }
    }

    /** Builds a `fun Json.toMapN(): Map<K, V>` extension that converts a JS object to a Kotlin map. */
    private fun buildDecodeSpec(mapType: KSType): FunSpec {
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val builder = FunSpec.builder(decodeName(mapType)).receiver(jsonClass).returns(mapType.toTypeName())
        if (keyType.isString) {
            builder.addStatement(
                "return js(%S).unsafeCast<Array<String>>().associateWith { %L }",
                "Object.keys(this)",
                jsValueToKotlin(valueType, "this.asDynamic()[it]"),
            )
        } else {
            builder.addStatement(
                "return js(%S).unsafeCast<Array<String>>().associate { k -> %L to %L }",
                "Object.keys(this)",
                jsKeyToKotlin(keyType),
                jsValueToKotlin(valueType, "this.asDynamic()[k]"),
            )
        }
        return builder.build()
    }

    /** Builds a `fun Map<K, V>.toJsonN(): Json` extension that folds a Kotlin map into a JS object. */
    private fun buildEncodeSpec(mapType: KSType): FunSpec =
        FunSpec
            .builder(encodeName(mapType))
            .receiver(mapType.toTypeName())
            .returns(jsonClass)
            .beginControlFlow("return entries.fold(js(%S)) { acc, (k, v) ->", "{}")
            .addStatement(
                "acc.asDynamic()[%L] = %L",
                kotlinKeyToJs(mapType.keyType),
                kotlinValueToJs(mapType.valueType, "v"),
            ).addStatement("acc")
            .endControlFlow()
            .build()

    /** Converts a JS object key string `k` to the Kotlin key type. JS object keys always arrive as String. */
    private fun jsKeyToKotlin(keyType: KSType): String =
        when (keyType.declaration.qualifiedName?.asString()) {
            "kotlin.String" -> {
                "k"
            }

            "kotlin.Long" -> {
                "k.toLong()"
            }

            "kotlin.Int" -> {
                "k.toInt()"
            }

            "kotlin.Short" -> {
                "k.toShort()"
            }

            "kotlin.Byte" -> {
                "k.toByte()"
            }

            "kotlin.Double" -> {
                "k.toDouble()"
            }

            "kotlin.Float" -> {
                "k.toFloat()"
            }

            "kotlin.Boolean" -> {
                "k.toBoolean()"
            }

            else -> {
                val declaration = keyType.declaration
                logger.error(
                    "Unsupported @JsExport map key type '${declaration.simpleName.asString()}'. " +
                        "Map keys must be String or a primitive (Int, Long, Short, Byte, Double, Float, Boolean).",
                    symbol = declaration,
                )
                val targetType = declaration.qualifiedName?.asString() ?: "Any"
                "k.unsafeCast<$targetType>()"
            }
        }

    /** Returns the JS key expression: string keys pass through as-is; all others are stringified. */
    private fun kotlinKeyToJs(keyType: KSType): String = if (keyType.isString) "k" else "k.toString()"

    /** Returns a Kotlin expression that converts JS value [expr] of [valueType] to the Kotlin equivalent. */
    private fun jsValueToKotlin(
        valueType: KSType,
        expr: String,
    ): String =
        when {
            valueType.isLong -> {
                if (bigintEnabled) "$expr.unsafeCast<Long>()" else "$expr.unsafeCast<Double>().toLong()"
            }

            valueType.isList || valueType.isSet -> {
                val elemExpr = jsValueToKotlin(valueType.elementType, "elem")
                val collector = if (valueType.isSet) "toSet" else "toList"
                "$expr.unsafeCast<Array<Any?>>().map { elem -> $elemExpr }.$collector()"
            }

            valueType.isMap -> {
                "$expr.unsafeCast<Json>().${decodeName(valueType)}()"
            }

            else -> {
                primitiveAsExpr(valueType, expr)
            }
        }

    /** Returns a Kotlin expression that converts [varName] of [valueType] to its JS-safe equivalent. */
    private fun kotlinValueToJs(
        valueType: KSType,
        varName: String,
    ): String =
        when {
            valueType.isLong -> {
                if (bigintEnabled) varName else "$varName.toDouble()"
            }

            valueType.isList || valueType.isSet -> {
                val elemExpr = kotlinValueToJs(valueType.elementType, "elem")
                if (elemExpr == "elem") "$varName.toTypedArray()" else "$varName.map { elem -> $elemExpr }.toTypedArray()"
            }

            valueType.isMap -> {
                "$varName.${encodeName(valueType)}()"
            }

            else -> {
                varName
            }
        }

    /** Wraps [expr] in an `unsafeCast` to the Kotlin primitive matching [type]. */
    private fun primitiveAsExpr(
        type: KSType,
        expr: String,
    ): String {
        val nullable = if (type.isMarkedNullable) "?" else ""
        val qualifiedName = type.declaration.qualifiedName?.asString() ?: return expr
        val castTarget =
            when (qualifiedName) {
                "kotlin.String" -> "String"
                "kotlin.Int" -> "Int"
                "kotlin.Double" -> "Double"
                "kotlin.Float" -> "Float"
                "kotlin.Boolean" -> "Boolean"
                "kotlin.Short" -> "Short"
                "kotlin.Byte" -> "Byte"
                else -> qualifiedName
            }
        return "$expr.unsafeCast<$castTarget$nullable>()"
    }

    /** Returns the stable numeric ID for [mapType], assigning a new one on first encounter. */
    private fun getIdFor(mapType: KSType): Int = typeIds.getOrPut(signature(mapType)) { currentId++ }

    /**
     * Produces a stable deduplication key for [type] by combining the declaration's qualified name
     * with its type arguments recursively. Unlike [toTypeName] + [toString],
     * this is not affected by KotlinPoet/KSP formatting or qualification changes across versions.
     */
    private fun signature(type: KSType): String {
        val base = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()
        val args =
            type.arguments.joinToString(",") { arg ->
                arg.type?.resolve()?.let { signature(it) } ?: "*"
            }
        return if (args.isEmpty()) base else "$base<$args>"
    }

    /** Name of the `Json` extension that decodes this map type, e.g. `toMap1`. */
    private fun decodeName(mapType: KSType): String = "toMap${getIdFor(mapType)}"

    /** Name of the `Map` extension that encodes this map type, e.g. `toJson1`. */
    private fun encodeName(mapType: KSType): String = "toJson${getIdFor(mapType)}"

    companion object {
        val jsonClass = ClassName("kotlin.js", "Json")
    }
}

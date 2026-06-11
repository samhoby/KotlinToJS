package processor.handlers

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import processor.isList
import processor.isLong
import processor.isMap
import processor.isSet
import types.TypeMapping

/**
 * Handles `Map<K, V>` → `Json` boundary conversion.
 *
 * `Map` is not supported at `@JsExport` boundaries. This handler converts maps to the Kotlin/JS
 * `Json` type and generates typed `toMapN()` / `toJsonN()` extension functions (collected into
 * `TypeConversion.kt`) for each unique map signature encountered during a processing round.
 * Must be instantiated per [processor.WrapperProcessor] to keep conversion state isolated across
 * KSP processing rounds.
 *
 * [bigintEnabled] mirrors the plugin-level `longAsBigInt` option: when `true`, `Long` keys and
 * values inside maps are cast directly (`unsafeCast<Long>()`) rather than converted via `Double`.
 */
internal class MapHandler(private val bigintEnabled: Boolean = false) {
    companion object {
        /** The JS-compatible type used at the `@JsExport` boundary in place of `Map`. */
        val jsonClass = ClassName("kotlin.js", "Json")
    }

    private val mapConversions = mutableSetOf<String>()
    private val mapConversionFunctions = mutableListOf<FunSpec>()
    private val typeSignaturesToAliases = mutableMapOf<String, String>()
    private var typeCounter = 1

    /** Returns true when [type] is `kotlin.collections.Map`. */
    fun handles(type: KSType): Boolean = type.isMap

    /**
     * Returns a [TypeMapping] that converts the map to [jsonClass] at the JS boundary, and
     * registers the corresponding `toMapN` / `toJsonN` extension functions for later emission.
     */
    fun resolveMapping(type: KSType): TypeMapping {
        buildConversionFunctions(type)
        val id = type.aliasId()
        return TypeMapping(
            jsTypeName = jsonClass,
            toKotlin = { name -> "($name).toMap$id()" },
            fromKotlin = { expr -> "($expr).toJson$id()" },
        )
    }

    /** Returns true if any map conversions were registered during this processing round. */
    fun hasConversions(): Boolean = mapConversionFunctions.isNotEmpty()

    /**
     * Writes `TypeConversion.kt` to disk, containing the base `toMap()` helper and all
     * typed `toMapN` / `toJsonN` extension functions accumulated this round.
     */
    fun generateTypeConversion(codeGenerator: CodeGenerator) {
        val file = FileSpec.builder("", "TypeConversion").addFunction(
            FunSpec.builder("toMap")
                .receiver(jsonClass)
                .returns(Map::class.asClassName().parameterizedBy(STAR, STAR))
                .addStatement(
                    "return js(%S).unsafeCast<Array<String>>().associateWith { this.asDynamic()[it] }",
                    "Object.keys(this)",
                ).build(),
        )
        mapConversionFunctions.forEach { file.addFunction(it) }
        file.build().writeTo(codeGenerator, aggregating = false)
    }

    /** Clears accumulated conversion functions after they have been written to disk. */
    fun clear() = mapConversionFunctions.clear()

    /** Returns (or creates) a stable numeric alias for this map's type signature. */
    private fun KSType.aliasId(): String = typeSignaturesToAliases.getOrPut(signature()) { "${typeCounter++}" }

    /** Produces a canonical string key that uniquely identifies a map's key/value type pair. */
    private fun KSType.signature(): String = when {
        isLong -> "Long"
        isList || isSet -> "${arguments.first().type!!.resolve().signature()}${if (isList) "List" else "Set"}"
        isMap -> "${arguments[0].type!!.resolve().signature()}${arguments[1].type!!.resolve().signature()}Map"
        else -> declaration.simpleName.asString()
    }

    /**
     * Generates and registers the `toMapN` (Json → Kotlin) and `toJsonN` (Kotlin → Json)
     * extension functions for [type]. Recursively handles nested map types.
     */
    private fun buildConversionFunctions(type: KSType) {
        val id = type.aliasId()
        if (!mapConversions.add("MapConversion_$id")) return

        val kType = type.arguments[0].type!!.resolve()
        val vType = type.arguments[1].type!!.resolve()
        val mapType = Map::class.asClassName().parameterizedBy(kType.toTypeName(), vType.toTypeName())

        mapConversionFunctions.add(
            FunSpec.builder("toMap$id")
                .receiver(jsonClass)
                .returns(mapType)
                .addStatement(
                    "return toMap().entries.associate { (k, v) -> ${buildToKotlin(kType, "k")} to ${buildToKotlin(vType, "v")} }",
                ).build(),
        )

        mapConversionFunctions.add(
            FunSpec.builder("toJson$id")
                .receiver(mapType)
                .returns(jsonClass)
                .beginControlFlow("return entries.fold(js(%S)) { acc, (k, v) ->", "{}")
                .addStatement("acc[%L] = %L", buildFromKotlin(kType, "k"), buildFromKotlin(vType, "v"))
                .addStatement("acc")
                .endControlFlow()
                .build(),
        )

        if (kType.isMap) buildConversionFunctions(kType)
        if (vType.isMap) buildConversionFunctions(vType)
    }

    /** Builds the Kotlin code string that converts a JS value [expr] to its Kotlin equivalent. */
    private fun buildToKotlin(type: KSType, expr: String): String = when {
        type.isLong -> if (bigintEnabled) "($expr).unsafeCast<Long>()" else "($expr).unsafeCast<Double>().toLong()"
        type.isList || type.isSet -> {
            val inner = type.arguments.first().type!!.resolve()
            val mapped = "($expr).unsafeCast<Array<*>>().map { ${buildToKotlin(inner, "it")} }"
            if (type.isList) mapped else "($mapped).toSet()"
        }
        type.isMap -> "($expr).unsafeCast<Json>().toMap${type.aliasId()}()"
        else -> "($expr).unsafeCast<${type.declaration.simpleName.asString()}>()"
    }

    /** Builds the Kotlin code string that converts a Kotlin [expr] to its JS equivalent. */
    private fun buildFromKotlin(type: KSType, expr: String): String = when {
        type.isLong -> if (bigintEnabled) expr else "($expr).toDouble()"
        type.isList || type.isSet -> {
            val inner = buildFromKotlin(type.arguments.first().type!!.resolve(), "it")
            "($expr).map { $inner }.toTypedArray()"
        }
        type.isMap -> {
            buildConversionFunctions(type)
            "($expr).toJson${type.aliasId()}()"
        }
        else -> expr
    }
}

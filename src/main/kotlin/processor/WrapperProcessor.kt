@file:Suppress("ktlint:standard:no-wildcard-imports")

package processor

import annotations.JsExportClass
import annotations.JsExportFunction
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

class WrapperProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val mapConversions = mutableSetOf<String>()
    private val mapConversionFunctions = mutableListOf<FunSpec>()
    private val typeSignaturesToAliases = mutableMapOf<String, String>()
    private var typeCounter = 1

    private val jsonClass = ClassName("kotlin.js", "Json")
    private val arrayClass = Array::class.asClassName()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val classes =
            resolver
                .getSymbolsWithAnnotation(JsExportClass::class.qualifiedName!!)
                .filterIsInstance<KSClassDeclaration>()
                .toList()

        val functions =
            resolver
                .getSymbolsWithAnnotation(JsExportFunction::class.qualifiedName!!)
                .filterIsInstance<KSFunctionDeclaration>()
                .toList()

        val functionsByClass = mutableMapOf<KSClassDeclaration, MutableList<KSFunctionDeclaration>>()

        // Group annotated functions inside their respective classes
        classes.forEach { classDecl ->
            functionsByClass
                .getOrPut(classDecl) { mutableListOf() }
                .addAll(
                    classDecl
                        .getDeclaredFunctions()
                        .filter { function -> function.validate() && function.simpleName.asString() != "<init>" },
                )
        }

        // Add standalone annotated functions to their parent classes
        functions.forEach { function ->
            (function.parentDeclaration as? KSClassDeclaration)?.let { classDecl ->
                functionsByClass.getOrPut(classDecl) { mutableListOf() }.add(function)
            }
        }

        // Check for overloaded functions without @JsName
        functionsByClass.forEach { (cls, funcs) ->
            val funcsByName = funcs.groupBy { it.simpleName.asString() }

            funcsByName.forEach { (originalName, overloads) ->
                if (overloads.size > 1) {
                    overloads.forEach { func ->
                        val hasJsName = func.annotations.any { it.shortName.asString() == "JsName" }
                        if (!hasJsName) {
                            logger.error(
                                "Kotlin/JS name mangling conflict: Overloaded function '$originalName' must be annotated with @JsName(\"uniqueName\").",
                                func,
                            )
                        }
                    }
                }
            }

            // Check for duplicate custom names
            val exportedNames = funcs.map { it.getExportedName() }
            val duplicateName =
                exportedNames
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .firstOrNull { it.value > 1 }
                    ?.key

            if (duplicateName != null) {
                logger.error("Multiple functions export to the same JS name: '$duplicateName'", cls)
            }
        }

        // Generate the wrapper for each class
        functionsByClass.forEach { (classDecl, funcs) -> generateWrapper(classDecl, funcs.distinct()) }

        // Generate type conversions if maps are used
        if (mapConversionFunctions.isNotEmpty()) {
            generateTypeConversion()
            mapConversionFunctions.clear()
        }

        return emptyList()
    }

    private fun generateWrapper(
        serviceClass: KSClassDeclaration,
        functions: List<KSFunctionDeclaration>,
    ) {
        val wrapperName = "${serviceClass.simpleName.asString()}Js"
        val isObject = serviceClass.classKind == ClassKind.OBJECT

        val classBuilder =
            TypeSpec
                .objectBuilder(wrapperName)
                .addAnnotation(ClassName("kotlin.js", "JsExport"))
                .addAnnotation(
                    AnnotationSpec
                        .builder(ClassName("kotlin", "OptIn"))
                        .addMember("%T::class", ClassName("kotlin.js", "ExperimentalJsExport"))
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("service", serviceClass.toClassName(), KModifier.PRIVATE)
                        .initializer(if (isObject) "%T" else "%T()", serviceClass.toClassName())
                        .build(),
                )

        // Add coroutine scope if there are suspend functions
        if (functions.any { it.modifiers.contains(Modifier.SUSPEND) }) {
            classBuilder.addProperty(
                PropertySpec
                    .builder("scope", ClassName("kotlinx.coroutines", "CoroutineScope"), KModifier.PRIVATE)
                    .initializer("%T()", ClassName("kotlinx.coroutines", "MainScope"))
                    .build(),
            )
        }

        functions.forEach { func ->
            val exportedName = func.getExportedName() // Use customised @JsName if present
            val originalName = func.simpleName.asString() // Real Kotlin function name
            val isSuspend = func.modifiers.contains(Modifier.SUSPEND)

            val returnType = func.returnType!!.resolve()
            val returnMapping = resolveMapping(returnType)

            // Define return type (wrapped in Promise if suspended)
            val finalReturn =
                if (isSuspend) {
                    ClassName("kotlin.js", "Promise").parameterizedBy(returnMapping.jsTypeName)
                } else {
                    returnMapping.jsTypeName
                }

            val params =
                func.parameters.map {
                    val pMapping = resolveMapping(it.type.resolve())
                    ParameterSpec(it.name!!.asString(), pMapping.jsTypeName)
                }

            val args =
                func.parameters.joinToString(", ") {
                    val pMapping = resolveMapping(it.type.resolve())
                    pMapping.toKotlin(it.name!!.asString())
                }

            // Call the real Kotlin function using its original name
            val call = "service.$originalName($args)"

            val body =
                if (isSuspend) {
                    "return scope.promise { ${returnMapping.fromKotlin(call)} }"
                } else {
                    "return ${returnMapping.fromKotlin(call)}"
                }

            // Build function using the JS exported name
            classBuilder.addFunction(
                FunSpec
                    .builder(exportedName)
                    .addParameters(params)
                    .returns(finalReturn)
                    .addStatement(body)
                    .build(),
            )
        }

        // Write file to disk
        FileSpec
            .builder(serviceClass.packageName.asString(), wrapperName)
            .addImport("", "toMap", "toJson")
            .addImport("kotlin.js", "Json")
            .addImport("kotlinx.coroutines", "promise")
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }

    // Get sequential ID for map types
    private fun KSType.aliasId(): String = typeSignaturesToAliases.getOrPut(signature()) { "${typeCounter++}" }

    // Generate unique signature for map types
    private fun KSType.signature(): String =
        when {
            isLong -> "Long"
            isList || isSet -> "${arguments.first().type!!.resolve().signature()}${if (isList) "List" else "Set"}"
            isMap -> "${arguments[0].type!!.resolve().signature()}${arguments[1].type!!.resolve().signature()}Map"
            else -> declaration.simpleName.asString()
        }

    // Resolve Kotlin type to JS type and provide conversion rules
    private fun resolveMapping(type: KSType): types.TypeMapping =
        when {
            type.isList || type.isSet -> {
                val mappedArgs = type.arguments.map { it.type!!.resolve().toJsExportable() }
                types.TypeMapping(
                    jsTypeName = arrayClass.parameterizedBy(mappedArgs),
                    toKotlin = { name -> if (type.isList) "$name.toList()" else "$name.toSet()" },
                    fromKotlin = { expr -> "$expr.toTypedArray()" },
                )
            }

            type.isMap -> {
                buildSpecificMapFunctions(type)
                val id = type.aliasId()
                types.TypeMapping(
                    jsTypeName = jsonClass,
                    toKotlin = { name -> "($name).toMap$id()" },
                    fromKotlin = { expr -> "($expr).toJson$id()" },
                )
            }

            type.isLong -> {
                types.TypeMapping(
                    Double::class.asClassName(),
                    toKotlin = { name -> "($name).toLong()" },
                    fromKotlin = { expr -> "($expr).toDouble()" },
                )
            }

            else -> {
                types.TypeMapping(jsTypeName = type.toTypeName())
            }
        }

    // Convert JS type to Kotlin type string
    private fun buildToKotlin(
        type: KSType,
        expr: String,
    ): String {
        val name = type.declaration.simpleName.asString()
        return when {
            type.isLong -> {
                "($expr).unsafeCast<Double>().toLong()"
            }

            type.isList || type.isSet -> {
                val inner =
                    type.arguments
                        .first()
                        .type!!
                        .resolve()
                val mapped = "($expr).unsafeCast<Array<*>>().map { ${buildToKotlin(inner, "it")} }"
                if (type.isList) mapped else "($mapped).toSet()"
            }

            type.isMap -> {
                "($expr).unsafeCast<Json>().toMap${type.aliasId()}()"
            }

            else -> {
                "($expr).unsafeCast<$name>()"
            }
        }
    }

    // Convert Kotlin type to JS type string
    private fun buildFromKotlin(
        type: KSType,
        expr: String,
    ): String =
        when {
            type.isLong -> {
                "($expr).toDouble()"
            }

            type.isList || type.isSet -> {
                val inner =
                    buildFromKotlin(
                        type.arguments
                            .first()
                            .type!!
                            .resolve(),
                        "it",
                    )
                "($expr).map { $inner }.toTypedArray()"
            }

            type.isMap -> {
                buildSpecificMapFunctions(type)
                "($expr).toJson${type.aliasId()}()"
            }

            else -> {
                expr
            }
        }

    // Build specific toJson and toMap extension functions
    private fun buildSpecificMapFunctions(type: KSType) {
        val id = type.aliasId()
        if (!mapConversions.add("MapConversion_$id")) return

        val kType = type.arguments[0].type!!.resolve()
        val vType = type.arguments[1].type!!.resolve()
        val mapType = Map::class.asClassName().parameterizedBy(kType.toTypeName(), vType.toTypeName())

        mapConversionFunctions.add(
            FunSpec
                .builder("toMap$id")
                .receiver(jsonClass)
                .returns(mapType)
                .addStatement(
                    "return toMap().entries.associate { (k, v) -> ${buildToKotlin(kType, "k")} to ${
                        buildToKotlin(
                            vType,
                            "v",
                        )
                    } }",
                ).build(),
        )

        mapConversionFunctions.add(
            FunSpec
                .builder("toJson$id")
                .receiver(mapType)
                .returns(jsonClass)
                .beginControlFlow("return entries.fold(js(%S)) { acc, (k, v) ->", "{}")
                .addStatement("acc[%L] = %L", buildFromKotlin(kType, "k"), buildFromKotlin(vType, "v"))
                .addStatement("acc")
                .endControlFlow()
                .build(),
        )

        // Recursively handle nested maps
        if (kType.isMap) buildSpecificMapFunctions(kType)
        if (vType.isMap) buildSpecificMapFunctions(vType)
    }

    // Output TypeConversion.kt to disk
    private fun generateTypeConversion() {
        val file =
            FileSpec.builder("", "TypeConversion").addFunction(
                FunSpec
                    .builder("toMap")
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

    // Map Kotlin TypeName to equivalent JS TypeName
    private fun KSType.toJsExportable(): TypeName =
        when {
            isList || isSet -> {
                arrayClass.parameterizedBy(
                    arguments
                        .first()
                        .type!!
                        .resolve()
                        .toJsExportable(),
                )
            }

            isMap -> {
                jsonClass
            }

            isLong -> {
                Double::class.asClassName()
            }

            else -> {
                toTypeName()
            }
        }

    // Extract @JsName value if present, else fallback to function name
    private fun KSFunctionDeclaration.getExportedName(): String {
        val jsNameAnnotation =
            annotations.firstOrNull { annotation ->
                annotation.shortName.asString() == "JsName" &&
                    annotation.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == "kotlin.js.JsName"
            }
        return jsNameAnnotation?.arguments?.firstOrNull()?.value as? String ?: simpleName.asString()
    }

    // Quick extension properties for checking specific types
    private val KSType.isList get() = declaration.qualifiedName?.asString() == "kotlin.collections.List"
    private val KSType.isSet get() = declaration.qualifiedName?.asString() == "kotlin.collections.Set"
    private val KSType.isMap get() = declaration.qualifiedName?.asString() == "kotlin.collections.Map"
    private val KSType.isLong get() = declaration.qualifiedName?.asString() == "kotlin.Long"
}

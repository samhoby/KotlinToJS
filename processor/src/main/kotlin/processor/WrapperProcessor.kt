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
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import processor.handlers.*
import types.TypeMapping

/**
 * KSP processor that generates `@JsExport` wrapper objects for classes and functions annotated
 * with [annotations.JsExportClass] or [annotations.JsExportFunction].
 *
 * This class is responsible only for orchestration: collecting annotated symbols, delegating
 * type resolution and conflict detection to the appropriate handlers, and writing the generated
 * files to disk. All conversion logic lives in the `handlers/` package.
 */
class WrapperProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    options: Map<String, String> = emptyMap(),
) : SymbolProcessor {
    private val bigintEnabled = options["longAsBigInt"]?.toBoolean() ?: false
    private val longHandler = LongHandler(bigintEnabled, logger)
    private val collectionHandler = CollectionHandler(bigintEnabled)
    private val mapHandler = MapHandler(bigintEnabled, logger)

    private val pendingImports = mutableSetOf<ClassName>()

    // Map conversion function names (from kotlintojs.generated) referenced by the file being built.
    // Cleared at the start of each generateWrapper / generateUtils call.
    private val pendingConversionImports = mutableSetOf<String>()

    /**
     * Main KSP processing entry point. Collects all symbols annotated with [annotations.JsExportClass]
     * and [annotations.JsExportFunction], groups member functions under their declaring class,
     * checks for name-mangling conflicts, and generates the corresponding wrapper files.
     *
     * Standalone [annotations.JsExportFunction]s (not inside a class) are gathered into a single
     * `JsExportUtils` object. Map types encountered during wrapper generation produce a shared
     * `TypeConversion.kt` file at the end of the round.
     */
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

        classes.forEach { classDecl ->
            functionsByClass
                .getOrPut(classDecl) { mutableListOf() }
                .addAll(
                    classDecl
                        .getDeclaredFunctions()
                        .filter { func -> func.validate() && func.simpleName.asString() != "<init>" },
                )
        }

        val standaloneFunctions = mutableListOf<KSFunctionDeclaration>()

        functions.forEach { function ->
            val classDecl = function.parentDeclaration as? KSClassDeclaration
            if (classDecl != null) {
                functionsByClass.getOrPut(classDecl) { mutableListOf() }.add(function)
            } else {
                standaloneFunctions.add(function)
            }
        }

        functionsByClass.forEach { (classDecl, funcs) ->
            ManglingHandler.checkConflicts(classDecl, funcs, logger)
            generateWrapper(classDecl, funcs.distinct())
        }

        val distinct = standaloneFunctions.distinct()
        if (distinct.isNotEmpty()) {
            ManglingHandler.checkConflicts(null, distinct, logger)
            generateUtils(distinct)
        }

        mapHandler.generateTypeConversion(codeGenerator)
        mapHandler.clear()

        return emptyList()
    }

    /**
     * Generates a `{ClassName}Js` wrapper object for [serviceClass], exposing [functions] with
     * JS-compatible types. Suspend functions are wrapped in `Promise`. Type conversions are
     * delegated to the relevant handlers.
     *
     * When [serviceClass] is itself a value class annotated with `@JsExportClass`, no `service`
     * property is generated. Each exported function receives the underlying value as an implicit
     * first parameter, named after the class in lower-camel-case, and constructs the value class
     * on every call so that JS callers work with the raw underlying type.
     */
    private fun generateWrapper(
        serviceClass: KSClassDeclaration,
        functions: List<KSFunctionDeclaration>,
    ) {
        pendingImports.clear()
        pendingConversionImports.clear()
        val wrapperName = "${serviceClass.simpleName.asString()}Js"
        val isObject = serviceClass.classKind == ClassKind.OBJECT
        val isValueClass = Modifier.VALUE in serviceClass.modifiers

        val classBuilder = jsExportObjectBuilder(wrapperName)

        if (!isValueClass) {
            classBuilder.addProperty(
                PropertySpec
                    .builder("service", serviceClass.toClassName(), KModifier.PRIVATE)
                    .initializer(if (isObject) "%T" else "%T()", serviceClass.toClassName())
                    .build(),
            )
        }

        val valueClassSelf: Pair<String, TypeMapping>? =
            if (isValueClass) {
                val underlyingParam = serviceClass.primaryConstructor!!.parameters.first()
                val selfParamName = serviceClass.simpleName.asString().replaceFirstChar { char -> char.lowercase() }
                selfParamName to resolveMapping(underlyingParam.type.resolve())
            } else {
                null
            }
        // The value class's own underlying value is emitted through toKotlin in each call.
        valueClassSelf?.let { (_, mapping) -> pendingConversionImports.addAll(mapping.importsForToKotlin) }

        addScopeIfNeeded(classBuilder, functions)

        val leadingParams =
            valueClassSelf?.let { (name, mapping) -> listOf(ParameterSpec(name, mapping.jsTypeName)) } ?: emptyList()

        functions.forEach { func ->
            val originalName = func.simpleName.asString()
            classBuilder.addFunction(
                buildExportedFunction(func, leadingParams) { args ->
                    if (valueClassSelf != null) {
                        val (selfName, selfMapping) = valueClassSelf
                        "${serviceClass.simpleName.asString()}(${selfMapping.toKotlin(selfName)}).$originalName($args)"
                    } else {
                        "service.$originalName($args)"
                    }
                },
            )
        }

        val fileSpec =
            FileSpec
                .builder(serviceClass.packageName.asString(), wrapperName)
                .apply {
                    if (pendingConversionImports.isNotEmpty()) addImport("kotlin.js", "Json")
                    if (SuspendHandler.needsScope(functions)) addImport("kotlinx.coroutines", "promise")
                }

        writeFile(fileSpec, classBuilder)
    }

    /**
     * Generates `JsExportUtils.kt` collecting all standalone [functions] annotated with
     * [annotations.JsExportFunction] into a single `JsExportUtils` object.
     * Only called when at least one such function exists.
     */
    private fun generateUtils(functions: List<KSFunctionDeclaration>) {
        pendingImports.clear()
        pendingConversionImports.clear()
        val wrapperName = "JsExportUtils"

        val classBuilder = jsExportObjectBuilder(wrapperName)
        addScopeIfNeeded(classBuilder, functions)

        functions.forEach { func ->
            val originalName = func.simpleName.asString()
            val packageName = func.packageName.asString()
            if (packageName.isNotEmpty()) pendingImports.add(ClassName(packageName, originalName))

            classBuilder.addFunction(
                buildExportedFunction(func) { args -> "$originalName($args)" },
            )
        }

        val fileSpec =
            FileSpec
                .builder("", wrapperName)
                .apply {
                    if (pendingConversionImports.isNotEmpty()) addImport("kotlin.js", "Json")
                    if (SuspendHandler.needsScope(functions)) addImport("kotlinx.coroutines", "promise")
                }

        writeFile(fileSpec, classBuilder)
    }

    /**
     * Creates the base `@JsExport object` builder with the mandatory
     * `@OptIn(ExperimentalJsExport::class)` opt-in, shared by every generated wrapper.
     */
    private fun jsExportObjectBuilder(name: String): TypeSpec.Builder =
        TypeSpec
            .objectBuilder(name)
            .addAnnotation(ClassName("kotlin.js", "JsExport"))
            .addAnnotation(
                AnnotationSpec
                    .builder(ClassName("kotlin", "OptIn"))
                    .addMember("%T::class", ClassName("kotlin.js", "ExperimentalJsExport"))
                    .build(),
            )

    /**
     * Adds a private `MainScope()` backed `CoroutineScope` property to [builder] when any of
     * [functions] is a suspend function and therefore needs `scope.promise { }`.
     */
    private fun addScopeIfNeeded(
        builder: TypeSpec.Builder,
        functions: List<KSFunctionDeclaration>,
    ) {
        if (SuspendHandler.needsScope(functions)) {
            builder.addProperty(
                PropertySpec
                    .builder("scope", ClassName("kotlinx.coroutines", "CoroutineScope"), KModifier.PRIVATE)
                    .initializer("%T()", ClassName("kotlinx.coroutines", "MainScope"))
                    .build(),
            )
        }
    }

    /**
     * Applies the accumulated [pendingImports] and [pendingConversionImports] to [fileSpec], attaches
     * the built [classBuilder], and writes the resulting file to the KSP output. Map conversion
     * functions are imported only when the file actually references a map type, so wrappers with no
     * map boundary do not import from the possibly absent `kotlintojs.generated` package.
     */
    private fun writeFile(
        fileSpec: FileSpec.Builder,
        classBuilder: TypeSpec.Builder,
    ) {
        pendingImports.forEach { className ->
            if (className.packageName.isNotEmpty()) {
                fileSpec.addImport(className.packageName, className.simpleName)
            }
        }
        pendingConversionImports.forEach { name ->
            fileSpec.addImport("kotlintojs.generated", name)
        }
        fileSpec
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }

    /**
     * Builds the exported [FunSpec] for [func], shared by class wrappers and `JsExportUtils`.
     *
     * Parameter and return types are resolved to their JS-compatible forms, suspend functions are
     * wrapped in `Promise`, and [leadingParams] are prepended to the function's own parameters
     * (used to expose a value class's underlying value as an implicit first argument).
     *
     * [buildCall] receives the comma-joined, already-converted argument list and returns the Kotlin
     * delegation expression, e.g. `service.foo(args)`, `Score(score).foo(args)`, or `foo(args)`.
     */
    private fun buildExportedFunction(
        func: KSFunctionDeclaration,
        leadingParams: List<ParameterSpec> = emptyList(),
        buildCall: (args: String) -> String,
    ): FunSpec {
        val isSuspend = func.modifiers.contains(Modifier.SUSPEND)
        val returnMapping = resolveMapping(func.returnType!!.resolve())
        // The return value is always emitted through toJs, so only its toJs-side imports are needed.
        pendingConversionImports.addAll(returnMapping.importsForToJs)
        val finalReturn: TypeName =
            if (isSuspend) SuspendHandler.buildReturnType(returnMapping) else returnMapping.jsTypeName

        val paramMappings = func.parameters.map { param -> param to resolveMapping(param.type.resolve()) }
        // Parameters are always emitted through toKotlin, so only their toKotlin-side imports are needed.
        paramMappings.forEach { (_, mapping) -> pendingConversionImports.addAll(mapping.importsForToKotlin) }

        val params =
            paramMappings.map { (param, mapping) ->
                ParameterSpec(param.name!!.asString(), mapping.jsTypeName)
            }

        val args =
            paramMappings.joinToString(", ") { (param, mapping) ->
                mapping.toKotlin(param.name!!.asString())
            }

        val call = buildCall(args)
        val body =
            if (isSuspend) SuspendHandler.buildBody(call, returnMapping) else "return ${returnMapping.toJs(call)}"

        return FunSpec
            .builder(ManglingHandler.getExportedName(func))
            .addParameters(leadingParams + params)
            .returns(finalReturn)
            .addStatement(body)
            .build()
    }

    /**
     * Resolves a Kotlin [KSType] to a [TypeMapping] by delegating to the first handler that
     * claims it. Falls back to a passthrough mapping for types with native JS equivalents.
     */
    private fun resolveMapping(type: KSType): TypeMapping =
        when {
            ValueClassHandler.handles(type) -> {
                pendingImports.add((type.declaration as KSClassDeclaration).toClassName())
                ValueClassHandler.resolveMapping(type, ::resolveMapping)
            }

            longHandler.handles(type) -> {
                longHandler.resolveMapping()
            }

            collectionHandler.handles(type) -> {
                collectionHandler.resolveMapping(type)
            }

            mapHandler.handles(type) -> {
                mapHandler.resolveMapping(type)
            }

            else -> {
                TypeMapping(jsTypeName = type.toTypeName())
            }
        }
}

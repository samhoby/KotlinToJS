@file:Suppress("ktlint:standard:no-wildcard-imports")

package io.github.samhoby.kotlintojs.processor

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
import io.github.samhoby.kotlintojs.annotations.JsExportClass
import io.github.samhoby.kotlintojs.annotations.JsExportConverter
import io.github.samhoby.kotlintojs.annotations.JsExportFunction
import io.github.samhoby.kotlintojs.annotations.JsExportReplacement
import io.github.samhoby.kotlintojs.processor.handlers.CollectionHandler
import io.github.samhoby.kotlintojs.processor.handlers.LongHandler
import io.github.samhoby.kotlintojs.processor.handlers.ManglingHandler
import io.github.samhoby.kotlintojs.processor.handlers.MapHandler
import io.github.samhoby.kotlintojs.processor.handlers.SuspendHandler
import io.github.samhoby.kotlintojs.processor.handlers.ValueClassHandler
import io.github.samhoby.kotlintojs.processor.types.Replacement
import io.github.samhoby.kotlintojs.processor.types.TypeMapping

/**
 * KSP processor that generates `@JsExport` wrapper objects for classes and functions annotated
 * with [JsExportClass] or [JsExportFunction].
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

    private val pendingConversionImports = mutableSetOf<String>()

    /**
     * Registry of `@JsExportReplacement` declarations, keyed by the fully-qualified name of the
     * Kotlin type being replaced (e.g. `pt.unravel.types.Either`). Populated at the start of each
     * round and consulted by [resolveMapping].
     */
    private val replacements = mutableMapOf<String, Replacement>()

    /**
     * Main KSP processing entry point. Collects all symbols annotated with [JsExportClass]
     * and [JsExportFunction], groups member functions under their declaring class,
     * checks for name-mangling conflicts, and generates the corresponding wrapper files.
     *
     * Standalone [JsExportFunction]s (not inside a class) are gathered into a single
     * `JsExportUtils` object. Map types encountered during wrapper generation produce a shared
     * `TypeConversion.kt` file at the end of the round.
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        collectReplacements(resolver)

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
     * Scans for `@JsExportReplacement` declarations and fills [replacements], mapping each replaced
     * type's fully-qualified name to the JS-facing type and its converter function. Re-run each
     * round so the registry reflects the symbols currently visible.
     */
    private fun collectReplacements(resolver: Resolver) {
        replacements.clear()
        resolver
            .getSymbolsWithAnnotation(JsExportReplacement::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { decl ->
                val annotation =
                    decl.annotations.first { it.shortName.asString() == JsExportReplacement::class.simpleName }
                val replacedType = annotation.arguments.first { it.name?.asString() == "replaces" }.value as KSType
                val replacedName = replacedType.declaration.qualifiedName?.asString() ?: return@forEach

                val converter =
                    decl.declarations
                        .filterIsInstance<KSClassDeclaration>()
                        .firstOrNull { it.isCompanionObject }
                        ?.getDeclaredFunctions()
                        ?.firstOrNull { function ->
                            function.annotations.any { it.shortName.asString() == JsExportConverter::class.simpleName }
                        }
                if (converter == null) {
                    logger.error(
                        "@JsExportReplacement type '${decl.simpleName.asString()}' must declare a companion " +
                            "function annotated @JsExportConverter that converts '$replacedName' to it.",
                        decl,
                    )
                    return@forEach
                }

                replacements[replacedName] =
                    Replacement(type = decl.toClassName(), converter = converter.simpleName.asString())
            }
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
            val initializer =
                if (isObject) CodeBlock.of("%T", serviceClass.toClassName()) else buildServiceInitializer(serviceClass)
            classBuilder.addProperty(
                PropertySpec
                    .builder("service", serviceClass.toClassName(), KModifier.PRIVATE)
                    .initializer(initializer)
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
     * Builds the initialiser for the wrapped `service` instance of a regular (non-object,
     * non-value) class.
     *
     * The generated wrapper is a parameterless `@JsExport object`, so it cannot receive the
     * service's dependencies from the JS side. Instead, it constructs the service eagerly,
     * instantiating every required primary-constructor parameter with that parameter type's own
     * no-argument constructor — e.g. `AuthAPI(httpClient = SharedHttpClient())`. Parameters that
     * declare a default value are omitted so their default applies. Each dependency type is
     * referenced via `%T`, so KotlinPoet imports it automatically.
     *
     * This assumes every required dependency is itself constructible with no arguments (directly or
     * through its own defaults); dependencies that need their own mandatory arguments are not
     * supported.
     */
    private fun buildServiceInitializer(serviceClass: KSClassDeclaration): CodeBlock {
        val requiredParams =
            serviceClass.primaryConstructor
                ?.parameters
                .orEmpty()
                .filterNot { param -> param.hasDefault }

        val initializer = CodeBlock.builder().add("%T(", serviceClass.toClassName())
        requiredParams.forEachIndexed { index, param ->
            if (index > 0) initializer.add(", ")
            val dependency = param.type.resolve().declaration as KSClassDeclaration
            initializer.add("%N = %T()", param.name!!.asString(), dependency.toClassName())
        }
        return initializer.add(")").build()
    }

    /**
     * Generates `JsExportUtils.kt` collecting all standalone [functions] annotated with
     * [JsExportFunction] into a single `JsExportUtils` object.
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
            replacements.containsKey(type.declaration.qualifiedName?.asString()) -> {
                val replacement = replacements.getValue(type.declaration.qualifiedName!!.asString())
                val argumentMappings = type.arguments.map { argument -> resolveMapping(argument.type!!.resolve()) }

                // The @JsExportConverter is generic and passes its payload through unchanged, so it
                // cannot transform a nested value (List -> Array, Long -> Double, a value class, ...).
                // A replacement is only sound when every type argument is already exportable as-is.
                // Fail loudly rather than emit a boundary whose declared type does not match the
                // value it carries. BigInt-mode Long stays a passthrough, so it is allowed.
                val probe = "__v__"
                val needsInnerConversion =
                    argumentMappings.any { mapping -> mapping.toJs(probe) != probe || mapping.toKotlin(probe) != probe }
                if (needsInnerConversion) {
                    logger.error(
                        "@JsExportReplacement type '${replacement.type.simpleName}' is used with a type " +
                            "argument that needs its own conversion at the JS boundary (such as List, Set, " +
                            "Map, Long without BigInt mode, or a value class). The generic converter cannot " +
                            "transform nested values, so a replacement must wrap an already-exportable type. " +
                            "Return the inner type directly instead of nesting it inside " +
                            "'${type.declaration.simpleName.asString()}'.",
                    )
                }

                val jsType =
                    if (argumentMappings.isEmpty()) {
                        replacement.type
                    } else {
                        replacement.type.parameterizedBy(argumentMappings.map { mapping -> mapping.jsTypeName })
                    }

                TypeMapping(
                    jsTypeName = jsType,
                    toJs = { expression -> "${replacement.type.simpleName}.${replacement.converter}($expression)" },
                )
            }

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

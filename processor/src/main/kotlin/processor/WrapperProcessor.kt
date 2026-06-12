package processor

import annotations.JsExportClass
import annotations.JsExportFunction
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import processor.handlers.CollectionHandler
import processor.handlers.LongHandler
import processor.handlers.ManglingHandler
import processor.handlers.MapHandler
import processor.handlers.SuspendHandler
import processor.handlers.ValueClassHandler
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
    private val mapHandler = MapHandler(bigintEnabled)

    // Accumulates value-class imports that need to be added to the FileSpec for the current file.
    // Cleared at the start of each generateWrapper call.
    private val pendingImports = mutableSetOf<ClassName>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val classes = resolver
            .getSymbolsWithAnnotation(JsExportClass::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val functions = resolver
            .getSymbolsWithAnnotation(JsExportFunction::class.qualifiedName!!)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        val functionsByClass = mutableMapOf<KSClassDeclaration, MutableList<KSFunctionDeclaration>>()

        classes.forEach { classDecl ->
            functionsByClass
                .getOrPut(classDecl) { mutableListOf() }
                .addAll(
                    classDecl.getDeclaredFunctions()
                        .filter { it.validate() && it.simpleName.asString() != "<init>" },
                )
        }

        // Top-level functions have no enclosing class and are gathered into a single JsExportUtils
        // object; member functions are grouped under their declaring class.
        val standaloneFunctions = mutableListOf<KSFunctionDeclaration>()

        functions.forEach { function ->
            val classDecl = function.parentDeclaration as? KSClassDeclaration
            if (classDecl != null) {
                functionsByClass.getOrPut(classDecl) { mutableListOf() }.add(function)
            } else {
                standaloneFunctions.add(function)
            }
        }

        functionsByClass.forEach { (cls, funcs) ->
            ManglingHandler.checkConflicts(cls, funcs, logger)
        }

        functionsByClass.forEach { (classDecl, funcs) ->
            generateWrapper(classDecl, funcs.distinct())
        }

        if (standaloneFunctions.isNotEmpty()) {
            val distinct = standaloneFunctions.distinct()
            ManglingHandler.checkConflicts(null, distinct, logger)
            generateUtils(distinct)
        }

        if (mapHandler.hasConversions()) {
            mapHandler.generateTypeConversion(codeGenerator)
            mapHandler.clear()
        }

        return emptyList()
    }

    /**
     * Generates a `{ClassName}Js` wrapper object for [serviceClass], exposing [functions] with
     * JS-compatible types. Suspend functions are wrapped in `Promise`; type conversions are
     * delegated to the relevant handlers.
     *
     * When [serviceClass] is itself a value class (annotated with `@JsExportClass`), no `service`
     * property is generated. Instead, each exported function receives the underlying value as an
     * implicit first parameter (named after the class, lower-camel-cased) and constructs the value
     * class on every call — mirroring how JS callers work with the raw underlying type.
     */
    private fun generateWrapper(serviceClass: KSClassDeclaration, functions: List<KSFunctionDeclaration>) {
        pendingImports.clear()
        val wrapperName = "${serviceClass.simpleName.asString()}Js"
        val isObject = serviceClass.classKind == ClassKind.OBJECT
        val isValueClass = Modifier.VALUE in serviceClass.modifiers

        val classBuilder = TypeSpec
            .objectBuilder(wrapperName)
            .addAnnotation(ClassName("kotlin.js", "JsExport"))
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .addMember("%T::class", ClassName("kotlin.js", "ExperimentalJsExport"))
                    .build(),
            )

        // Value classes are constructed per-call from the underlying value — no shared instance.
        if (!isValueClass) {
            classBuilder.addProperty(
                PropertySpec
                    .builder("service", serviceClass.toClassName(), KModifier.PRIVATE)
                    .initializer(if (isObject) "%T" else "%T()", serviceClass.toClassName())
                    .build(),
            )
        }

        // When the annotated class is itself a value class, every generated function takes the
        // underlying value as its first argument (named after the class, lower-camel-cased) so
        // that JS callers pass the primitive type rather than a Kotlin wrapper object.
        val valueClassSelf: Pair<String, TypeMapping>? = if (isValueClass) {
            val underlyingParam = serviceClass.primaryConstructor!!.parameters.first()
            val selfParamName = serviceClass.simpleName.asString().replaceFirstChar { it.lowercase() }
            selfParamName to resolveMapping(underlyingParam.type.resolve())
        } else null

        if (SuspendHandler.needsScope(functions)) {
            classBuilder.addProperty(
                PropertySpec
                    .builder("scope", ClassName("kotlinx.coroutines", "CoroutineScope"), KModifier.PRIVATE)
                    .initializer("%T()", ClassName("kotlinx.coroutines", "MainScope"))
                    .build(),
            )
        }

        val leadingParams = valueClassSelf?.let { (name, mapping) -> listOf(ParameterSpec(name, mapping.jsTypeName)) } ?: emptyList()

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

        val fileSpec = FileSpec.builder(serviceClass.packageName.asString(), wrapperName)
            .addImport("", "toMap", "toJson")
            .addImport("kotlin.js", "Json")
            .addImport("kotlinx.coroutines", "promise")

        pendingImports.forEach { className ->
            if (className.packageName.isNotEmpty()) {
                fileSpec.addImport(className.packageName, className.simpleName)
            }
        }

        fileSpec
            .addType(classBuilder.build())
            .build()
            .writeTo(codeGenerator, aggregating = false)
    }

    /**
     * Generates the shared `JsExportUtils` object for top-level [functions] annotated with
     * [annotations.JsExportFunction]. Unlike [generateWrapper] there is no `service` instance:
     * each function delegates to the original top-level function directly, importing it from its
     * source package. All standalone functions across every source file land in this one object.
     */
    private fun generateUtils(functions: List<KSFunctionDeclaration>) {
        pendingImports.clear()
        val wrapperName = "JsExportUtils"

        val classBuilder = TypeSpec
            .objectBuilder(wrapperName)
            .addAnnotation(ClassName("kotlin.js", "JsExport"))
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .addMember("%T::class", ClassName("kotlin.js", "ExperimentalJsExport"))
                    .build(),
            )

        if (SuspendHandler.needsScope(functions)) {
            classBuilder.addProperty(
                PropertySpec
                    .builder("scope", ClassName("kotlinx.coroutines", "CoroutineScope"), KModifier.PRIVATE)
                    .initializer("%T()", ClassName("kotlinx.coroutines", "MainScope"))
                    .build(),
            )
        }

        functions.forEach { func ->
            val originalName = func.simpleName.asString()
            // Import the top-level function so the unqualified call resolves in the generated file.
            val packageName = func.packageName.asString()
            if (packageName.isNotEmpty()) pendingImports.add(ClassName(packageName, originalName))

            classBuilder.addFunction(
                buildExportedFunction(func) { args -> "$originalName($args)" },
            )
        }

        val fileSpec = FileSpec.builder("", wrapperName)
            .addImport("", "toMap", "toJson")
            .addImport("kotlin.js", "Json")
            .addImport("kotlinx.coroutines", "promise")

        pendingImports.forEach { className ->
            if (className.packageName.isNotEmpty()) {
                fileSpec.addImport(className.packageName, className.simpleName)
            }
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
        val finalReturn: TypeName = if (isSuspend) SuspendHandler.buildReturnType(returnMapping) else returnMapping.jsTypeName

        val params = func.parameters.map {
            ParameterSpec(it.name!!.asString(), resolveMapping(it.type.resolve()).jsTypeName)
        }

        val args = func.parameters.joinToString(", ") {
            resolveMapping(it.type.resolve()).toKotlin(it.name!!.asString())
        }

        val call = buildCall(args)
        val body = if (isSuspend) SuspendHandler.buildBody(call, returnMapping) else "return ${returnMapping.fromKotlin(call)}"

        return FunSpec.builder(ManglingHandler.getExportedName(func))
            .addParameters(leadingParams + params)
            .returns(finalReturn)
            .addStatement(body)
            .build()
    }

    /**
     * Resolves a Kotlin [KSType] to a [TypeMapping] by delegating to the first handler that
     * claims it. Falls back to a passthrough mapping for types with native JS equivalents.
     */
    private fun resolveMapping(type: KSType): TypeMapping = when {
        ValueClassHandler.handles(type) -> {
            pendingImports.add((type.declaration as KSClassDeclaration).toClassName())
            ValueClassHandler.resolveMapping(type, ::resolveMapping)
        }
        longHandler.handles(type) -> longHandler.resolveMapping()
        collectionHandler.handles(type) -> collectionHandler.resolveMapping(type)
        mapHandler.handles(type) -> mapHandler.resolveMapping(type)
        else -> TypeMapping(jsTypeName = type.toTypeName())
    }
}

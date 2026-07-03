package io.github.samhoby.kotlintojs.processor.handlers

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import io.github.samhoby.kotlintojs.processor.types.JsRuntimeNames
import io.github.samhoby.kotlintojs.processor.types.TypeMapping

/**
 * Handles suspend function → `Promise<T>` boundary conversion.
 *
 * Suspend functions cannot be called from JavaScript. This handler wraps them in
 * `scope.promise { }` (from `kotlinx-coroutines-core`) so JS consumers can use
 * standard `async/await` or `.then()`. The `scope` property is only added to the
 * generated wrapper when at least one suspend function is present.
 */
internal object SuspendHandler {
    /** Returns true if any function in [functions] is a suspend function, indicating a `CoroutineScope` is needed. */
    fun needsScope(functions: List<KSFunctionDeclaration>): Boolean =
        functions.any { function -> function.modifiers.contains(Modifier.SUSPEND) }

    /** Wraps the resolved return [TypeName] inside `Promise<T>` for the JS boundary. */
    fun buildReturnType(mapping: TypeMapping): TypeName = JsRuntimeNames.promiseClass.parameterizedBy(mapping.jsTypeName)

    /**
     * Appends the `return scope.promise { ... }` statement to [builder], referencing the `promise`
     * extension via `%M` so KotlinPoet resolves the `kotlinx.coroutines` import itself.
     */
    fun addBody(
        builder: FunSpec.Builder,
        call: String,
        returnMapping: TypeMapping,
    ): FunSpec.Builder = builder.addStatement("return scope.%M { %L }", JsRuntimeNames.promise, returnMapping.toJs(call))
}

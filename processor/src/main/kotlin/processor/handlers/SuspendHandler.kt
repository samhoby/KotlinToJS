package processor.handlers

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import types.TypeMapping

/**
 * Handles suspend function → `Promise<T>` boundary conversion.
 *
 * Suspend functions cannot be called from JavaScript. This handler wraps them in
 * `scope.promise { }` (from `kotlinx-coroutines-core`) so JS consumers can use
 * standard `async/await` or `.then()`. The `scope` property is only added to the
 * generated wrapper when at least one suspend function is present.
 */
internal object SuspendHandler {
    private val promiseClass = ClassName("kotlin.js", "Promise")

    /** Returns true if any function in [functions] is a suspend function, indicating a `CoroutineScope` is needed. */
    fun needsScope(functions: List<KSFunctionDeclaration>): Boolean =
        functions.any { function -> function.modifiers.contains(Modifier.SUSPEND) }

    /** Wraps the resolved return [TypeName] inside `Promise<T>` for the JS boundary. */
    fun buildReturnType(mapping: TypeMapping): TypeName = promiseClass.parameterizedBy(mapping.jsTypeName)

    /** Builds the function body string that delegates to `scope.promise { }` and applies the return conversion. */
    fun buildBody(
        call: String,
        returnMapping: TypeMapping,
    ): String = "return scope.promise { ${returnMapping.toJs(call)} }"
}

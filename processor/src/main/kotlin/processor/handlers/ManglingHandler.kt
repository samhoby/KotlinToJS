package processor.handlers

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/**
 * Handles Kotlin/JS name mangling detection and exported name resolution.
 *
 * Kotlin mangles function names to support overloading, producing JS names like `process_za3rmp$`
 * that break stable API contracts. This handler resolves the correct exported name (via `@JsName`
 * if present) and fails the build with a clear error when overloaded functions lack `@JsName`,
 * preventing silent emission of unusable wrappers.
 */
internal object ManglingHandler {
    /**
     * Returns the JS export name for [function]: the value of `@JsName` if present,
     * otherwise the Kotlin function's simple name.
     */
    fun getExportedName(function: KSFunctionDeclaration): String {
        val jsNameAnnotation =
            function.annotations.firstOrNull { annotation ->
                annotation.shortName.asString() == "JsName" &&
                    annotation.annotationType
                        .resolve()
                        .declaration.qualifiedName
                        ?.asString() == "kotlin.js.JsName"
            }
        return jsNameAnnotation?.arguments?.firstOrNull()?.value as? String ?: function.simpleName.asString()
    }

    /**
     * Validates [functions] for name conflicts:
     * - Overloaded functions (same Kotlin name) without `@JsName` are reported as errors.
     * - Multiple functions resolving to the same exported JS name are reported as errors.
     *
     * [classDecl] is the enclosing class used as the diagnostic node for the duplicate-name
     * error. It is `null` for standalone functions collected into `JsExportUtils`.
     */
    fun checkConflicts(
        classDecl: KSClassDeclaration?,
        functions: List<KSFunctionDeclaration>,
        logger: KSPLogger,
    ) {
        functions.groupBy { it.simpleName.asString() }.forEach { (originalName, overloads) ->
            if (overloads.size > 1) {
                overloads.forEach { func ->
                    if (func.annotations.none { it.shortName.asString() == "JsName" }) {
                        logger.error(
                            "Kotlin/JS name mangling conflict: Overloaded function '$originalName' must be annotated with @JsName(\"uniqueName\").",
                            func,
                        )
                    }
                }
            }
        }

        val duplicate =
            functions
                .map { function -> getExportedName(function) }
                .groupingBy { exportedName -> exportedName }
                .eachCount()
                .entries
                .firstOrNull { (_, counts) -> counts > 1 }
                ?.key

        if (duplicate != null) {
            logger.error("Multiple functions export to the same JS name: '$duplicate'", classDecl)
        }
    }
}

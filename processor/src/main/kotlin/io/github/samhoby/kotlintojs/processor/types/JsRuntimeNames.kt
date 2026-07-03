package io.github.samhoby.kotlintojs.processor.types

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

/**
 * Canonical [ClassName]/[MemberName] references for JS-interop runtime symbols shared across
 * handlers and `WrapperProcessor`. Declaring `kotlin.js` and `kotlinx.coroutines` symbols once
 * here, and referencing them via `%T`/`%M` at every call site, lets KotlinPoet resolve imports
 * from the generated code structure itself instead of via manual `addImport(String, String)` calls.
 */
private const val KOTLIN_JS_PACKAGE = "kotlin.js"
private const val KOTLINX_COROUTINES_PACKAGE = "kotlinx.coroutines"
private const val KOTLIN_PACKAGE = "kotlin"

internal object JsRuntimeNames {
    val json = ClassName(KOTLIN_JS_PACKAGE, "Json")
    val promiseClass = ClassName(KOTLIN_JS_PACKAGE, "Promise")
    val jsExport = ClassName(KOTLIN_JS_PACKAGE, "JsExport")
    val experimentalJsExport = ClassName(KOTLIN_JS_PACKAGE, "ExperimentalJsExport")
    val optIn = ClassName(KOTLIN_PACKAGE, "OptIn")
    val coroutineScope = ClassName(KOTLINX_COROUTINES_PACKAGE, "CoroutineScope")
    val mainScope = ClassName(KOTLINX_COROUTINES_PACKAGE, "MainScope")
    val promise = MemberName(KOTLINX_COROUTINES_PACKAGE, "promise")
}

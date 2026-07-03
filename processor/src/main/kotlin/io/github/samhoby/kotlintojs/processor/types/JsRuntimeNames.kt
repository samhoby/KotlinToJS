package io.github.samhoby.kotlintojs.processor.types

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

/**
 * Canonical [ClassName]/[MemberName] references for JS-interop runtime symbols shared across
 * handlers and `WrapperProcessor`. Declaring `kotlin.js` and `kotlinx.coroutines` symbols once
 * here, and referencing them via `%T`/`%M` at every call site, lets KotlinPoet resolve imports
 * from the generated code structure itself instead of via manual `addImport(String, String)` calls.
 */
internal object JsRuntimeNames {
    val json = ClassName("kotlin.js", "Json")
    val promiseClass = ClassName("kotlin.js", "Promise")
    val jsExport = ClassName("kotlin.js", "JsExport")
    val experimentalJsExport = ClassName("kotlin.js", "ExperimentalJsExport")
    val optIn = ClassName("kotlin", "OptIn")
    val coroutineScope = ClassName("kotlinx.coroutines", "CoroutineScope")
    val mainScope = ClassName("kotlinx.coroutines", "MainScope")
    val promise = MemberName("kotlinx.coroutines", "promise")
}

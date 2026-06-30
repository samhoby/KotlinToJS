package io.github.samhoby.kotlintojs.processor.types

import com.squareup.kotlinpoet.ClassName

/**
 * A resolved `@JsExportReplacement`: the JS-facing [type] to expose and the name of its companion
 * [converter] function (the one marked `@JsExportConverter`), discovered by the processor.
 *
 * The conversion is emitted as `Type.converter(value)`; [type] is already imported via the return
 * type, so no further import is required.
 */
data class Replacement(
    val type: ClassName,
    val converter: String,
)

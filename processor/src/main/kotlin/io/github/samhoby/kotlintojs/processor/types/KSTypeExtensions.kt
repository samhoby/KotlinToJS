package io.github.samhoby.kotlintojs.processor.types

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** True when this type is `kotlin.collections.List`. */
internal val KSType.isList get() = declaration.qualifiedName?.asString() == "kotlin.collections.List"

/** True when this type is `kotlin.collections.Set`. */
internal val KSType.isSet get() = declaration.qualifiedName?.asString() == "kotlin.collections.Set"

/** True when this type is `kotlin.collections.Map`. */
internal val KSType.isMap get() = declaration.qualifiedName?.asString() == "kotlin.collections.Map"

/** True when this type is `kotlin.Long`. */
internal val KSType.isLong get() = declaration.qualifiedName?.asString() == "kotlin.Long"

/** True when this type is `kotlin.String`. */
internal val KSType.isString get() = declaration.qualifiedName?.asString() == "kotlin.String"

/** True when this type is a Kotlin value (inline) class. */
internal val KSType.isValueClass: Boolean
    get() {
        val decl = declaration as? KSClassDeclaration ?: return false
        return Modifier.VALUE in decl.modifiers
    }

/** The resolved key type `K` of a `Map<K, V>`. Only meaningful when [isMap]. */
internal val KSType.keyType: KSType get() = arguments[0].type!!.resolve()

/** The resolved value type `V` of a `Map<K, V>`. Only meaningful when [isMap]. */
internal val KSType.valueType: KSType get() = arguments[1].type!!.resolve()

/** The resolved element type `T` of a `List<T>` or `Set<T>`. Only meaningful when [isList] or [isSet]. */
internal val KSType.elementType: KSType get() = arguments.first().type!!.resolve()

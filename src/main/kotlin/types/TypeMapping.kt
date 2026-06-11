package types

import com.squareup.kotlinpoet.TypeName

data class TypeMapping(
    val jsTypeName: TypeName,
    val toKotlin: (String) -> String = { name -> name },
    val fromKotlin: (String) -> String = { name -> name },
)

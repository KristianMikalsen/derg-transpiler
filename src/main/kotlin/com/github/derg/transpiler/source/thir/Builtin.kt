package com.github.derg.transpiler.source.thir

import com.github.derg.transpiler.source.*

/**
 * Registers all builtin types, functions, variables, everything required to implement any sort of transpiler or
 * compiler.
 */
object Builtin
{
    val SYMBOLS = ThirSymbolTable()
    
    val BOOL = SYMBOLS.register(typeOf("__builtin_bool", size = 1))
    val INT32 = SYMBOLS.register(typeOf("__builtin_int32", size = 4))
    val INT64 = SYMBOLS.register(typeOf("__builtin_int64", size = 8))
    val VOID = SYMBOLS.register(typeOf("__builtin_void", size = 0))
    
    const val LIT_INT32 = "i32"
    const val LIT_INT64 = "i64"
}

/**
 * Generates a new type, with the given [size] in bytes and a specific [name].
 */
private fun typeOf(name: Name, size: Int) = ThirType(
    id = Id.randomUUID(),
    name = name,
    visibility = Visibility.EXPORTED,
).also { it.size = size }

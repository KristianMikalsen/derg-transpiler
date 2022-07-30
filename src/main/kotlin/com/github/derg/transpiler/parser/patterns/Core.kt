package com.github.derg.transpiler.parser.patterns

import com.github.derg.transpiler.parser.Context
import com.github.derg.transpiler.parser.ParseError
import com.github.derg.transpiler.parser.Parser
import com.github.derg.transpiler.util.*

/**
 * Parses a single [parser] at the current cursor location for the context, rolling back to the given [snapshot]
 * after parsing.
 */
private fun <Type> parseAndReset(context: Context, snapshot: Int, key: String, parser: Parser<Type>): Outcome<Type> =
    Outcome(key, parser.parse(context), context.snapshot()).also { context.revert(snapshot) }

/**
 * Helper data class to represent the outcome of a parsing operation from a single pattern. This allows easy
 * rollback to the snapshot representing this particular outcome.
 */
private data class Outcome<Type>(val key: String, val outcome: Result<Type, ParseError>, val snapshot: Int)

/**
 * Parses the context in such a way that exactly one of the provided [parsers] parses. If more than a single pattern
 * matches the context, the longest matching pattern is chosen. In the case of ties, the first specified pattern in the
 * list of [parsers] is chosen.
 */
class ParserAnyOf<Type>(private val parsers: List<Parser<Type>>) : Parser<Type>
{
    /** Helper for specifying the [parsers] of which exactly one of them must match. */
    constructor(vararg parsers: Parser<Type>) : this(parsers.toList())
    
    override fun parse(context: Context): Result<Type, ParseError>
    {
        if (!context.hasNext())
            return failureOf(ParseError.End)
        
        val snapshot = context.snapshot()
        val outcomes = parsers.map { parseAndReset(context, snapshot, "", it) }.sortedByDescending { it.snapshot }
        
        val result = outcomes.firstOrNull { it.outcome.isSuccess } ?: outcomes.first { it.outcome.isFailure }
        return result.outcome.onSuccess { context.revert(result.snapshot) }
    }
}

/**
 * Parses the context in such a way that all the provided [parsers] parses. The order in which the parses do parse is
 * irrelevant; the requirement is that they all must parse. If any of the provided patterns are optional, the optimal
 * parse is performed in which the longest matching parser which is not yet matched is selected, whenever one parser has
 * been properly parsed.
 */
class ParserAllOf(private val parsers: Map<String, Parser<*>>) : Parser<Map<String, *>>
{
    /** Helper for specifying all [parsers] which are all required. */
    constructor(vararg parsers: Pair<String, Parser<*>>) : this(parsers.toMap())
    
    override fun parse(context: Context): Result<Map<String, *>, ParseError>
    {
        val snapshot = context.snapshot()
        val values = mutableMapOf<String, Any?>()
        while (values.size < parsers.size)
        {
            val result = parse(context, parsers.filter { it.key !in values })
                .onFailure { context.revert(snapshot) }
                .valueOr { return failureOf(it) }
            values[result.first] = result.second
        }
        return successOf(values)
    }
    
    private fun parse(context: Context, parsers: Map<String, Parser<*>>): Result<Pair<String, *>, ParseError>
    {
        val ss = context.snapshot()
        val outcomes = parsers.map { parseAndReset(context, ss, it.key, it.value) }.sortedByDescending { it.snapshot }
        
        val result = outcomes.firstOrNull { it.outcome.isSuccess } ?: outcomes.first { it.outcome.isFailure }
        return result.outcome.mapValue { result.key to it }.onSuccess { context.revert(result.snapshot) }
    }
}

/**
 * Parses the context where all the provided [parsers] are required to match, in the exact same order they are
 * specified. The overall result of the parsing is determined by the whole chain; if either pattern fails to parse, the
 * whole sequence is discarded.
 */
class ParserSequence(private val parsers: List<Parser<*>>) : Parser<List<*>>
{
    /** Helper for specifying a sequence of [parsers] which are all required. */
    constructor(vararg parsers: Parser<*>) : this(parsers.toList())
    
    override fun parse(context: Context): Result<List<*>, ParseError>
    {
        val snapshot = context.snapshot()
        return parsers.fold { it.parse(context) }.onFailure { context.revert(snapshot) }
    }
}

/**
 * Parses the context where the [parser] is repeated as many times as it possibly can be. Every value which was
 * extracted will be present in the final output list. Every value is required to be separated by the [separator]. The
 * separator may also optionally appear at the very end of the sequence (i.e. a trailing comma).
 */
class ParserRepeating<Type>(private val parser: Parser<Type>, private val separator: Parser<*>) : Parser<List<Type>>
{
    override fun parse(context: Context): Result<List<Type>, ParseError>
    {
        if (!context.hasNext())
            return successOf(emptyList())
        
        val values = mutableListOf<Type>()
        while (true)
        {
            val value = parse(context, values.isEmpty()) ?: break
            values.add(value)
        }
        return successOf(values)
    }
    
    private fun parse(context: Context, first: Boolean): Type?
    {
        if (!first)
            separator.parse(context).onFailure { context.reset(); return null }
        return parser.parse(context).onFailure { context.reset() }.valueOrNull()
    }
}

/**
 * Parses the context where the [parser] is considered an optional bit of data. If the pattern matches the context, then
 * all data is written as if the pattern was mandatory. Otherwise, the context is rolled back to how it was before the
 * parsing was attempted, and the operation is considered a success.
 */
class ParserOptional<Type>(private val parser: Parser<Type>) : Parser<Type?>
{
    override fun parse(context: Context): Result<Type?, ParseError>
    {
        val snapshot = context.snapshot()
        return parser.parse(context).onFailure { context.revert(snapshot) }.valueOr { null }.toSuccess()
    }
}

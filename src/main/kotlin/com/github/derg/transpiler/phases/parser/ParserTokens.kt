package com.github.derg.transpiler.phases.parser

import com.github.derg.transpiler.source.ast.*
import com.github.derg.transpiler.source.lexeme.*
import com.github.derg.transpiler.source.thir.*
import com.github.derg.transpiler.utils.*

/**
 * Parses a single identifier from the token stream.
 */
class ParserName : Parser<String>
{
    private var name: String? = null
    
    override fun skipable(): Boolean = false
    override fun produce(): String = name ?: throw IllegalStateException("No name has been parsed")
    override fun parse(token: Token): Result<ParseOk, ParseError>
    {
        if (name != null)
            return ParseOk.Finished.toSuccess()
        
        val identifier = token as? Identifier ?: return ParseError.UnexpectedToken(token).toFailure()
        name = identifier.name
        return ParseOk.Complete.toSuccess()
    }
    
    override fun reset()
    {
        name = null
    }
}

/**
 * Parses a single symbol from the token stream. The parser will only accept one of the symbols present in the
 * [whitelist] when parsing. Any symbol not found in the whitelist is treated as an unexpected token.
 */
class ParserSymbol(vararg symbols: SymbolType) : Parser<SymbolType>
{
    private val whitelist = symbols.toSet()
    private var type: SymbolType? = null
    
    override fun skipable(): Boolean = false
    override fun produce(): SymbolType = type ?: throw IllegalStateException("No symbol has been parsed")
    override fun parse(token: Token): Result<ParseOk, ParseError>
    {
        if (type != null)
            return ParseOk.Finished.toSuccess()
        
        val symbol = token as? Symbol ?: return ParseError.UnexpectedToken(token).toFailure()
        type = if (symbol.type in whitelist) symbol.type else return ParseError.UnexpectedToken(token).toFailure()
        return ParseOk.Complete.toSuccess()
    }
    
    override fun reset()
    {
        type = null
    }
}

/**
 * Parses a single boolean value from the token stream.
 */
class ParserBool : Parser<AstExpression>
{
    private var expression: AstExpression? = null
    
    override fun parse(token: Token): Result<ParseOk, ParseError>
    {
        if (expression != null)
            return ParseOk.Finished.toSuccess()
        
        val symbol = token as? Symbol ?: return ParseError.UnexpectedToken(token).toFailure()
        expression = when (symbol.type)
        {
            SymbolType.TRUE  -> AstBool(true)
            SymbolType.FALSE -> AstBool(false)
            else             -> return ParseError.UnexpectedToken(token).toFailure()
        }
        return ParseOk.Complete.toSuccess()
    }
    
    override fun skipable(): Boolean = false
    override fun produce(): AstExpression = expression ?: throw IllegalStateException("No expression has been parsed")
    override fun reset()
    {
        expression = null
    }
}

/**
 * Parses a single numeric value from the token stream.
 */
class ParserReal : Parser<AstExpression>
{
    private var expression: AstExpression? = null
    
    override fun parse(token: Token): Result<ParseOk, ParseError>
    {
        if (expression != null)
            return ParseOk.Finished.toSuccess()
        
        val number = token as? Numeric ?: return ParseError.UnexpectedToken(token).toFailure()
        expression = AstReal(number.value, number.type ?: Builtin.INT32_LIT.name)
        return ParseOk.Complete.toSuccess()
    }
    
    override fun skipable(): Boolean = false
    override fun produce(): AstExpression = expression ?: throw IllegalStateException("No expression has been parsed")
    override fun reset()
    {
        expression = null
    }
}

/**
 * Parses a single string value from the token stream.
 */
class ParserText : Parser<AstExpression>
{
    private var expression: AstExpression? = null
    
    override fun parse(token: Token): Result<ParseOk, ParseError>
    {
        if (expression != null)
            return ParseOk.Finished.toSuccess()
        
        val string = token as? Textual ?: return ParseError.UnexpectedToken(token).toFailure()
        expression = AstText(string.value, string.type ?: Builtin.STR_LIT.name)
        return ParseOk.Complete.toSuccess()
    }
    
    override fun skipable(): Boolean = false
    override fun produce(): AstExpression = expression ?: throw IllegalStateException("No expression has been parsed")
    override fun reset()
    {
        expression = null
    }
}

package com.github.derg.transpiler.parser

import com.github.derg.transpiler.ast.Access
import com.github.derg.transpiler.ast.Expression
import com.github.derg.transpiler.ast.Operator.*
import com.github.derg.transpiler.ast.Parameter
import com.github.derg.transpiler.ast.Value
import com.github.derg.transpiler.lexer.*
import com.github.derg.transpiler.util.*

/**
 * All expressions are represented as either a single node which holds a specific value, or some other complicated and
 * nested expression. Arbitrary expressions may be extracted through this parser.
 */
object ParserExpression : Pattern<Expression>
{
    private val pattern = PatternAnyOf(
        ParserSubExpression,
        ParserInfixOperator,
    )
    
    override fun parse(context: Context): Result<Expression, String> = pattern.parse(context)
}

/**
 * In order to simplify the expression parsing engine, certain types of expressions are considered "leaf" expressions.
 * Examples of such expressions include constant values, memory access via variables, a nested sub-expression, or some
 * procedure call.
 */
private object ParserSubExpression : Pattern<Expression>
{
    private val pattern = PatternAnyOf(
        ParserBool,
        ParserReal,
        ParserText,
        ParserVariable,
        ParserFunction,
        ParserParenthesis,
        ParserPrefixOperator,
        ParserPostfixOperator,
    )
    
    override fun parse(context: Context): Result<Expression, String> = pattern.parse(context)
}


/**
 * Specifies a pattern where exactly one boolean value will be extracted from the context.
 */
object ParserBool : Pattern<Expression>
{
    override fun parse(context: Context): Result<Value.Bool, String>
    {
        val token = context.next() ?: return failureOf("expected token, found end of stream")
        val keyword = token as? Keyword ?: return failureOf("'${token.raw}' is not a keyword")
        return when (keyword.type)
        {
            Keyword.Type.TRUE  -> Value.Bool(true).toSuccess()
            Keyword.Type.FALSE -> Value.Bool(false).toSuccess()
            else               -> failureOf("'${keyword.type.word}' is not a bool")
        }
    }
}

/**
 * Specifies a pattern where exactly one real value will be extracted from the context.
 */
object ParserReal : Pattern<Expression>
{
    override fun parse(context: Context): Result<Value.Real, String>
    {
        val token = context.next() ?: return failureOf("expected token, found end of stream")
        val number = token as? Numeric ?: return failureOf("'${token.raw}' is not a number")
        return Value.Real(number.value, number.type).toSuccess()
    }
}

/**
 * Specifies a pattern where exactly one text value will be extracted from the context.
 */
object ParserText : Pattern<Expression>
{
    override fun parse(context: Context): Result<Value.Text, String>
    {
        val token = context.next() ?: return failureOf("expected token, found end of stream")
        val string = token as? Textual ?: return failureOf("'${token.raw}' is not a string")
        return Value.Text(string.value, string.type).toSuccess()
    }
}

/**
 * Specifies a pattern where exactly one identifier will be extracted from the context. The type of identifier is not
 * known at the extraction time, and may be a variable, function, namespace, etc. Note that the variable parser may
 * extract a result which should be a function instead.
 */
object ParserVariable : Pattern<Expression>
{
    override fun parse(context: Context): Result<Access.Variable, String>
    {
        val identifier = parseIdentifier(context).valueOr { return failureOf(it) }
        return Access.Variable(identifier.name).toSuccess()
    }
}

/**
 * Specifies a pattern where exactly one identifier will be extracted from the context, alongside the required
 * parenthesis and optional parameters.
 */
object ParserFunction : Pattern<Expression>
{
    private val open = ParseStructure(Structure.Type.OPEN_PARENTHESIS)
    private val close = ParseStructure(Structure.Type.CLOSE_PARENTHESIS)
    private val comma = PatternOptional(ParseStructure(Structure.Type.COMMA), Unit)
    private val equal = ParseOperator(Operator.Type.ASSIGN)
    
    override fun parse(context: Context): Result<Expression, String>
    {
        val identifier = parseIdentifier(context).valueOr { return failureOf(it) }
        open.parse(context).valueOr { return failureOf(it) }
        val parameters = parseParameters(context)
        close.parse(context).valueOr { return failureOf(it) }
        return Access.Function(identifier.name, parameters).toSuccess()
    }
    
    private fun parseParameters(context: Context): List<Parameter>
    {
        val parameters = mutableListOf<Parameter>()
        while (true)
            parameters.add(parseParameter(context).valueOr { return parameters })
    }
    
    private fun parseParameter(context: Context): Result<Parameter, Unit>
    {
        val snapshot = context.snapshot()
        val identifier = parseIdentifier(context)
            .andThen { equal.parse(context) }
            .onFailure { context.restore(snapshot) }
            .valueOr { null }
        val expression = ParserExpression.parse(context)
            .andThen { comma.parse(context) }
            .onFailure { context.restore(snapshot) }
            .valueOr { return failureOf() }
        return Parameter(identifier?.name, expression).toSuccess()
    }
}


/**
 * Specifies a pattern where the [operator] component is required. The next token in line must be the specific operator.
 */
class ParseOperator(private val operator: Operator.Type) : Pattern<Unit>
{
    override fun parse(context: Context): Result<Unit, String>
    {
        val token = context.next() ?: return failureOf("expected '${operator.word}', found end of stream")
        return if (token is Operator && token.type == operator)
            successOf()
        else
            failureOf("expected '${operator.word}', found '${token.raw}'")
    }
}

/**
 * Specifies a pattern where the [structure] component is required. The next token in line must be the specific
 * structure.
 */
class ParseStructure(private val structure: Structure.Type) : Pattern<Unit>
{
    override fun parse(context: Context): Result<Unit, String>
    {
        val token = context.next() ?: return failureOf("expected '${structure.word}', found end of stream")
        return if (token is Structure && token.type == structure)
            successOf()
        else
            failureOf("expected '${structure.word}', found '${token.raw}'")
    }
}


/**
 * Certain operators act only on one expression to their immediate right. Such operators require special care when
 * parsing source code, to ensure precedence rules are respected.
 */
object ParserPrefixOperator : Pattern<Expression>
{
    override fun parse(context: Context): Result<Expression, String>
    {
        val op = parseOperator(context).valueOr { return failureOf(it) }
        val rhs = ParserSubExpression.parse(context).valueOr { return failureOf(it) }
        return join(rhs, op)
    }
    
    private fun join(rhs: Expression, op: Operator): Result<Expression, String> = when
    {
        op.type == Operator.Type.PLUS  -> UnaryPlus(rhs).toSuccess()
        op.type == Operator.Type.MINUS -> UnaryMinus(rhs).toSuccess()
        op.type == Operator.Type.NOT   -> Not(rhs).toSuccess()
        rhs is Access.Variable         -> assign(rhs, op)
        else                           -> failureOf("'${op.type.word}' is not a legal prefix operator")
    }
    
    private fun assign(rhs: Access.Variable, op: Operator): Result<Expression, String> = when (op.type)
    {
        Operator.Type.DECREMENT -> PreDecrement(rhs).toSuccess()
        Operator.Type.INCREMENT -> PreIncrement(rhs).toSuccess()
        else                    -> failureOf("'${op.type.word}' is not a legal prefix operator")
    }
}

/**
 * Certain operators act only on one expression to their immediate left. Such operators require special care when
 * parsing source code, to ensure precedence rules are respected.
 */
object ParserPostfixOperator : Pattern<Expression>
{
    private val pattern = PatternAnyOf(
        ParserBool,
        ParserReal,
        ParserText,
        ParserVariable,
        ParserFunction,
        ParserParenthesis,
        ParserPrefixOperator,
    )
    
    override fun parse(context: Context): Result<Expression, String>
    {
        val lhs = pattern.parse(context).valueOr { return failureOf(it) }
        val op = parseOperator(context).valueOr { return failureOf(it) }
        if (lhs !is Access.Variable)
            return failureOf("'${op.type.word}' is not a legal postfix operator here")
        return join(lhs, op)
    }
    
    private fun join(lhs: Access.Variable, op: Operator): Result<Expression, String> = when (op.type)
    {
        Operator.Type.DECREMENT -> PostDecrement(lhs).toSuccess()
        Operator.Type.INCREMENT -> PostIncrement(lhs).toSuccess()
        else                    -> failureOf("'${op.type.word}' is not a legal postfix operator")
    }
}

/**
 * Many operators require a left-hand and right-hand expression when performing some operation. These operators require
 * special care when parsing as different operators have different precedence rules, which determines how expressions
 * should be parsed left-to-right.
 */
object ParserInfixOperator : Pattern<Expression>
{
    override fun parse(context: Context): Result<Expression, String>
    {
        val lhs = ParserSubExpression.parse(context).valueOr { return failureOf(it) }
        val op1 = parseOperator(context).valueOr { return failureOf(it) }
        return parseRecursive(context, lhs, op1)
    }
    
    private fun parseRecursive(context: Context, lhs: Expression, op1: Operator): Result<Expression, String>
    {
        val mhs = ParserSubExpression.parse(context).valueOr { return failureOf(it) }
        val snapshot = context.snapshot()
        val op2 = parseOperator(context).valueOr { return join(lhs, mhs, op1).also { context.restore(snapshot) } }
        
        if (precedence(op1) <= precedence(op2) && associativity(op2))
            return join(lhs, mhs, op1).flatMap { parseRecursive(context, it, op2) }
        
        val rhs = ParserExpression.parse(context).valueOr { return failureOf(it) }
        return join(mhs, rhs, op2).flatMap { join(lhs, it, op1) }
    }
    
    private fun join(lhs: Expression, rhs: Expression, op: Operator): Result<Expression, String> = when
    {
        op.type == Operator.Type.AND           -> And(lhs, rhs).toSuccess()
        op.type == Operator.Type.DIVIDE        -> Divide(lhs, rhs).toSuccess()
        op.type == Operator.Type.EQUAL         -> Equal(lhs, rhs).toSuccess()
        op.type == Operator.Type.GREATER       -> Greater(lhs, rhs).toSuccess()
        op.type == Operator.Type.GREATER_EQUAL -> GreaterEqual(lhs, rhs).toSuccess()
        op.type == Operator.Type.LESS          -> Less(lhs, rhs).toSuccess()
        op.type == Operator.Type.LESS_EQUAL    -> LessEqual(lhs, rhs).toSuccess()
        op.type == Operator.Type.MINUS         -> Subtract(lhs, rhs).toSuccess()
        op.type == Operator.Type.MODULO        -> Modulo(lhs, rhs).toSuccess()
        op.type == Operator.Type.MULTIPLY      -> Multiply(lhs, rhs).toSuccess()
        op.type == Operator.Type.NOT_EQUAL     -> NotEqual(lhs, rhs).toSuccess()
        op.type == Operator.Type.OR            -> Or(lhs, rhs).toSuccess()
        op.type == Operator.Type.PLUS          -> Add(lhs, rhs).toSuccess()
        op.type == Operator.Type.THREE_WAY     -> ThreeWay(lhs, rhs).toSuccess()
        op.type == Operator.Type.XOR           -> Xor(lhs, rhs).toSuccess()
        lhs is Access.Variable                 -> assign(lhs, rhs, op)
        else                                   -> failureOf("'${op.type.word}' is not a legal operator")
    }
    
    private fun assign(lhs: Access.Variable, rhs: Expression, op: Operator): Result<Expression, String> = when (op.type)
    {
        Operator.Type.ASSIGN          -> Assign(lhs, rhs).toSuccess()
        Operator.Type.ASSIGN_DIVIDE   -> AssignDivide(lhs, rhs).toSuccess()
        Operator.Type.ASSIGN_MINUS    -> AssignSubtract(lhs, rhs).toSuccess()
        Operator.Type.ASSIGN_MODULO   -> AssignModulo(lhs, rhs).toSuccess()
        Operator.Type.ASSIGN_MULTIPLY -> AssignMultiply(lhs, rhs).toSuccess()
        Operator.Type.ASSIGN_PLUS     -> AssignAdd(lhs, rhs).toSuccess()
        else                          -> failureOf("'${op.type.word}' is not a legal operator")
    }
    
    private fun precedence(op: Operator): Int = when (op.type)
    {
        Operator.Type.ASSIGN          -> 7
        Operator.Type.ASSIGN_DIVIDE   -> 7
        Operator.Type.ASSIGN_MINUS    -> 7
        Operator.Type.ASSIGN_MODULO   -> 7
        Operator.Type.ASSIGN_MULTIPLY -> 7
        Operator.Type.ASSIGN_PLUS     -> 7
        Operator.Type.AND             -> 4
        Operator.Type.DIVIDE          -> 0
        Operator.Type.EQUAL           -> 3
        Operator.Type.GREATER         -> 3
        Operator.Type.GREATER_EQUAL   -> 3
        Operator.Type.LESS            -> 3
        Operator.Type.LESS_EQUAL      -> 3
        Operator.Type.MINUS           -> 1
        Operator.Type.MODULO          -> 0
        Operator.Type.MULTIPLY        -> 0
        Operator.Type.NOT_EQUAL       -> 3
        Operator.Type.OR              -> 5
        Operator.Type.PLUS            -> 1
        Operator.Type.THREE_WAY       -> 2
        Operator.Type.XOR             -> 6
        else                          -> TODO("no precedence associated with operator ${op.type.word}")
    }
    
    private fun associativity(op: Operator): Boolean = when (op.type)
    {
        Operator.Type.ASSIGN,
        Operator.Type.ASSIGN_DIVIDE,
        Operator.Type.ASSIGN_MINUS,
        Operator.Type.ASSIGN_MODULO,
        Operator.Type.ASSIGN_MULTIPLY,
        Operator.Type.ASSIGN_PLUS,
             -> false
        else -> true
    }
}

/**
 * Certain expressions are disambiguated with parentheses, or otherwise use parentheses to force a specific evaluation
 * order. The parentheses are discarded after parsing the source code, although they are baked into the ast after
 * parsing.
 */
object ParserParenthesis : Pattern<Expression>
{
    private val open = ParseStructure(Structure.Type.OPEN_PARENTHESIS)
    private val close = ParseStructure(Structure.Type.CLOSE_PARENTHESIS)
    
    override fun parse(context: Context): Result<Expression, String>
    {
        open.parse(context).valueOr { return failureOf(it) }
        val expression = ParserExpression.parse(context).valueOr { return failureOf(it) }
        close.parse(context).valueOr { return failureOf(it) }
        return successOf(expression)
    }
}

/**
 * Retrieves the next token from the [context] as an identifier, if possible.
 */
private fun parseIdentifier(context: Context): Result<Identifier, String>
{
    val token = context.next() ?: return failureOf("expected token, found end of stream")
    val identifier = token as? Identifier ?: return failureOf("'${token.raw}' is not an identifier")
    return identifier.toSuccess()
}

/**
 * Retrieves the next token from the [context] as an operator, if possible.
 */
private fun parseOperator(context: Context): Result<Operator, String>
{
    val token = context.next() ?: return failureOf("expected token, found end of stream")
    val operator = token as? Operator ?: return failureOf("'${token.raw}' is not an operator")
    return operator.toSuccess()
}

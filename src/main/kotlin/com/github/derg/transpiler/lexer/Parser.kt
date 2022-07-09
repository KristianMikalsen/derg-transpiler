package com.github.derg.transpiler.lexer

import com.github.derg.transpiler.core.Localized
import com.github.derg.transpiler.core.Node
import com.github.derg.transpiler.core.NodeAssignment.*
import com.github.derg.transpiler.core.NodeExpression
import com.github.derg.transpiler.core.NodeExpression.*
import com.github.derg.transpiler.core.NodeExpression.Function
import com.github.derg.transpiler.core.ParameterNode
import com.github.derg.transpiler.util.indexOfFirstOrNull

/**
 *
 */
fun parse(tokens: List<Localized<Token>>): List<Node> = NodeExtractor(tokens.map { it.data }).toList()

/**
 *
 */
private class NodeExtractor(private val input: List<Token>) : Iterator<Node>, Iterable<Node>
{
    private var cursor = 0
    
    override fun iterator(): Iterator<Node> = this
    override fun hasNext(): Boolean = extractNode(input, cursor) != null
    override fun next(): Node
    {
        val (node, index) = extractNode(input, cursor)
            ?: throw IllegalStateException("No node was found in '$input' at position $cursor")
        cursor = index
        return node
    }
}

/**
 * Defines the interface which must be respected by any parser. Parsers are used to retrieve the next AST node in the
 * source code, although not all parsers are capable of understanding the source at the cursor position.
 */
private typealias NodeParser = (List<Token>, Int) -> Pair<Node, Int>?
private typealias Parsed = Pair<NodeExpression, Int>?
private typealias ExpressionParser = (List<Token>, Int) -> Parsed

private val Token.isComma: Boolean get() = (this is Structure && type == Structure.Type.COMMA)
private val Token.isOpenParenthesis: Boolean get() = (this is Structure && type == Structure.Type.OPEN_PARENTHESIS)
private val Token.isCloseParenthesis: Boolean get() = (this is Structure && type == Structure.Type.CLOSE_PARENTHESIS)

/**
 * The collection of all parsers which are capable of extracting a node from source code. Exactly one parser must be
 * used to retrieve the next node from the source code.
 */
private val PARSERS: List<NodeParser> = listOf(
    ::extractExpressionTmp,
)

private val EXPRESSIONS: List<ExpressionParser> = listOf(
    ::extractUnary,
    ::extractNot,
    ::extractIncrementOrDecrement,
    ::extractExpression,
    ::extractAssignment,
)

/**
 * Extracts the node at the [cursor] position from the [input] tokens.
 */
private fun extractNode(input: List<Token>, cursor: Int): Pair<Node, Int>?
{
    if (cursor >= input.size)
        return null
    
    return PARSERS.mapNotNull { it(input, cursor) }.maxByOrNull { it.second }
        ?: throw IllegalStateException("No parsers matched '$input' at position $cursor")
}

/**
 * Converts the [input] tokens into an expression if possible, starting at [cursor].
 */
private fun extractExpressionTmp(input: List<Token>, cursor: Int): Parsed =
    EXPRESSIONS.mapNotNull { it(input, cursor) }.maxByOrNull { it.second }

private fun extractIncrementOrDecrement(input: List<Token>, cursor: Int): Parsed
{
    if (cursor + 1 >= input.size)
        return null
    
    val isPre = input[cursor] is Operator
    val operator = input[if (isPre) cursor else cursor + 1] as? Operator ?: return null
    val variable = input[if (isPre) cursor + 1 else cursor] as? Identifier ?: return null
    
    return when (operator.type)
    {
        Operator.Type.DECREMENT -> if (isPre) DecrementPre(variable.name) else DecrementPost(variable.name)
        Operator.Type.INCREMENT -> if (isPre) IncrementPre(variable.name) else IncrementPost(variable.name)
        else                    -> return null
    } to cursor + 2
}

private fun extractUnary(input: List<Token>, cursor: Int): Parsed
{
    val token = input[cursor] as? Operator ?: return null
    if (token.type != Operator.Type.MINUS || cursor >= input.size)
        return null
    
    val (expression, index) = extractExpressionTmp(input, cursor + 1) ?: return null
    return Unary(expression) to index
}

private fun extractNot(input: List<Token>, cursor: Int): Parsed
{
    val token = input[cursor] as? Operator ?: return null
    if (token.type != Operator.Type.NOT || cursor >= input.size)
        return null
    
    val (expression, index) = extractExpressionTmp(input, cursor + 1) ?: return null
    return LogicalNot(expression) to index
}

/**
 * Extracts a constant value expression from the [input] tokens at the [cursor] position. The constant value may be
 * either a numeric, a textual value, a boolean `true` or `false`, or either of those followed by a user-defined
 * literal.
 */
private fun extractConstant(input: List<Token>, cursor: Int): Parsed
{
    // TODO: Implement handling of custom literals
    return when (val token = input.getOrNull(cursor))
    {
        is Numeric -> NodeExpression.Numeric(token.value)
        is Textual -> NodeExpression.Textual(token.value)
        is Keyword -> convertToBoolExpression(token) ?: return null
        else       -> return null
    } to cursor + 1
}

private fun convertToBoolExpression(token: Keyword): Bool? = when (token.type)
{
    Keyword.Type.TRUE  -> Bool(true)
    Keyword.Type.FALSE -> Bool(false)
    else               -> null
}

/**
 * Extracts a single variable assignment node from the [input] tokens at the [cursor] position. All assignment nodes
 * must contain a valid expression.
 */
private fun extractAssignment(input: List<Token>, cursor: Int): Parsed
{
    val identifier = input.getOrNull(cursor) as? Identifier ?: return null
    val operator = input.getOrNull(cursor + 1) as? Operator ?: return null
    val (expression, index) = extractExpression(input, cursor + 2) ?: return null
    
    return when (operator.type)
    {
        Operator.Type.ASSIGN          -> Assign(identifier.name, expression)
        Operator.Type.ASSIGN_PLUS     -> AssignPlus(identifier.name, expression)
        Operator.Type.ASSIGN_MINUS    -> AssignMinus(identifier.name, expression)
        Operator.Type.ASSIGN_MULTIPLY -> AssignMultiply(identifier.name, expression)
        Operator.Type.ASSIGN_DIVIDE   -> AssignDivide(identifier.name, expression)
        else                          -> return null
    } to index + 1
}

/**
 * Extracts a single leaf expression from the [input] tokens at the [cursor] position. The legal leaf expressions are
 * constants (including custom literals), variable reads, and function calls.
 */
private fun extractLeafExpression(input: List<Token>, cursor: Int): Parsed
{
    // Certain expressions are entire trees of expressions, where the contents in parentheses must be unpacked
    if (input.getOrNull(cursor)?.isOpenParenthesis == true)
    {
        val (expression, index) = extractExpression(input, cursor + 1) ?: return null
        if (input.getOrNull(index)?.isCloseParenthesis != true) return null // Require a close parenthesis
        return expression to index + 1
    }
    
    // Otherwise, assume the next expression is an ordinary leaf expression and work from there
    return extractFunctionCall(input, cursor) ?: extractVariableRead(input, cursor) ?: extractConstant(input, cursor)
}

private fun extractVariableRead(input: List<Token>, cursor: Int): Parsed
{
    val identifier = input.getOrNull(cursor) as? Identifier ?: return null
    return Variable(identifier.name) to cursor + 1
}

private fun extractFunctionCall(input: List<Token>, cursor: Int): Parsed
{
    val identifier = input.getOrNull(cursor) as? Identifier ?: return null
    val parameters = mutableListOf<ParameterNode>()
    
    var current = input.indexOfFirstOrNull(cursor + 1) { it.isOpenParenthesis } ?: return null
    while (++current < input.size)
    {
        if (input[current].isCloseParenthesis) break // Early bail if we find the end of the function call
        
        val (parameter, next) = extractFunctionParameter(input, current) ?: return null
        parameters.add(parameter)
        current = next
        
        if (current >= input.size) return null // Terminating due to no more tokens is not permitted
        if (input[current].isCloseParenthesis) break // Late bail if we find the end of the function call
        if (!input[current].isComma) return null // Require comma-separated parameters
    }
    return Function(identifier.name, parameters) to current + 1
}

private fun extractFunctionParameter(input: List<Token>, cursor: Int): Pair<ParameterNode, Int>?
{
    val id = input.getOrNull(cursor) as? Identifier
    val op = input.getOrNull(cursor + 1) as? Operator
    
    if (id != null && op?.type != Operator.Type.ASSIGN) return null // Require equal symbol if assignment
    
    val (expression, index) = when (id == null)
    {
        true  -> extractExpression(input, cursor) ?: return null
        false -> extractExpression(input, cursor + 2) ?: return null
    }
    return ParameterNode(id?.name, expression) to index
}

/**
 * Extracts a full expression AST from the [input] tokens at the [cursor] position. The expression will contain all sub-
 * expressions relevant to the extracted expression, parsed according to all operator precedences.
 */
private fun extractExpression(input: List<Token>, cursor: Int): Parsed
{
    val (lhs, index) = extractLeafExpression(input, cursor) ?: return null
    val operator = input.getOrNull(index) as? Operator ?: return lhs to index
    return extractInfixOperator(input, index + 1, lhs, operator)
}

/**
 * Extracts an infix operator on the form `lhs` `op` `rhs` to a single node representing the operation, while ensuring
 * operator precedence is respected. The node is pulled from the [input] tokens at the [cursor] position, where [currEx]
 * is the previously seen expression and [currOp] is the previously seen operator.
 *
 * The infix operator looks at three expressions and two operators at a time, to determine how the nodes should be
 * spliced together. Based on the operator precedence, the final node will be either (`lhs` `op1` `mhs`) `op2` `rhs` or
 * `lhs` `op1` (`mhs` `op2` `rhs`).
 *
 * If an insufficient number of tokens were found in the [input], the final node produce will be `lhs` `op` `rhs`.
 */
private fun extractInfixOperator(input: List<Token>, cursor: Int, currEx: NodeExpression, currOp: Operator): Parsed
{
    val (rhs, index) = extractLeafExpression(input, cursor) ?: return null
    val joined = join(currEx, rhs, currOp) ?: return null
    
    // If no more operators exists at this point, we are done parsing infix operators. Otherwise, if the current
    // operator has a higher priority than the next one, lhs + rhs is considered left-hand, otherwise only lhs is.
    val nextOp = input.getOrNull(index) as? Operator ?: return joined to index
    if (nextOp.type.priority <= currOp.type.priority)
        return extractInfixOperator(input, index + 1, joined, nextOp)
    
    // If only lhs is left-only, we must recursively parse the right-hand expression
    val (remainder, end) = extractExpression(input, index + 1) ?: return null
    val combined = join(rhs, remainder, nextOp) ?: return null
    return join(currEx, combined, currOp)?.let { it to end + 1 }
}

/**
 * Joins together the [lhs] and [rhs] expressions based on a specific [operator], returning the node which represents
 * the operation applied to the two nodes.
 */
private fun join(lhs: NodeExpression, rhs: NodeExpression, operator: Operator): NodeExpression? = when (operator.type)
{
    Operator.Type.PLUS          -> Plus(lhs, rhs)
    Operator.Type.MINUS         -> Minus(lhs, rhs)
    Operator.Type.MULTIPLY      -> Multiply(lhs, rhs)
    Operator.Type.DIVIDE        -> Divide(lhs, rhs)
    Operator.Type.AND           -> LogicalAnd(lhs, rhs)
    Operator.Type.OR            -> LogicalOr(lhs, rhs)
    Operator.Type.XOR           -> LogicalXor(lhs, rhs)
    Operator.Type.LESS          -> Less(lhs, rhs)
    Operator.Type.LESS_EQUAL    -> LessEqual(lhs, rhs)
    Operator.Type.GREATER       -> Greater(lhs, rhs)
    Operator.Type.GREATER_EQUAL -> GreaterEqual(lhs, rhs)
    Operator.Type.EQUAL         -> Equal(lhs, rhs)
    Operator.Type.NOT_EQUAL     -> NotEqual(lhs, rhs)
    Operator.Type.THREE_WAY     -> ThreeWay(lhs, rhs)
    else                        -> null
}

/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.eclipse.parsson.jsonpath;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

import jakarta.json.JsonException;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.jsonpath.Ast.And;
import org.eclipse.parsson.jsonpath.Ast.Argument;
import org.eclipse.parsson.jsonpath.Ast.Comparison;
import org.eclipse.parsson.jsonpath.Ast.ComparisonOp;
import org.eclipse.parsson.jsonpath.Ast.Expr;
import org.eclipse.parsson.jsonpath.Ast.Filter;
import org.eclipse.parsson.jsonpath.Ast.Function;
import org.eclipse.parsson.jsonpath.Ast.FunctionCall;
import org.eclipse.parsson.jsonpath.Ast.FunctionType;
import org.eclipse.parsson.jsonpath.Ast.Index;
import org.eclipse.parsson.jsonpath.Ast.Literal;
import org.eclipse.parsson.jsonpath.Ast.Name;
import org.eclipse.parsson.jsonpath.Ast.Not;
import org.eclipse.parsson.jsonpath.Ast.Operand;
import org.eclipse.parsson.jsonpath.Ast.Or;
import org.eclipse.parsson.jsonpath.Ast.Paren;
import org.eclipse.parsson.jsonpath.Ast.Query;
import org.eclipse.parsson.jsonpath.Ast.QueryOperand;
import org.eclipse.parsson.jsonpath.Ast.Segment;
import org.eclipse.parsson.jsonpath.Ast.Selector;
import org.eclipse.parsson.jsonpath.Ast.Slice;
import org.eclipse.parsson.jsonpath.Ast.Test;
import org.eclipse.parsson.jsonpath.Ast.Wildcard;

/**
 * Recursive-descent parser for the RFC 9535 grammar (appendix A), including the well-typedness
 * rules for function expressions (section 2.4.3). Every error is a {@link JsonException} whose
 * message gives the reason, the position and an excerpt of the query.
 */
final class Parser {

    /** Largest integer allowed in index and slice selectors: 2^53 - 1 (I-JSON range). */
    private static final long MAX_INT = (1L << 53) - 1;

    /** Maximum nesting of filters, parentheses and function calls, to bound recursion on hostile input. */
    static final int MAX_NESTING = 64;

    private final String src;
    private final JsonProvider provider;
    private int pos;
    private int nesting;

    /** Start position of every test expression, so that type errors point at the operand. */
    private final IdentityHashMap<Test, Integer> testPositions = new IdentityHashMap<>();

    private Parser(String src, JsonProvider provider) {
        this.src = src;
        this.provider = provider;
    }

    /**
     * Parses a query.
     *
     * @param query the query string
     * @param provider creates the values of string and number literals
     * @return the syntax tree
     * @throws JsonException if the query is not well-formed and valid
     */
    static Query parse(String query, JsonProvider provider) {
        Parser parser = new Parser(query, provider);
        if (parser.peek() != '$') {
            throw parser.error("path.syntax.root");
        }
        parser.pos++;
        Query result = new Query(true, parser.segments());
        if (parser.pos != query.length()) {
            throw parser.error("path.syntax.unexpected.char");
        }
        return result;
    }

    // ---- segments and selectors ----

    private List<Segment> segments() {
        List<Segment> segments = new ArrayList<>();
        while (true) {
            int save = pos;
            skipBlanks();
            int c = peek();
            if (c == '.' || c == '[') {
                segments.add(segment());
            } else {
                pos = save;
                return unmodifiable(segments);
            }
        }
    }

    private Segment segment() {
        if (src.startsWith("..", pos)) {
            pos += 2;
            int c = peek();
            if (c == '[') {
                return new Segment(true, bracketedSelection(), false);
            }
            if (c == '*') {
                pos++;
                return new Segment(true, Collections.<Selector>singletonList(Wildcard.INSTANCE), false);
            }
            if (isNameFirst(c)) {
                return new Segment(true, Collections.<Selector>singletonList(new Name(memberNameShorthand())), false);
            }
            throw error("path.syntax.descendant");
        }
        if (peek() == '.') {
            pos++;
            if (peek() == '*') {
                pos++;
                return new Segment(false, Collections.<Selector>singletonList(Wildcard.INSTANCE), false);
            }
            if (isNameFirst(peek())) {
                return new Segment(false, Collections.<Selector>singletonList(new Name(memberNameShorthand())), true);
            }
            throw error("path.syntax.child");
        }
        int start = pos;
        List<Selector> selectors = bracketedSelection();
        boolean singular = selectors.size() == 1
                && (selectors.get(0) instanceof Name || selectors.get(0) instanceof Index)
                && !isBlank(src.charAt(start + 1))
                && !isBlank(src.charAt(pos - 2));
        return new Segment(false, selectors, singular);
    }

    private List<Selector> bracketedSelection() {
        expect('[');
        List<Selector> selectors = new ArrayList<>();
        skipBlanks();
        selectors.add(selector());
        skipBlanks();
        while (peek() == ',') {
            pos++;
            skipBlanks();
            selectors.add(selector());
            skipBlanks();
        }
        expect(']');
        return unmodifiable(selectors);
    }

    private Selector selector() {
        int c = peek();
        if (c == '\'' || c == '"') {
            return new Name(stringLiteral());
        }
        if (c == '*') {
            pos++;
            return Wildcard.INSTANCE;
        }
        if (c == '?') {
            pos++;
            skipBlanks();
            enter();
            Expr expr = logicalOr();
            nesting--;
            checkLogical(expr);
            return new Filter(expr);
        }
        if (c == '-' || isDigit(c) || c == ':') {
            return indexOrSlice();
        }
        throw error("path.syntax.selector");
    }

    private Selector indexOrSlice() {
        Long start = optionalInt();
        if (start != null) {
            skipBlanks();
        }
        if (peek() != ':') {
            if (start == null) {
                throw error("path.syntax.integer");
            }
            return new Index(start);
        }
        pos++;
        skipBlanks();
        Long end = optionalInt();
        if (end != null) {
            skipBlanks();
        }
        Long step = null;
        if (peek() == ':') {
            pos++;
            skipBlanks();
            step = optionalInt();
        }
        return new Slice(start, end, step);
    }

    /** An integer of an index or slice selector, or {@code null} if there is none at the position. */
    private Long optionalInt() {
        int c = peek();
        if (c != '-' && !isDigit(c)) {
            return null;
        }
        int start = pos;
        if (c == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
            if (c == '-') {
                throw error("path.syntax.minus.zero", start);
            }
            if (isDigit(peek())) {
                throw error("path.syntax.leading.zero", start);
            }
            return 0L;
        }
        if (!isDigit(peek())) {
            throw error("path.syntax.digit");
        }
        while (isDigit(peek())) {
            pos++;
        }
        long value;
        try {
            value = Long.parseLong(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("path.syntax.integer.range", start);
        }
        if (value > MAX_INT || value < -MAX_INT) {
            throw error("path.syntax.integer.range", start);
        }
        return value;
    }

    private String memberNameShorthand() {
        int start = pos;
        while (pos < src.length() && isNameChar(peek())) {
            pos += Character.charCount(peek());
        }
        return src.substring(start, pos);
    }

    // ---- filter expressions ----

    private Expr logicalOr() {
        List<Expr> operands = new ArrayList<>();
        operands.add(logicalAnd());
        while (true) {
            int save = pos;
            skipBlanks();
            if (src.startsWith("||", pos)) {
                pos += 2;
                skipBlanks();
                operands.add(logicalAnd());
            } else {
                pos = save;
                break;
            }
        }
        return operands.size() == 1 ? operands.get(0) : new Or(unmodifiable(operands));
    }

    private Expr logicalAnd() {
        List<Expr> operands = new ArrayList<>();
        operands.add(basicExpr());
        while (true) {
            int save = pos;
            skipBlanks();
            if (src.startsWith("&&", pos)) {
                pos += 2;
                skipBlanks();
                operands.add(basicExpr());
            } else {
                pos = save;
                break;
            }
        }
        return operands.size() == 1 ? operands.get(0) : new And(unmodifiable(operands));
    }

    private Expr basicExpr() {
        if (peek() == '!') {
            pos++;
            skipBlanks();
            if (peek() == '(') {
                return new Not(parenExpr());
            }
            int start = pos;
            Operand operand = operand();
            if (operand instanceof Literal) {
                throw error("path.syntax.not.operand", start);
            }
            int save = pos;
            skipBlanks();
            if (comparisonOp() != null) {
                throw error("path.syntax.not.comparison", save);
            }
            pos = save;
            return new Not(test(operand, start));
        }
        if (peek() == '(') {
            return parenExpr();
        }
        int start = pos;
        Operand left = operand();
        int save = pos;
        skipBlanks();
        ComparisonOp op = comparisonOp();
        if (op == null) {
            pos = save;
            return test(left, start);
        }
        skipBlanks();
        int rightStart = pos;
        Operand right = operand();
        checkComparable(left, start);
        checkComparable(right, rightStart);
        return new Comparison(left, op, right);
    }

    private Test test(Operand operand, int start) {
        Test test = new Test(operand);
        testPositions.put(test, start);
        return test;
    }

    private Expr parenExpr() {
        expect('(');
        enter();
        skipBlanks();
        Expr inner = logicalOr();
        skipBlanks();
        expect(')');
        nesting--;
        return new Paren(inner);
    }

    private void enter() {
        if (++nesting > MAX_NESTING) {
            throw error("path.syntax.nesting", pos, String.valueOf(MAX_NESTING));
        }
    }

    /** Reads a comparison operator, or returns {@code null} without moving if there is none. */
    private ComparisonOp comparisonOp() {
        // The operators are declared longest first, so that "<=" is not read as "<".
        for (ComparisonOp op : ComparisonOp.values()) {
            if (src.startsWith(op.symbol, pos)) {
                pos += op.symbol.length();
                return op;
            }
        }
        return null;
    }

    private Operand operand() {
        int c = peek();
        if (c == '@' || c == '$') {
            pos++;
            return new QueryOperand(new Query(c == '$', segments()));
        }
        if (c == '\'' || c == '"') {
            return new Literal(provider.createValue(stringLiteral()));
        }
        if (c == '-' || isDigit(c)) {
            return new Literal(provider.createValue(number()));
        }
        if (c >= 'a' && c <= 'z') {
            int start = pos;
            while (isFunctionNameChar(peek())) {
                pos++;
            }
            String name = src.substring(start, pos);
            if (peek() == '(') {
                return functionCall(name, start);
            }
            switch (name) {
                case "true":
                    return new Literal(JsonValue.TRUE);
                case "false":
                    return new Literal(JsonValue.FALSE);
                case "null":
                    return new Literal(JsonValue.NULL);
                default:
                    throw error("path.syntax.literal.unknown", start, name);
            }
        }
        throw error("path.syntax.operand");
    }

    private FunctionCall functionCall(String name, int start) {
        Function function = Function.byName(name);
        if (function == null) {
            throw error("path.syntax.function.unknown", start, name);
        }
        expect('(');
        enter();
        skipBlanks();
        List<Argument> arguments = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        if (peek() != ')') {
            while (true) {
                positions.add(pos);
                arguments.add(argument());
                skipBlanks();
                if (peek() == ',') {
                    pos++;
                    skipBlanks();
                } else {
                    break;
                }
            }
        }
        expect(')');
        nesting--;
        if (arguments.size() != function.parameterCount()) {
            throw error("path.syntax.function.arity", start, name, String.valueOf(function.parameterCount()));
        }
        for (int i = 0; i < arguments.size(); i++) {
            checkArgument(arguments.get(i), function.parameter(i), positions.get(i));
        }
        return new FunctionCall(function, unmodifiable(arguments));
    }

    /** An argument is parsed as a logical expression; a bare operand is unwrapped. */
    private Argument argument() {
        Expr expr = logicalOr();
        if (expr instanceof Test) {
            return ((Test) expr).operand;
        }
        return expr;
    }

    // ---- type checking (RFC 9535, section 2.4.3) ----

    private void checkComparable(Operand operand, int at) {
        if (operand instanceof QueryOperand && !((QueryOperand) operand).query.isSingular()) {
            throw error("path.syntax.compare.query", at);
        }
        if (operand instanceof FunctionCall && ((FunctionCall) operand).function.result != FunctionType.VALUE) {
            throw error("path.syntax.compare.function", at);
        }
    }

    private void checkLogical(Expr expr) {
        if (expr instanceof Or) {
            for (Expr operand : ((Or) expr).operands) {
                checkLogical(operand);
            }
        } else if (expr instanceof And) {
            for (Expr operand : ((And) expr).operands) {
                checkLogical(operand);
            }
        } else if (expr instanceof Not) {
            checkLogical(((Not) expr).operand);
        } else if (expr instanceof Paren) {
            checkLogical(((Paren) expr).operand);
        } else if (expr instanceof Test) {
            Operand operand = ((Test) expr).operand;
            Integer at = testPositions.get(expr);
            int position = at != null ? at : pos;
            if (operand instanceof Literal) {
                throw error("path.syntax.test.literal", position);
            }
            if (operand instanceof FunctionCall && ((FunctionCall) operand).function.result == FunctionType.VALUE) {
                throw error("path.syntax.test.function", position, ((FunctionCall) operand).function.functionName);
            }
        }
    }

    private void checkArgument(Argument argument, FunctionType type, int at) {
        boolean ok;
        switch (type) {
            case VALUE:
                ok = argument instanceof Literal
                        || (argument instanceof QueryOperand && ((QueryOperand) argument).query.isSingular())
                        || (argument instanceof FunctionCall
                                && ((FunctionCall) argument).function.result == FunctionType.VALUE);
                break;
            case LOGICAL:
                if (argument instanceof Expr) {
                    checkLogical((Expr) argument);
                    ok = true;
                } else {
                    ok = argument instanceof QueryOperand
                            || (argument instanceof FunctionCall
                                    && ((FunctionCall) argument).function.result != FunctionType.VALUE);
                }
                break;
            default:
                ok = argument instanceof QueryOperand
                        || (argument instanceof FunctionCall
                                && ((FunctionCall) argument).function.result == FunctionType.NODES);
                break;
        }
        if (!ok) {
            throw error("path.syntax.function.argument", at, type.name());
        }
    }

    // ---- literals ----

    /** A number literal, parsed exactly, never through {@code double}. */
    private BigDecimal number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
            if (isDigit(peek())) {
                throw error("path.syntax.leading.zero", start);
            }
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("path.syntax.digit");
        }
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw error("path.syntax.fraction");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("path.syntax.exponent");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        try {
            return new BigDecimal(src.substring(start, pos));
        } catch (NumberFormatException | ArithmeticException e) {
            throw error("path.syntax.number.range", start);
        }
    }

    private String stringLiteral() {
        int quote = src.charAt(pos);
        int start = pos;
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("path.syntax.string.unterminated", start);
            }
            int c = src.codePointAt(pos);
            if (c == quote) {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                escape(quote, sb);
                continue;
            }
            if (c < 0x20) {
                throw error("path.syntax.string.control");
            }
            if (Character.isSurrogate(src.charAt(pos)) && Character.charCount(c) == 1) {
                throw error("path.syntax.string.surrogate");
            }
            sb.appendCodePoint(c);
            pos += Character.charCount(c);
        }
    }

    private void escape(int quote, StringBuilder sb) {
        if (pos >= src.length()) {
            throw error("path.syntax.string.unterminated", pos - 1);
        }
        char c = src.charAt(pos++);
        switch (c) {
            case 'b':
                sb.append('\b');
                break;
            case 'f':
                sb.append('\f');
                break;
            case 'n':
                sb.append('\n');
                break;
            case 'r':
                sb.append('\r');
                break;
            case 't':
                sb.append('\t');
                break;
            case '/':
                sb.append('/');
                break;
            case '\\':
                sb.append('\\');
                break;
            case '\'':
            case '"':
                if (c != quote) {
                    throw error("path.syntax.escape", pos - 2);
                }
                sb.append(c);
                break;
            case 'u':
                int unit = hex4();
                if (unit >= 0xD800 && unit <= 0xDBFF) {
                    if (!src.startsWith("\\u", pos)) {
                        throw error("path.syntax.surrogate.high");
                    }
                    pos += 2;
                    int low = hex4();
                    if (low < 0xDC00 || low > 0xDFFF) {
                        throw error("path.syntax.surrogate.high");
                    }
                    sb.append((char) unit).append((char) low);
                } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                    throw error("path.syntax.surrogate.low");
                } else {
                    sb.append((char) unit);
                }
                break;
            default:
                throw error("path.syntax.escape", pos - 2);
        }
    }

    private int hex4() {
        if (pos + 4 > src.length()) {
            throw error("path.syntax.hex");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = src.charAt(pos + i);
            boolean hex = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                throw error("path.syntax.hex");
            }
            value = value * 16 + Character.digit(c, 16);
        }
        pos += 4;
        return value;
    }

    // ---- character classes ----

    private static boolean isBlank(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNameFirst(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '_'
                || (c >= 0x80 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0x10FFFF);
    }

    private static boolean isNameChar(int c) {
        return isNameFirst(c) || isDigit(c);
    }

    private static boolean isFunctionNameChar(int c) {
        return (c >= 'a' && c <= 'z') || c == '_' || isDigit(c);
    }

    private void skipBlanks() {
        while (pos < src.length() && isBlank(src.charAt(pos))) {
            pos++;
        }
    }

    private int peek() {
        if (pos >= src.length()) {
            return -1;
        }
        char c = src.charAt(pos);
        if (Character.isHighSurrogate(c) && pos + 1 < src.length() && Character.isLowSurrogate(src.charAt(pos + 1))) {
            return src.codePointAt(pos);
        }
        // Unpaired surrogates are returned as is; they match no grammar rule.
        return c;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("path.syntax.expected", pos, String.valueOf(c));
        }
        pos++;
    }

    private static <T> List<T> unmodifiable(List<T> list) {
        return list.size() == 1 ? Collections.singletonList(list.get(0)) : Collections.unmodifiableList(list);
    }

    private JsonException error(String key) {
        return error(key, pos);
    }

    private JsonException error(String key, int at, Object... arguments) {
        return new JsonException(JsonPathMessages.syntaxError(JsonPathMessages.get(key, arguments), src, at));
    }
}

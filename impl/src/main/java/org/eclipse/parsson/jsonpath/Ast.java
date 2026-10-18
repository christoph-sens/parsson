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

import java.util.List;

import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Syntax tree of a parsed query. Produced by {@link Parser}, executed by {@link Evaluator}.
 * All nodes are immutable.
 */
final class Ast {

    private Ast() {
    }

    /** A query: {@code $} or {@code @} followed by segments. */
    static final class Query {

        final boolean absolute;
        final List<Segment> segments;

        Query(boolean absolute, List<Segment> segments) {
            this.absolute = absolute;
            this.segments = segments;
        }

        /** Whether this is a singular query (RFC 9535, section 2.3.5.1): it selects at most one node. */
        boolean isSingular() {
            for (Segment segment : segments) {
                if (!segment.singular) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A child or descendant segment. {@code singular} records whether the segment's syntax is a
     * singular segment ({@code .name}, {@code ['name']} or {@code [0]} without inner blanks).
     */
    static final class Segment {

        final boolean descendant;
        final List<Selector> selectors;
        final boolean singular;

        Segment(boolean descendant, List<Selector> selectors, boolean singular) {
            this.descendant = descendant;
            this.selectors = selectors;
            this.singular = singular;
        }
    }

    /** A selector: {@link Name}, {@link Wildcard}, {@link Index}, {@link Slice} or {@link Filter}. */
    interface Selector {
    }

    static final class Name implements Selector {

        final String name;

        Name(String name) {
            this.name = name;
        }
    }

    static final class Wildcard implements Selector {

        static final Wildcard INSTANCE = new Wildcard();

        private Wildcard() {
        }
    }

    static final class Index implements Selector {

        final long index;

        Index(long index) {
            this.index = index;
        }
    }

    /** A slice; {@code null} bounds mean "not given". */
    static final class Slice implements Selector {

        final Long start;
        final Long end;
        final Long step;

        Slice(Long start, Long end, Long step) {
            this.start = start;
            this.end = end;
            this.step = step;
        }
    }

    static final class Filter implements Selector {

        final Expr expr;

        Filter(Expr expr) {
            this.expr = expr;
        }
    }

    /** A function argument: an {@link Operand} or a logical expression ({@link Expr}). */
    interface Argument {
    }

    /**
     * Something that produces a value, a node list or a logical value: {@link Literal},
     * {@link QueryOperand} or {@link FunctionCall}.
     */
    interface Operand extends Argument {
    }

    /** A literal: a string, number, {@code true}, {@code false} or {@code null}. */
    static final class Literal implements Operand {

        final JsonValue value;

        Literal(JsonValue value) {
            this.value = value;
        }
    }

    static final class QueryOperand implements Operand {

        final Query query;

        QueryOperand(Query query) {
            this.query = query;
        }
    }

    static final class FunctionCall implements Operand {

        final Function function;
        final List<Argument> arguments;
        /** The compiled patterns of {@code match()} and {@code search()}; {@code null} for other functions. */
        final Patterns patterns;

        FunctionCall(Function function, List<Argument> arguments) {
            this.function = function;
            this.arguments = arguments;
            if (function == Function.MATCH || function == Function.SEARCH) {
                Argument pattern = arguments.get(1);
                boolean literal = pattern instanceof Literal && ((Literal) pattern).value instanceof JsonString;
                this.patterns = new Patterns(literal ? ((JsonString) ((Literal) pattern).value).getString() : null);
            } else {
                this.patterns = null;
            }
        }
    }

    /**
     * A logical expression: {@link Or}, {@link And}, {@link Not}, {@link Paren}, {@link Comparison}
     * or {@link Test}.
     */
    interface Expr extends Argument {
    }

    static final class Or implements Expr {

        final List<Expr> operands;

        Or(List<Expr> operands) {
            this.operands = operands;
        }
    }

    static final class And implements Expr {

        final List<Expr> operands;

        And(List<Expr> operands) {
            this.operands = operands;
        }
    }

    static final class Not implements Expr {

        final Expr operand;

        Not(Expr operand) {
            this.operand = operand;
        }
    }

    static final class Paren implements Expr {

        final Expr operand;

        Paren(Expr operand) {
            this.operand = operand;
        }
    }

    static final class Comparison implements Expr {

        final Operand left;
        final ComparisonOp op;
        final Operand right;

        Comparison(Operand left, ComparisonOp op, Operand right) {
            this.left = left;
            this.op = op;
            this.right = right;
        }
    }

    /** An existence test ({@code @.a}) or a logical function call ({@code match(@.a, 'x')}). */
    static final class Test implements Expr {

        final Operand operand;

        Test(Operand operand) {
            this.operand = operand;
        }
    }

    enum ComparisonOp {
        EQ("=="),
        NE("!="),
        LE("<="),
        GE(">="),
        LT("<"),
        GT(">");

        final String symbol;

        ComparisonOp(String symbol) {
            this.symbol = symbol;
        }
    }

    /** The declared types of function parameters and results (RFC 9535, section 2.4.1). */
    enum FunctionType {
        VALUE,
        LOGICAL,
        NODES
    }

    /** The functions of RFC 9535, sections 2.4.4 to 2.4.8. */
    enum Function {
        LENGTH("length", FunctionType.VALUE, FunctionType.VALUE),
        COUNT("count", FunctionType.VALUE, FunctionType.NODES),
        MATCH("match", FunctionType.LOGICAL, FunctionType.VALUE, FunctionType.VALUE),
        SEARCH("search", FunctionType.LOGICAL, FunctionType.VALUE, FunctionType.VALUE),
        VALUE("value", FunctionType.VALUE, FunctionType.NODES);

        final String functionName;
        final FunctionType result;
        private final FunctionType[] parameters;

        Function(String functionName, FunctionType result, FunctionType... parameters) {
            this.functionName = functionName;
            this.result = result;
            this.parameters = parameters;
        }

        int parameterCount() {
            return parameters.length;
        }

        FunctionType parameter(int index) {
            return parameters[index];
        }

        /** Returns the function with the given name, or {@code null} if there is none. */
        static Function byName(String name) {
            for (Function function : values()) {
                if (function.functionName.equals(name)) {
                    return function;
                }
            }
            return null;
        }
    }
}

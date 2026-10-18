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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.jsonpath.Ast.And;
import org.eclipse.parsson.jsonpath.Ast.Comparison;
import org.eclipse.parsson.jsonpath.Ast.Expr;
import org.eclipse.parsson.jsonpath.Ast.Filter;
import org.eclipse.parsson.jsonpath.Ast.Function;
import org.eclipse.parsson.jsonpath.Ast.FunctionCall;
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
import org.eclipse.parsson.jsonpath.regex.IRegexp;

/**
 * Executes a parsed query against a query argument (RFC 9535, sections 2.3 to 2.5).
 *
 * <p>An evaluator runs one query once. Absolute queries inside filters ({@code $[?@.a == $.b]}) do not
 * depend on the current node, so each is evaluated once per run and its result reused; otherwise nesting
 * them would multiply the work by the document size per level.
 *
 * <p>Descendant segments walk the document with an explicit stack, and {@code ==} compares values with an
 * explicit stack, so deep documents cannot overflow the call stack; the recursion that remains is bounded
 * by the query.
 */
final class Evaluator {

    private final JsonProvider provider;
    private final NodeImpl root;

    /** Results of absolute queries inside filters, created on first use. */
    private IdentityHashMap<Query, List<NodeImpl>> absoluteNodes;

    /**
     * Creates an evaluator.
     *
     * @param document the query argument
     * @param provider creates the numbers that {@code length()} and {@code count()} return
     */
    Evaluator(JsonValue document, JsonProvider provider) {
        this.provider = provider;
        this.root = NodeImpl.root(document);
    }

    /** Receives the nodes a query selects, in order; returns false to stop the evaluation. */
    private interface Sink {
        boolean accept(NodeImpl node);
    }

    /** Runs the query and returns the nodes it selects. */
    List<NodeImpl> run(Query query) {
        List<NodeImpl> result = new ArrayList<>();
        evaluate(query, root, node -> {
            result.add(node);
            return true;
        });
        return result;
    }

    /** Runs a query inside a filter; absolute queries are evaluated once per run. */
    private List<NodeImpl> query(Query query, NodeImpl current) {
        if (!query.absolute) {
            List<NodeImpl> nodes = new ArrayList<>();
            evaluate(query, current, nodes::add);
            return nodes;
        }
        if (absoluteNodes == null) {
            absoluteNodes = new IdentityHashMap<>();
        }
        List<NodeImpl> nodes = absoluteNodes.get(query);
        if (nodes == null) {
            List<NodeImpl> evaluated = new ArrayList<>();
            evaluate(query, root, evaluated::add);
            nodes = evaluated;
            absoluteNodes.put(query, nodes);
        }
        return nodes;
    }

    /** The first node a query inside a filter selects, or null; absolute queries use their result of this run. */
    private NodeImpl firstOf(Query query, NodeImpl current) {
        if (query.absolute) {
            List<NodeImpl> nodes = query(query, current);
            return nodes.isEmpty() ? null : nodes.get(0);
        }
        NodeImpl[] first = {null};
        evaluate(query, current, node -> {
            first[0] = node;
            return false;
        });
        return first[0];
    }

    /**
     * Evaluates a query from a start node and passes each selected node to the sink, in order. The nodes go
     * through the segments depth first: each node a segment selects is passed on to the next segment before
     * the segment selects the next one. This keeps the order of RFC 9535 (the result of a segment is the
     * concatenation of its results for each input node) without building a list per segment, and lets the
     * sink stop the evaluation early.
     */
    private void evaluate(Query query, NodeImpl start, Sink sink) {
        apply(query.segments, 0, start, sink);
    }

    /** Applies the segments from {@code index} on to one node; returns false if the sink stopped. */
    private boolean apply(List<Segment> segments, int index, NodeImpl node, Sink sink) {
        if (index == segments.size()) {
            return sink.accept(node);
        }
        Segment segment = segments.get(index);
        if (!segment.descendant) {
            return select(segment, node, null, 0, 0, segments, index + 1, sink);
        }
        // Descendant segment: the node and all its descendants in document order (each node before its
        // children, children in order), each with the segment's selectors. The nodes still to visit are on one
        // stack, the next on top. A node's children are pushed in order, so that wildcard and filter selectors
        // take them from there, and then reversed, so that the first is visited next; no list per node.
        NodeStack stack = new NodeStack();
        stack.push(node);
        while (stack.size > 0) {
            NodeImpl current = stack.pop();
            int from = stack.size;
            pushChildren(current, stack);
            int to = stack.size;
            if (!select(segment, current, stack.nodes, from, to, segments, index + 1, sink)) {
                return false;
            }
            stack.reverse(from, to);
        }
        return true;
    }

    /** The nodes a descendant segment still has to visit. */
    private static final class NodeStack {

        NodeImpl[] nodes = new NodeImpl[16];
        int size;

        void push(NodeImpl node) {
            if (size == nodes.length) {
                nodes = Arrays.copyOf(nodes, size * 2);
            }
            nodes[size++] = node;
        }

        /** Returns the top node; its slot is overwritten by later pushes, the stack lives for one walk only. */
        NodeImpl pop() {
            return nodes[--size];
        }

        void reverse(int from, int to) {
            for (int i = from, j = to - 1; i < j; i++, j--) {
                NodeImpl node = nodes[i];
                nodes[i] = nodes[j];
                nodes[j] = node;
            }
        }
    }

    /** Pushes the children of a node, in order, for the walk of a descendant segment. */
    private void pushChildren(NodeImpl node, NodeStack stack) {
        JsonValue value = node.value();
        ValueType kind = value.getValueType();
        if (kind == ValueType.OBJECT) {
            for (Map.Entry<String, JsonValue> member : ((JsonObject) value).entrySet()) {
                stack.push(node.child(member.getValue(), member.getKey()));
            }
        } else if (kind == ValueType.ARRAY) {
            JsonArray array = (JsonArray) value;
            int size = array.size();
            for (int i = 0; i < size; i++) {
                stack.push(node.child(array.get(i), i));
            }
        }
    }

    /**
     * Applies a segment's selectors to a node and passes what they select on to the next segment.
     * {@code children[from, to)} are the node's children if the caller has computed them already, else
     * {@code children} is null.
     */
    private boolean select(Segment segment, NodeImpl node, NodeImpl[] children, int from, int to,
                           List<Segment> segments, int next, Sink sink) {
        for (Selector selector : segment.selectors) {
            if (!select(selector, node, children, from, to, segments, next, sink)) {
                return false;
            }
        }
        return true;
    }

    private boolean select(Selector selector, NodeImpl node, NodeImpl[] children, int from, int to,
                           List<Segment> segments, int next, Sink sink) {
        if (selector instanceof Wildcard || selector instanceof Filter) {
            Expr filter = selector instanceof Filter ? ((Filter) selector).expr : null;
            if (children == null) {
                return selectChildren(node, filter, segments, next, sink);
            }
            for (int i = from; i < to; i++) {
                if (!selectChild(children[i], filter, segments, next, sink)) {
                    return false;
                }
            }
            return true;
        }
        JsonValue value = node.value();
        ValueType kind = value.getValueType();
        if (selector instanceof Name) {
            if (kind == ValueType.OBJECT) {
                String name = ((Name) selector).name;
                JsonValue member = ((JsonObject) value).get(name);
                if (member != null) {
                    return apply(segments, next, node.child(member, name), sink);
                }
            }
            return true;
        }
        if (kind != ValueType.ARRAY) {
            return true;
        }
        JsonArray array = (JsonArray) value;
        if (selector instanceof Index) {
            int size = array.size();
            long index = ((Index) selector).index;
            long normalized = index >= 0 ? index : size + index;
            if (normalized >= 0 && normalized < size) {
                int i = (int) normalized;
                return apply(segments, next, node.child(array.get(i), i), sink);
            }
            return true;
        }
        return slice((Slice) selector, node, array, segments, next, sink);
    }

    /**
     * Selects the children of a node that pass a filter, or all of them for a wildcard ({@code filter} null),
     * one by one.
     */
    private boolean selectChildren(NodeImpl node, Expr filter, List<Segment> segments, int next, Sink sink) {
        JsonValue value = node.value();
        ValueType kind = value.getValueType();
        if (kind == ValueType.OBJECT) {
            for (Map.Entry<String, JsonValue> member : ((JsonObject) value).entrySet()) {
                if (!selectChild(node.child(member.getValue(), member.getKey()), filter, segments, next, sink)) {
                    return false;
                }
            }
        } else if (kind == ValueType.ARRAY) {
            JsonArray array = (JsonArray) value;
            int size = array.size();
            for (int i = 0; i < size; i++) {
                if (!selectChild(node.child(array.get(i), i), filter, segments, next, sink)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Passes a child on to the next segment if it passes the filter, if any. */
    private boolean selectChild(NodeImpl child, Expr filter, List<Segment> segments, int next, Sink sink) {
        return (filter != null && !test(filter, child)) || apply(segments, next, child, sink);
    }

    /** Array slice (RFC 9535, section 2.3.4.2): passes the selected elements of an array on. */
    private boolean slice(Slice slice, NodeImpl node, JsonArray array, List<Segment> segments, int next,
                          Sink sink) {
        long len = array.size();
        long step = slice.step == null ? 1 : slice.step;
        if (step == 0) {
            return true;
        }
        long start = slice.start != null ? slice.start : (step >= 0 ? 0 : len - 1);
        long end = slice.end != null ? slice.end : (step >= 0 ? len : -len - 1);
        long nStart = start >= 0 ? start : len + start;
        long nEnd = end >= 0 ? end : len + end;
        if (step > 0) {
            long lower = Math.min(Math.max(nStart, 0), len);
            long upper = Math.min(Math.max(nEnd, 0), len);
            for (long i = lower; i < upper; i += step) {
                if (!apply(segments, next, node.child(array.get((int) i), (int) i), sink)) {
                    return false;
                }
            }
        } else {
            long upper = Math.min(Math.max(nStart, -1), len - 1);
            long lower = Math.min(Math.max(nEnd, -1), len - 1);
            for (long i = upper; lower < i; i += step) {
                if (!apply(segments, next, node.child(array.get((int) i), (int) i), sink)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- logical expressions ----

    private boolean test(Expr expr, NodeImpl current) {
        if (expr instanceof Or) {
            for (Expr operand : ((Or) expr).operands) {
                if (test(operand, current)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof And) {
            for (Expr operand : ((And) expr).operands) {
                if (!test(operand, current)) {
                    return false;
                }
            }
            return true;
        }
        if (expr instanceof Not) {
            return !test(((Not) expr).operand, current);
        }
        if (expr instanceof Paren) {
            return test(((Paren) expr).operand, current);
        }
        if (expr instanceof Comparison) {
            Comparison comparison = (Comparison) expr;
            return Values.compare(value(comparison.left, current), comparison.op, value(comparison.right, current));
        }
        return logical(((Test) expr).operand, current);
    }

    /** Converts a query or function result to a logical value (NodesType: non-empty). */
    private boolean logical(Operand operand, NodeImpl current) {
        if (operand instanceof QueryOperand) {
            return firstOf(((QueryOperand) operand).query, current) != null;
        }
        // The parser admits only functions of LogicalType here: match() and search().
        return regex((FunctionCall) operand, current);
    }

    /** A value, or {@code null} for Nothing. */
    private JsonValue value(Operand operand, NodeImpl current) {
        if (operand instanceof Literal) {
            return ((Literal) operand).value;
        }
        if (operand instanceof QueryOperand) {
            NodeImpl first = firstOf(((QueryOperand) operand).query, current);
            return first == null ? null : first.value();
        }
        // The parser admits only functions of ValueType here: length(), count() and value().
        FunctionCall call = (FunctionCall) operand;
        Operand argument = (Operand) call.arguments.get(0);
        switch (call.function) {
            case LENGTH:
                return length(value(argument, current));
            case COUNT:
                return provider.createValue(nodes(argument, current).size());
            case VALUE:
                List<NodeImpl> nodes = nodes(argument, current);
                return nodes.size() == 1 ? nodes.get(0).value() : null;
            default:
                throw new IllegalStateException(call.function.name());
        }
    }

    /** The nodes of a NodesType argument; the parser admits only queries, as no function returns nodes. */
    private List<NodeImpl> nodes(Operand operand, NodeImpl current) {
        return query(((QueryOperand) operand).query, current);
    }

    /** The {@code length()} function (RFC 9535, section 2.4.4). */
    private JsonValue length(JsonValue value) {
        if (value == null) {
            return null;
        }
        switch (value.getValueType()) {
            case STRING:
                String s = ((JsonString) value).getString();
                return provider.createValue(s.codePointCount(0, s.length()));
            case ARRAY:
                return provider.createValue(((JsonArray) value).size());
            case OBJECT:
                return provider.createValue(((JsonObject) value).size());
            default:
                return null;
        }
    }

    /** The {@code match()} and {@code search()} functions (RFC 9535, sections 2.4.6 and 2.4.7). */
    private boolean regex(FunctionCall call, NodeImpl current) {
        JsonValue subject = value((Operand) call.arguments.get(0), current);
        JsonValue pattern = value((Operand) call.arguments.get(1), current);
        if (subject == null || pattern == null
                || subject.getValueType() != ValueType.STRING || pattern.getValueType() != ValueType.STRING) {
            return false;
        }
        Optional<IRegexp> regexp = call.patterns.get(((JsonString) pattern).getString());
        if (!regexp.isPresent()) {
            return false;
        }
        String s = ((JsonString) subject).getString();
        return call.function == Function.MATCH ? regexp.get().matches(s) : regexp.get().find(s);
    }
}

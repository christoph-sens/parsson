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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonPath;
import jakarta.json.JsonPointer;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;

import org.eclipse.parsson.jsonpath.Ast.And;
import org.eclipse.parsson.jsonpath.Ast.Argument;
import org.eclipse.parsson.jsonpath.Ast.Comparison;
import org.eclipse.parsson.jsonpath.Ast.Expr;
import org.eclipse.parsson.jsonpath.Ast.Filter;
import org.eclipse.parsson.jsonpath.Ast.FunctionCall;
import org.eclipse.parsson.jsonpath.Ast.Index;
import org.eclipse.parsson.jsonpath.Ast.Name;
import org.eclipse.parsson.jsonpath.Ast.Not;
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
 * Evaluates a query over a {@link JsonParser} (the streaming layer of the JSONPath API) and reports the
 * selected nodes in document order.
 *
 * <p>The matcher runs the query as an automaton over the parser's events. Each node it reaches carries
 * {@link Item}s: "the route from the root to this node has passed the first {@code index} segments", with
 * the number of such routes, so that a node selected twice is reported twice. A descendant segment keeps its
 * item on every node below the node it starts from. An item may depend on {@link Decision}s that the parser
 * has not reached yet: whether a filter accepts a child (decided at the end of the child's value) and
 * whether an index or slice counted from the end of an array selects an element (decided once the array is
 * long enough or ends). Matches wait in a queue in document order until their decisions are known; a match
 * that depends on nothing and has nothing before it is reported while the parser is at its first event.
 *
 * <p>Values are built only where needed: for matches in the queue, for children a filter tests, and for a
 * value the caller requests. A value with nothing inside that can be selected is read with
 * {@link JsonParser#getValue()} or skipped with {@link JsonParser#skipObject()}/{@link JsonParser#skipArray()};
 * the matcher reads events only where items can still select something.
 *
 * <p>Nodes are processed with an explicit stack of frames, so deep documents cannot overflow the call stack.
 * Every node the matcher reaches and every selection counts against the visited-nodes limit, as do the visits
 * of filter tests.
 */
final class StreamingMatcher implements JsonPath.Matcher {

    // ---- classification ----

    /**
     * Returns the buffering class of a query, by the rules of {@link JsonPath.Buffering}: filters, negative
     * indices and slices counted from the end need buffering; absolute queries in filters and slices whose
     * selection depends on the array length modulo the step need the whole document.
     */
    static JsonPath.Buffering buffering(Query query) {
        boolean bounded = false;
        for (Segment segment : query.segments) {
            for (Selector selector : segment.selectors) {
                if (selector instanceof Filter) {
                    if (containsAbsoluteQuery(((Filter) selector).expr)) {
                        return JsonPath.Buffering.UNBOUNDED;
                    }
                    bounded = true;
                } else if (selector instanceof Index) {
                    bounded |= ((Index) selector).index < 0;
                } else if (selector instanceof Slice) {
                    Slice slice = (Slice) selector;
                    if (needsWholeArray(slice)) {
                        return JsonPath.Buffering.UNBOUNDED;
                    }
                    bounded |= !decidedAtOnce(slice);
                }
            }
        }
        return bounded ? JsonPath.Buffering.BOUNDED : JsonPath.Buffering.NONE;
    }

    /**
     * Whether a slice decides for each element when the parser reaches it, without the length of the array:
     * start and end are non-negative or omitted and the step is omitted or at least -1.
     */
    private static boolean decidedAtOnce(Slice slice) {
        return (slice.start == null || slice.start >= 0) && (slice.end == null || slice.end >= 0)
                && (slice.step == null || slice.step >= -1);
    }

    /**
     * Whether a slice may need every element of an array: with a step below -1, a start counted from the end
     * and an end that does not bound the elements from below, which elements it selects depends on the length
     * of the array modulo the step.
     */
    private static boolean needsWholeArray(Slice slice) {
        return slice.step != null && slice.step < -1 && (slice.start == null || slice.start < 0)
                && (slice.end == null || slice.end >= 0);
    }

    /** Whether an expression contains an absolute query, at any depth. Recursion is bounded by the query. */
    private static boolean containsAbsoluteQuery(Argument argument) {
        if (argument instanceof Or) {
            return anyContainsAbsoluteQuery(((Or) argument).operands);
        }
        if (argument instanceof And) {
            return anyContainsAbsoluteQuery(((And) argument).operands);
        }
        if (argument instanceof Not) {
            return containsAbsoluteQuery(((Not) argument).operand);
        }
        if (argument instanceof Paren) {
            return containsAbsoluteQuery(((Paren) argument).operand);
        }
        if (argument instanceof Comparison) {
            Comparison comparison = (Comparison) argument;
            return containsAbsoluteQuery(comparison.left) || containsAbsoluteQuery(comparison.right);
        }
        if (argument instanceof Test) {
            return containsAbsoluteQuery(((Test) argument).operand);
        }
        if (argument instanceof FunctionCall) {
            return anyContainsAbsoluteQuery(((FunctionCall) argument).arguments);
        }
        if (argument instanceof QueryOperand) {
            Query query = ((QueryOperand) argument).query;
            if (query.absolute) {
                return true;
            }
            for (Segment segment : query.segments) {
                for (Selector selector : segment.selectors) {
                    if (selector instanceof Filter && containsAbsoluteQuery(((Filter) selector).expr)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean anyContainsAbsoluteQuery(List<? extends Argument> arguments) {
        for (Argument argument : arguments) {
            if (containsAbsoluteQuery(argument)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an index or slice selects element {@code k} of an array of length {@code length} (RFC 9535, 2.3.3, 2.3.4). */
    static boolean selects(Selector selector, long k, long length) {
        if (selector instanceof Index) {
            long index = ((Index) selector).index;
            return (index >= 0 ? index : length + index) == k;
        }
        Slice slice = (Slice) selector;
        long step = slice.step == null ? 1 : slice.step;
        if (step == 0) {
            return false;
        }
        long start = slice.start != null ? slice.start : (step >= 0 ? 0 : length - 1);
        long end = slice.end != null ? slice.end : (step >= 0 ? length : -length - 1);
        long nStart = start >= 0 ? start : length + start;
        long nEnd = end >= 0 ? end : length + end;
        if (step > 0) {
            long lower = Math.min(Math.max(nStart, 0), length);
            long upper = Math.min(Math.max(nEnd, 0), length);
            return lower <= k && k < upper && (k - lower) % step == 0;
        }
        long upper = Math.min(Math.max(nStart, -1), length - 1);
        long lower = Math.min(Math.max(nEnd, -1), length - 1);
        return lower < k && k <= upper && (upper - k) % -step == 0;
    }

    /**
     * The array length from which on an index or slice that counts from the end decides the same for element
     * {@code k} whatever the actual length is: beyond the offsets counted from the end, and for a negative step
     * with a non-negative start, beyond the start. Not defined for slices that {@link #needsWholeArray need
     * the whole array}.
     */
    private static long threshold(Selector selector, long k) {
        if (selector instanceof Index) {
            return k + 1 - ((Index) selector).index;
        }
        Slice slice = (Slice) selector;
        long threshold = k + 1;
        if (slice.start != null && slice.start < 0) {
            threshold -= slice.start;
        }
        if (slice.end != null && slice.end < 0) {
            threshold -= slice.end;
        }
        if (slice.step != null && slice.step < 0 && slice.start != null && slice.start >= 0) {
            threshold = Math.max(threshold, slice.start + 1);
        }
        return threshold;
    }

    // ---- decisions, conditions and items ----

    private static final byte PENDING = 0;
    private static final byte TRUE = 1;
    private static final byte FALSE = 2;

    /** Whether a selector selects a particular child; pending until the parser has read enough. */
    private static class Decision {
        byte state = PENDING;
    }

    /** Whether a filter accepts a child; decided at the end of the child's value. */
    private static final class FilterDecision extends Decision {
        final Filter filter;

        FilterDecision(Filter filter) {
            this.filter = filter;
        }
    }

    /** Whether an index or slice counted from the end selects element {@code index}. */
    private static final class IndexDecision extends Decision {
        final Selector selector;
        final long index;
        final long threshold;

        IndexDecision(Selector selector, long index) {
            this.selector = selector;
            this.index = index;
            this.threshold = threshold(selector, index);
        }
    }

    /** The decisions a route depends on, as a linked list; {@code null} is a route that depends on none. */
    private static final class Cond {
        final Decision decision;
        final Cond next;

        Cond(Decision decision, Cond next) {
            this.decision = decision;
            this.next = next;
        }
    }

    private static byte state(Cond cond) {
        byte result = TRUE;
        for (Cond c = cond; c != null; c = c.next) {
            byte state = c.decision.state;
            if (state == FALSE) {
                return FALSE;
            }
            if (state == PENDING) {
                result = PENDING;
            }
        }
        return result;
    }

    /**
     * {@code count} routes from the root to a node have passed segments {@code [0, index)} under {@code cond}.
     * A {@code descendant} item stands for the descendant segment {@code index}: it applies to the node and
     * is passed on to all its children. A plain item with {@code index == n} makes the node a match.
     */
    private static final class Item {
        final int index;
        final boolean descendant;
        final Cond cond;
        long count;

        Item(int index, boolean descendant, Cond cond, long count) {
            this.index = index;
            this.descendant = descendant;
            this.cond = cond;
            this.count = count;
        }
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return sum < a ? Long.MAX_VALUE : sum;
    }

    // ---- nodes and matches ----

    /** A node the matcher has reached; a frame on the stack while the matcher reads inside it. */
    private static final class Frame {
        final Frame parent;
        final Event event;
        final NodeImpl path;
        /** The member name of this node in its parent object, or {@code null}. */
        final String name;
        Item[] items;
        /** Decisions of filters that test this node. */
        List<FilterDecision> filters;
        /** Decisions of selectors counted from the end of the parent array, for this node. */
        List<IndexDecision> indexDecisions;
        Entry entry;
        /** Whether the value of this node is built. */
        boolean capture;

        // While the matcher reads inside the node:
        boolean array;
        /** Elements started so far. */
        long length;
        /** Name of the member being read. */
        String memberName;
        /** Index decisions of children, in the order of the children. */
        ArrayDeque<IndexDecision> undecided;
        JsonObjectBuilder objectBuilder;
        JsonArrayBuilder arrayBuilder;

        Frame(Frame parent, Event event, NodeImpl path, String name) {
            this.parent = parent;
            this.event = event;
            this.path = path;
            this.name = name;
        }
    }

    /** A selected node, in the queue until its decisions are known and its value is complete. */
    private static final class Entry {
        final NodeImpl path;
        final JsonLocation location;
        final Item[] routes;
        JsonValue value;
        boolean complete;
        long count;

        Entry(NodeImpl path, JsonLocation location, Item[] routes) {
            this.path = path;
            this.location = location;
            this.routes = routes;
        }

        /** How often the node is selected, or -1 while that depends on pending decisions. */
        long resolve() {
            long total = 0;
            for (Item route : routes) {
                byte state = state(route.cond);
                if (state == PENDING) {
                    return -1;
                }
                if (state == TRUE) {
                    total = add(total, route.count);
                }
            }
            return total;
        }
    }

    /** An immutable copy of a parser location; parsers may reuse theirs. */
    private static final class Location implements JsonLocation {
        private final long line;
        private final long column;
        private final long offset;

        Location(JsonLocation location) {
            this.line = location.getLineNumber();
            this.column = location.getColumnNumber();
            this.offset = location.getStreamOffset();
        }

        @Override
        public long getLineNumber() {
            return line;
        }

        @Override
        public long getColumnNumber() {
            return column;
        }

        @Override
        public long getStreamOffset() {
            return offset;
        }

        @Override
        public String toString() {
            return "(line no=" + line + ", column no=" + column + ", offset=" + offset + ")";
        }
    }

    // ---- the matcher ----

    private final JsonParser parser;
    private final JsonProvider provider;
    private final List<Segment> segments;
    private final int n;
    private final int maxDepth;
    /** Tests filters and counts all visits. */
    private final Evaluator evaluator;

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    /** The innermost node the matcher reads inside, or {@code null}. */
    private Frame top;
    /** The node of the current match if it was reported at the parser and has not been processed further. */
    private Frame open;
    private Entry current;
    /** How often the current match is still to be reported, including now. */
    private long repeats;
    private boolean started;
    private boolean ended;

    StreamingMatcher(Query query, JsonProvider provider, Limits limits, JsonParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.provider = provider;
        this.segments = query.segments;
        this.n = segments.size();
        this.maxDepth = limits.maxDepth;
        this.evaluator = new Evaluator(JsonValue.NULL, provider, limits);
    }

    @Override
    public boolean find() {
        if (current != null) {
            if (repeats > 1) {
                repeats--;
                return true;
            }
            current = null;
        }
        if (open != null) {
            Frame node = open;
            open = null;
            proceed(node);
        }
        while (true) {
            Entry ready = readyEntry();
            if (ready != null) {
                current = ready;
                repeats = ready.count;
                return true;
            }
            if (ended) {
                if (!queue.isEmpty()) {
                    throw new IllegalStateException("Matches with undecided selectors at the end of the value");
                }
                return false;
            }
            if (step()) {
                return true;
            }
        }
    }

    @Override
    public String getPath() {
        return currentEntry().path.getPath();
    }

    @Override
    public JsonPointer getPointer() {
        return currentEntry().path.getPointer();
    }

    @Override
    public JsonLocation getLocation() {
        return currentEntry().location;
    }

    @Override
    public JsonValue getValue() {
        Entry entry = currentEntry();
        if (entry.value == null) {
            // Reported at the parser and not read yet: read it now, with the matches inside it.
            Frame node = open;
            open = null;
            node.capture = true;
            proceed(node);
            while (!entry.complete) {
                step();
            }
        }
        return entry.value;
    }

    private Entry currentEntry() {
        if (current == null) {
            throw new IllegalStateException(JsonPathMessages.get("path.stream.no.match"));
        }
        return current;
    }

    /** Takes the first match of the queue if its decisions are known; drops matches that were not selected. */
    private Entry readyEntry() {
        while (!queue.isEmpty()) {
            Entry head = queue.peek();
            if (!head.complete) {
                return null;
            }
            long count = head.resolve();
            if (count < 0) {
                return null;
            }
            queue.poll();
            if (count > 0) {
                head.count = count;
                return head;
            }
        }
        return null;
    }

    /** Reads and processes one event; returns true if it reported a match at the parser. */
    private boolean step() {
        if (!started) {
            started = true;
            Event event = parser.next();
            if (event == Event.KEY_NAME || event == Event.END_OBJECT || event == Event.END_ARRAY) {
                throw new IllegalStateException(JsonPathMessages.get("path.stream.start", event));
            }
            return start(null, event);
        }
        Event event = parser.next();
        switch (event) {
            case KEY_NAME:
                top.memberName = parser.getString();
                return false;
            case END_OBJECT:
            case END_ARRAY:
                end();
                return false;
            default:
                return start(top, event);
        }
    }

    /** Processes the first event of a value; returns true if the value is a match reported at the parser. */
    private boolean start(Frame parent, Event event) {
        Frame node;
        if (parent == null) {
            node = new Frame(null, event, NodeImpl.root(provider, null), null);
            List<Item> items = new ArrayList<>(2);
            addItem(items, 0, null, 1);
            node.items = items.toArray(new Item[0]);
        } else if (parent.array) {
            long index = parent.length++;
            decideIndices(parent, false);
            node = new Frame(parent, event, parent.path.child(null, (int) index), null);
            node.items = childItems(parent, node, null, index);
        } else {
            String name = parent.memberName;
            node = new Frame(parent, event, parent.path.child(null, name), name);
            node.items = childItems(parent, node, name, -1);
        }
        evaluator.visitNodes(1);

        boolean insideCapture = parent != null && parent.capture;
        List<Item> routes = null;
        boolean certain = true;
        for (Item item : node.items) {
            if (!item.descendant && item.index == n) {
                if (routes == null) {
                    routes = new ArrayList<>(1);
                }
                routes.add(item);
                certain &= state(item.cond) == TRUE;
            }
        }
        if (routes != null) {
            Entry entry = new Entry(node.path, new Location(parser.getLocation()), routes.toArray(new Item[0]));
            node.entry = entry;
            if (certain && !insideCapture && queue.isEmpty() && node.filters == null) {
                // Nothing before it waits and nothing decides it later: report it with the parser right here.
                current = entry;
                repeats = entry.resolve();
                open = node;
                return true;
            }
            queue.add(entry);
        }
        node.capture = insideCapture || node.entry != null || node.filters != null;
        proceed(node);
        return false;
    }

    /**
     * Continues with a node whose first event has been read: reads or skips a value with nothing inside to
     * select, or makes the node the top frame so that the matcher reads its children.
     */
    private void proceed(Frame node) {
        Event event = node.event;
        if (event != Event.START_OBJECT && event != Event.START_ARRAY) {
            complete(node, node.capture ? parser.getValue() : null);
        } else if (!live(node.items)) {
            if (node.capture) {
                complete(node, parser.getValue());
            } else {
                if (event == Event.START_OBJECT) {
                    parser.skipObject();
                } else {
                    parser.skipArray();
                }
                complete(node, null);
            }
        } else {
            if (node.path.depth() >= maxDepth) {
                throw Evaluator.tooDeep(maxDepth);
            }
            node.array = event == Event.START_ARRAY;
            if (node.capture) {
                if (node.array) {
                    node.arrayBuilder = provider.createArrayBuilder();
                } else {
                    node.objectBuilder = provider.createObjectBuilder();
                }
            }
            top = node;
        }
    }

    /** Processes the last event of the top frame. */
    private void end() {
        Frame node = top;
        top = node.parent;
        if (node.array) {
            decideIndices(node, true);
        }
        JsonValue value = null;
        if (node.capture) {
            value = node.array ? node.arrayBuilder.build() : node.objectBuilder.build();
        }
        complete(node, value);
    }

    /** Finishes a node: passes its value on, decides the filters that test it, completes its match. */
    private void complete(Frame node, JsonValue value) {
        Frame parent = node.parent;
        if (parent != null && parent.capture) {
            if (parent.array) {
                parent.arrayBuilder.add(value);
            } else {
                parent.objectBuilder.add(node.name, value);
            }
        }
        if (node.filters != null) {
            int depth = node.path.depth();
            for (FilterDecision decision : node.filters) {
                decision.state = evaluator.test(decision.filter.expr, value, depth) ? TRUE : FALSE;
            }
        }
        if (node.entry != null) {
            node.entry.value = value;
            node.entry.complete = true;
        }
        if (parent == null) {
            ended = true;
        }
    }

    /** Whether items of a node can select its children. */
    private boolean live(Item[] items) {
        for (Item item : items) {
            if (item.descendant || item.index < n) {
                return true;
            }
        }
        return false;
    }

    /** Computes the items of a child from those of its parent: passes descendant items on, applies selectors. */
    private Item[] childItems(Frame parent, Frame child, String name, long index) {
        List<Item> items = new ArrayList<>(4);
        for (Item item : parent.items) {
            if (state(item.cond) == FALSE) {
                continue;
            }
            if (item.descendant) {
                addItem(items, item.index, true, item.cond, item.count);
                select(items, item, parent, child, name, index);
            } else if (item.index < n) {
                select(items, item, parent, child, name, index);
            }
        }
        return items.toArray(new Item[0]);
    }

    /** Applies the selectors of the item's segment to a child and adds the items of the selections. */
    private void select(List<Item> items, Item item, Frame parent, Frame child, String name, long index) {
        for (Selector selector : segments.get(item.index).selectors) {
            Decision decision = null;
            if (selector instanceof Name) {
                if (parent.array || !((Name) selector).name.equals(name)) {
                    continue;
                }
            } else if (selector instanceof Filter) {
                decision = filterDecision(child, (Filter) selector);
            } else if (!(selector instanceof Wildcard)) {
                if (!parent.array) {
                    continue;
                }
                boolean atOnce = selector instanceof Index ? ((Index) selector).index >= 0
                        : decidedAtOnce((Slice) selector);
                if (atOnce) {
                    if (!selects(selector, index, index + 1)) {
                        continue;
                    }
                } else {
                    decision = indexDecision(parent, child, selector, index);
                }
            }
            Cond cond = item.cond;
            if (decision != null) {
                if (decision.state == FALSE) {
                    continue;
                }
                if (decision.state == PENDING) {
                    cond = new Cond(decision, cond);
                }
            }
            evaluator.visitNodes(item.count);
            addItem(items, item.index + 1, cond, item.count);
        }
    }

    /** Adds the item for a node that has passed {@code index} segments. */
    private void addItem(List<Item> items, int index, Cond cond, long count) {
        boolean descendant = index < n && segments.get(index).descendant;
        addItem(items, index, descendant, cond, count);
    }

    /** Adds an item, merged with an equal one. */
    private static void addItem(List<Item> items, int index, boolean descendant, Cond cond, long count) {
        for (Item item : items) {
            if (item.index == index && item.descendant == descendant && item.cond == cond) {
                item.count = add(item.count, count);
                return;
            }
        }
        items.add(new Item(index, descendant, cond, count));
    }

    /** The decision whether a filter accepts a child; one per filter and child. */
    private static FilterDecision filterDecision(Frame child, Filter filter) {
        if (child.filters == null) {
            child.filters = new ArrayList<>(1);
        }
        for (FilterDecision decision : child.filters) {
            if (decision.filter == filter) {
                return decision;
            }
        }
        FilterDecision decision = new FilterDecision(filter);
        child.filters.add(decision);
        return decision;
    }

    /** The decision whether an index or slice counted from the end selects an element; one per selector and element. */
    private static IndexDecision indexDecision(Frame parent, Frame child, Selector selector, long index) {
        if (child.indexDecisions == null) {
            child.indexDecisions = new ArrayList<>(1);
        }
        for (IndexDecision decision : child.indexDecisions) {
            if (decision.selector == selector) {
                return decision;
            }
        }
        IndexDecision decision = new IndexDecision(selector, index);
        child.indexDecisions.add(decision);
        if (decision.threshold <= parent.length) {
            decision.state = selects(selector, index, parent.length) ? TRUE : FALSE;
        } else {
            if (parent.undecided == null) {
                parent.undecided = new ArrayDeque<>();
            }
            parent.undecided.add(decision);
        }
        return decision;
    }

    /**
     * Decides the index decisions of an array's elements that its length so far settles, in order of the
     * elements; at the end of the array, all of them.
     */
    private static void decideIndices(Frame array, boolean end) {
        ArrayDeque<IndexDecision> undecided = array.undecided;
        if (undecided == null) {
            return;
        }
        while (!undecided.isEmpty()) {
            IndexDecision decision = undecided.peek();
            if (!end && decision.threshold > array.length) {
                return;
            }
            undecided.poll();
            decision.state = selects(decision.selector, decision.index, array.length) ? TRUE : FALSE;
        }
    }
}

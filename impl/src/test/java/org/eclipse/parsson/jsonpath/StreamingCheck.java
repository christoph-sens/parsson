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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonPath;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;

import org.eclipse.parsson.JsonProviderImpl;

/**
 * Checks that the streaming matcher reports the nodes of the tree evaluation, in document order, with the
 * parser state and locations the API promises.
 */
final class StreamingCheck {

    static final JsonProvider PROVIDER = new JsonProviderImpl();

    private StreamingCheck() {
    }

    /** A match as the matcher reported it. */
    static final class Match {
        final String path;
        final String pointer;
        final long offset;
        final JsonValue value;

        Match(String path, String pointer, long offset, JsonValue value) {
            this.path = path;
            this.pointer = pointer;
            this.offset = offset;
            this.value = value;
        }
    }

    /** Which values the caller takes. */
    enum Take {
        NONE, ALL, EVERY_OTHER
    }

    /** Runs the matcher over {@code json} and collects the matches. */
    static List<Match> stream(JsonPath path, String json, Take take) {
        List<Match> matches = new ArrayList<>();
        try (JsonParser parser = PROVIDER.createParser(new StringReader(json))) {
            JsonPath.Matcher matcher = path.matcher(parser);
            int i = 0;
            while (matcher.find()) {
                boolean taken = take == Take.ALL || (take == Take.EVERY_OTHER && i % 2 == 0);
                JsonValue value = taken ? matcher.getValue() : null;
                matches.add(new Match(matcher.getPath(), matcher.getPointer().toString(),
                        matcher.getLocation().getStreamOffset(), value));
                i++;
            }
            assertFalse(parser.hasNext(), "the matcher read the whole value, nothing more");
            assertFalse(matcher.find(), "find() stays false");
            assertEquals(0, ((StreamingMatcher) matcher).bufferedNodes(), "all held values released");
        }
        return matches;
    }

    /**
     * Asserts that the matcher reports for {@code query} on {@code json} the tree result sorted into document
     * order, whichever values the caller takes, and that matches of class NONE leave the parser at their first
     * event.
     */
    static void check(String query, String json) {
        JsonPath path = PROVIDER.createPath(query);
        JsonValue document = PROVIDER.createReader(new StringReader(json)).readValue();
        List<NodeImpl> expected = documentOrder(path.query(document), document);
        Map<String, Long> offsets = valueOffsets(json);
        String context = query + " on " + json;

        for (Take take : Take.values()) {
            List<Match> actual = stream(path, json, take);
            List<String> expectedPaths = new ArrayList<>();
            for (NodeImpl node : expected) {
                expectedPaths.add(node.getPath());
            }
            List<String> actualPaths = new ArrayList<>();
            for (Match match : actual) {
                actualPaths.add(match.path);
            }
            assertEquals(expectedPaths, actualPaths, context + ", taking " + take);
            for (int i = 0; i < actual.size(); i++) {
                Match match = actual.get(i);
                NodeImpl node = expected.get(i);
                assertEquals(node.getPointer().toString(), match.pointer, context);
                assertEquals(offsets.get(match.path), Long.valueOf(match.offset), context + ": location of " + match.path);
                if (match.value != null) {
                    assertEquals(node.getValue(), match.value, context + ": value of " + match.path);
                }
            }
        }
        if (path.getBuffering() == JsonPath.Buffering.NONE) {
            checkAtParser(path, json, context);
            // Class NONE holds nothing, so it streams with no buffer at all, whichever values are taken.
            JsonPath unbuffered = PROVIDER.createPath(query, Collections.singletonMap(JsonPath.MAX_BUFFERED_NODES, 0));
            for (Take take : Take.values()) {
                assertEquals(expected.size(), stream(unbuffered, json, take).size(), context + ", no buffer");
            }
        }
        if (inRfcOrder(Parser.parse(query, PROVIDER))) {
            List<String> rfcPaths = new ArrayList<>();
            for (JsonPath.Node node : path.query(document)) {
                rfcPaths.add(node.getPath());
            }
            List<String> streamPaths = new ArrayList<>();
            for (Match match : stream(path, json, Take.NONE)) {
                streamPaths.add(match.path);
            }
            assertEquals(rfcPaths, streamPaths, context + ": the matcher must keep RFC 9535 order");
        }
    }

    /** The condition of the API under which document order is RFC 9535 order. */
    static boolean inRfcOrder(Ast.Query query) {
        for (Ast.Segment segment : query.segments) {
            if (segment.descendant || segment.selectors.size() != 1) {
                return false;
            }
            Ast.Selector selector = segment.selectors.get(0);
            if (selector instanceof Ast.Slice && ((Ast.Slice) selector).step != null && ((Ast.Slice) selector).step < 0) {
                return false;
            }
        }
        return true;
    }

    /** Class NONE, no values taken: at each match the parser's current event is the first event of the value. */
    private static void checkAtParser(JsonPath path, String json, String context) {
        try (JsonParser parser = PROVIDER.createParser(new StringReader(json))) {
            JsonPath.Matcher matcher = path.matcher(parser);
            while (matcher.find()) {
                Event event = parser.currentEvent();
                assertTrue(event != Event.KEY_NAME && event != Event.END_OBJECT && event != Event.END_ARRAY,
                        context + ": parser at " + event + " for " + matcher.getPath());
                assertEquals(matcher.getLocation().getStreamOffset(), parser.getLocation().getStreamOffset(),
                        context + ": parser not at " + matcher.getPath());
            }
        }
    }

    /** Sorts nodes into document order, stably: a node before its descendants, members in entrySet order. */
    static List<NodeImpl> documentOrder(List<JsonPath.Node> nodes, JsonValue document) {
        Map<String, Integer> ordinals = new HashMap<>();
        Deque<NodeImpl> stack = new ArrayDeque<>();
        stack.push(NodeImpl.root(PROVIDER, document));
        while (!stack.isEmpty()) {
            NodeImpl node = stack.pop();
            ordinals.put(node.getPath(), ordinals.size());
            JsonValue value = node.getValue();
            List<NodeImpl> children = new ArrayList<>();
            if (value instanceof JsonObject) {
                for (Map.Entry<String, JsonValue> member : ((JsonObject) value).entrySet()) {
                    children.add(node.child(member.getValue(), member.getKey()));
                }
            } else if (value instanceof JsonArray) {
                JsonArray array = (JsonArray) value;
                for (int i = 0; i < array.size(); i++) {
                    children.add(node.child(array.get(i), i));
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        List<NodeImpl> sorted = new ArrayList<>();
        for (JsonPath.Node node : nodes) {
            sorted.add((NodeImpl) node);
        }
        sorted.sort((a, b) -> Integer.compare(ordinals.get(a.getPath()), ordinals.get(b.getPath())));
        return sorted;
    }

    /** The parser location (stream offset) at the first event of each value, by normalized path. */
    static Map<String, Long> valueOffsets(String json) {
        Map<String, Long> offsets = new HashMap<>();
        Deque<NodeImpl> containers = new ArrayDeque<>();
        Deque<int[]> indices = new ArrayDeque<>();
        String name = null;
        try (JsonParser parser = PROVIDER.createParser(new StringReader(json))) {
            while (parser.hasNext()) {
                Event event = parser.next();
                if (event == Event.KEY_NAME) {
                    name = parser.getString();
                    continue;
                }
                if (event == Event.END_OBJECT || event == Event.END_ARRAY) {
                    containers.pop();
                    indices.pop();
                    continue;
                }
                NodeImpl node;
                if (containers.isEmpty()) {
                    node = NodeImpl.root(PROVIDER, null);
                } else if (indices.peek()[0] >= 0) {
                    node = containers.peek().child(null, indices.peek()[0]++);
                } else {
                    node = containers.peek().child(null, name);
                }
                assertNotEquals(null, node);
                offsets.put(node.getPath(), parser.getLocation().getStreamOffset());
                if (event == Event.START_OBJECT || event == Event.START_ARRAY) {
                    containers.push(node);
                    indices.push(new int[] {event == Event.START_ARRAY ? 0 : -1});
                }
            }
        }
        return offsets;
    }
}

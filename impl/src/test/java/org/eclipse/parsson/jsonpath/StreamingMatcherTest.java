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

import static org.eclipse.parsson.jsonpath.StreamingCheck.PROVIDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPath;
import jakarta.json.JsonPath.Buffering;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;

import org.junit.jupiter.api.Test;

/**
 * Tests of the streaming matcher: buffering classes, order, repeated and nested matches, parser state,
 * skipping, and limits.
 */
class StreamingMatcherTest {

    private static JsonParser parser(String json) {
        return PROVIDER.createParser(new StringReader(json.replace('\'', '"')));
    }

    private static List<String> paths(String query, String json) {
        List<String> paths = new ArrayList<>();
        try (JsonParser parser = parser(json)) {
            JsonPath.Matcher matcher = PROVIDER.createPath(query).matcher(parser);
            while (matcher.find()) {
                paths.add(matcher.getPath());
            }
        }
        return paths;
    }

    private static Buffering buffering(String query) {
        return PROVIDER.createPath(query).getBuffering();
    }

    @Test
    void classifiesQueriesByTheirSyntax() {
        for (String query : new String[] {"$", "$.a", "$.store.book[*].title", "$..id", "$..*", "$['a','b']",
            "$[0,0]", "$[*,0]", "$[1:5:2]", "$[::-1]", "$[5:1:-1]", "$[::0]", "$..[0]", "$.a..b[1:]", "$[0:2,3]"}) {
            assertEquals(Buffering.NONE, buffering(query), query);
        }
        for (String query : new String[] {"$[?@.a]", "$..[?@.price < 10]", "$[-1]", "$[-3:]", "$[:-1]", "$[1:-1]",
            "$[5:1:-2]", "$[0::-2]", "$[-1:-4:-2]", "$[?@[?@]]",
            "$[?length(@) > 1]", "$[?match(@.a, 'x.*')]", "$[?@[::-2]]"}) {
            assertEquals(Buffering.BOUNDED, buffering(query), query);
        }
        for (String query : new String[] {"$[?@.price < $.limit]", "$[?$]", "$[?@[?$.x]]", "$[?count($..a) > 1]",
            "$[?!(@.a == $.b)]", "$[::-2]", "$[-1::-2]", "$[:0:-3]", "$[-2:1:-2]", "$.a[1][::-2].b"}) {
            assertEquals(Buffering.UNBOUNDED, buffering(query), query);
        }
    }

    @Test
    void rejectsUnboundedQueries() {
        JsonPath path = PROVIDER.createPath("$.items[?@.price < $.limit]");
        try (JsonParser parser = parser("{'items': [{'price': 1}], 'limit': 2}")) {
            assertThrows(JsonException.class, () -> path.matcher(parser));
            // The parser is untouched, so the caller can fall back to the tree layer.
            assertEquals(Event.START_OBJECT, parser.next());
            assertEquals(1, path.query(parser.getValue()).size());
        }
    }

    @Test
    void reportsInDocumentOrder() {
        assertEquals(Arrays.asList("$['a']['b']", "$['b']"), paths("$..b", "{'a': {'b': 1}, 'b': 2}"));
        assertEquals(Arrays.asList("$['a']", "$['b']"), paths("$['b','a']", "{'a': 1, 'b': 2}"));
        assertEquals(Arrays.asList("$[0][1]", "$[1]"), paths("$..[1]", "[[7, 8], 9]"));
        assertEquals(Arrays.asList("$[0]", "$[2]"), paths("$[2,0]", "[1, 2, 3]"));
    }

    @Test
    void reportsANodeSelectedTwiceTwiceInARowAtTheParser() {
        try (JsonParser parser = parser("[{'a': 1}, 2]")) {
            JsonPath.Matcher matcher = PROVIDER.createPath("$[0,0,1]").matcher(parser);
            assertTrue(matcher.find());
            assertEquals("$[0]", matcher.getPath());
            assertEquals(Event.START_OBJECT, parser.currentEvent());
            assertTrue(matcher.find());
            assertEquals("$[0]", matcher.getPath());
            assertEquals(Event.START_OBJECT, parser.currentEvent());
            JsonValue value = matcher.getValue();
            assertEquals(Event.END_OBJECT, parser.currentEvent());
            assertSame(value, matcher.getValue());
            assertTrue(matcher.find());
            assertEquals("$[1]", matcher.getPath());
            assertEquals(Event.VALUE_NUMBER, parser.currentEvent());
            assertEquals(2, parser.getInt());
            assertFalse(matcher.find());
            assertEquals(Event.END_ARRAY, parser.currentEvent());
        }
    }

    @Test
    void reportsMatchesInsideATakenValueFromThatValue() {
        try (JsonParser parser = parser("{'a': {'a': {'a': 1}}, 'b': 2}")) {
            JsonPath.Matcher matcher = PROVIDER.createPath("$..a").matcher(parser);
            assertTrue(matcher.find());
            assertEquals("$['a']", matcher.getPath());
            JsonObject outer = matcher.getValue().asJsonObject();
            assertEquals(Event.END_OBJECT, parser.currentEvent());
            assertTrue(matcher.find());
            assertEquals("$['a']['a']", matcher.getPath());
            assertSame(outer.get("a"), matcher.getValue());
            assertTrue(matcher.find());
            assertEquals("$['a']['a']['a']", matcher.getPath());
            assertEquals(1, matcher.getLocation().getLineNumber());
            assertEquals(PROVIDER.createValue(1), matcher.getValue());
            assertFalse(matcher.find());
        }
    }

    @Test
    void readsTheValueThatTheParserReadsNextAndNothingMore() {
        try (JsonParser parser = parser("{'header': {'a': 0}, 'items': [{'a': 1}, {'a': 2}], 'trailer': {'a': 3}}")) {
            assertEquals(Event.START_OBJECT, parser.next());
            assertEquals(Event.KEY_NAME, parser.next());
            parser.next();
            parser.skipObject();
            assertEquals(Event.KEY_NAME, parser.next());
            assertEquals("items", parser.getString());
            JsonPath.Matcher matcher = PROVIDER.createPath("$[*].a").matcher(parser);
            assertTrue(matcher.find());
            assertEquals("$[0]['a']", matcher.getPath());
            assertEquals("/0/a", matcher.getPointer().toString());
            assertTrue(matcher.find());
            assertEquals(PROVIDER.createValue(2), matcher.getValue());
            assertFalse(matcher.find());
            assertEquals(Event.END_ARRAY, parser.currentEvent());
            assertEquals(Event.KEY_NAME, parser.next());
            assertEquals("trailer", parser.getString());
        }
    }

    @Test
    void failsIfTheParserIsNotBeforeAValue() {
        try (JsonParser parser = parser("[]")) {
            parser.next();
            JsonPath.Matcher matcher = PROVIDER.createPath("$").matcher(parser);
            assertThrows(IllegalStateException.class, matcher::find);
        }
        try (JsonParser parser = parser("[1]")) {
            JsonPath.Matcher matcher = PROVIDER.createPath("$[0]").matcher(parser);
            assertThrows(IllegalStateException.class, matcher::getPath);
            assertThrows(IllegalStateException.class, matcher::getValue);
            assertTrue(matcher.find());
            assertFalse(matcher.find());
            assertThrows(IllegalStateException.class, matcher::getLocation);
        }
        assertThrows(NullPointerException.class, () -> PROVIDER.createPath("$").matcher(null));
    }

    @Test
    void skipsValuesWithNothingToSelectInside() {
        String json = "{'x': {'deep': [[[1, 2, 3]], {'y': [4, 5]}]}, 'a': 1, 'z': [6, 7]}";
        CountingParser parser = new CountingParser(parser(json));
        JsonPath.Matcher matcher = PROVIDER.createPath("$.a").matcher(parser);
        assertTrue(matcher.find());
        assertFalse(matcher.find());
        // START_OBJECT, KEY x, START_OBJECT (skipped), KEY a, 1, KEY z, START_ARRAY (skipped), END_OBJECT
        assertEquals(8, parser.nextCalls);
        assertEquals(1, parser.skipObjectCalls);
        assertEquals(1, parser.skipArrayCalls);
    }

    @Test
    void reportsBufferedMatchesBeforeTheEndOfTheDocument() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 10_000; i++) {
            json.append(i == 0 ? "" : ",").append("{\"a\": [").append(i).append(", ").append(i).append("]}");
        }
        json.append(']');
        for (String query : new String[] {"$[*].a[-1]", "$[?@.a[0] == 0].a", "$..[?@ == 1]", "$[*].a[1::-2]"}) {
            assertEquals(Buffering.BOUNDED, buffering(query), query);
            try (JsonParser parser = PROVIDER.createParser(new StringReader(json.toString()))) {
                JsonPath.Matcher matcher = PROVIDER.createPath(query).matcher(parser);
                assertTrue(matcher.find(), query);
                assertTrue(parser.getLocation().getStreamOffset() < 100, query + ": " + parser.getLocation());
            }
        }
    }

    @Test
    void matchesOverAParserOfAnotherKind() {
        JsonArray array = EvaluatorTest.json("[{'a': 1}, {'a': 2, 'b': {'a': 3}}]").asJsonArray();
        try (JsonParser parser = PROVIDER.createParserFactory(Collections.<String, Object>emptyMap())
                .createParser(array)) {
            JsonPath.Matcher matcher = PROVIDER.createPath("$..a").matcher(parser);
            List<JsonValue> values = new ArrayList<>();
            while (matcher.find()) {
                values.add(matcher.getValue());
            }
            assertEquals(Arrays.asList(PROVIDER.createValue(1), PROVIDER.createValue(2), PROVIDER.createValue(3)),
                    values);
        }
    }

    @Test
    void countsVisitsAgainstTheLimit() {
        Map<String, ?> config = Collections.singletonMap(JsonPath.MAX_VISITED_NODES, 50);
        JsonPath path = PROVIDER.createPath("$[*]", config);
        StringBuilder json = new StringBuilder("[0");
        for (int i = 1; i < 100; i++) {
            json.append(',').append(i);
        }
        String document = json.append(']').toString();
        try (JsonParser parser = PROVIDER.createParser(new StringReader(document))) {
            JsonPath.Matcher matcher = path.matcher(parser);
            JsonException e = assertThrows(JsonException.class, () -> {
                while (matcher.find()) {
                    matcher.getValue();
                }
            });
            assertEquals("Query visited more than 50 nodes, the limit", e.getMessage());
        }
    }

    @Test
    void stopsRoutesThatMultiplyAtTheLimit() {
        // Every node is reached by a number of routes that grows polynomially with its depth.
        JsonPath path = PROVIDER.createPath("$..*..*..*..*..*..*..*..*");
        String json = EvaluatorTest.nestedArrays(500).toString();
        try (JsonParser parser = PROVIDER.createParser(new StringReader(json))) {
            JsonPath.Matcher matcher = path.matcher(parser);
            assertThrows(JsonException.class, () -> {
                while (matcher.find()) {
                    matcher.getPath();
                }
            });
        }
    }

    @Test
    void countsTheWorkForRoutesThatWaitForDecisions() {
        // Found by fuzzing: nested filters and indices counted from the end that are not decided yet give each node a
        // number of items that grows with the square of its depth. Before the work for them was counted as visits
        // (and equal items were found by hash), this document of 2 KB took hours with fewer than 1,000,000 visits.
        JsonPath path = PROVIDER.createPath("$..[?@..*]..[-5]..a.a",
                Collections.singletonMap(JsonPath.MAX_VISITED_NODES, 1_000_000));
        String json = EvaluatorTest.nestedArrays(999).toString();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try (JsonParser parser = PROVIDER.createParser(new StringReader(json))) {
                JsonPath.Matcher matcher = path.matcher(parser);
                assertEquals("Query visited more than 1000000 nodes, the limit",
                        assertThrows(JsonException.class, () -> {
                            while (matcher.find()) {
                                matcher.getPath();
                            }
                        }).getMessage());
            }
        });
        assertEquals(Collections.emptyList(), paths("$..[?@..*]..[-5]..a.a", EvaluatorTest.nestedArrays(100).toString()));
    }

    @Test
    void failsBelowTheDepthLimitWhereTheQueryWalks() {
        JsonPath path = PROVIDER.createPath("$..x", Collections.singletonMap(JsonPath.MAX_DEPTH, "10"));
        assertEquals(Collections.emptyList(), paths("$.a", EvaluatorTest.nestedArrays(50).toString()));
        try (JsonParser parser = PROVIDER.createParser(new StringReader(EvaluatorTest.nestedArrays(50).toString()))) {
            JsonPath.Matcher matcher = path.matcher(parser);
            assertEquals("Query argument is nested more than 10 levels deep, the limit",
                    assertThrows(JsonException.class, matcher::find).getMessage());
        }
    }

    @Test
    void handlesDeepDocumentsWithoutRecursion() {
        int depth = 50_000;
        Map<String, Object> parserConfig = Collections.<String, Object>singletonMap("org.eclipse.parsson.maxDepth",
                depth + 1);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            text.append('[');
        }
        text.append("\"x\"");
        for (int i = 0; i < depth; i++) {
            text.append(']');
        }
        String json = text.toString();
        JsonPath path = PROVIDER.createPath("$..[0]", Collections.singletonMap(JsonPath.MAX_DEPTH, depth + 1));
        try (JsonParser parser = PROVIDER.createParserFactory(parserConfig).createParser(new StringReader(json))) {
            JsonPath.Matcher matcher = path.matcher(parser);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            assertEquals(depth, count);
        }
    }

    @Test
    void decidesFiltersOnTheCandidateAlone() {
        StreamingCheck.check("$.store.book[?@.price < 10].title", "{\"store\": {\"book\": [{\"title\": \"A\", "
                + "\"price\": 8.95}, {\"title\": \"B\", \"price\": 12.99}, {\"price\": 8.99, \"title\": \"C\"}]}}");
        StreamingCheck.check("$..[?@.x]..y", "{\"a\": {\"x\": 1, \"b\": {\"y\": 2, \"x\": 0, \"c\": {\"y\": 3}}}}");
        StreamingCheck.check("$[?@.a == @.b]", "[{\"a\": [1, {\"c\": 1.0}], \"b\": [1.0, {\"c\": 1}]}, {\"a\": 1}]");
    }

    @Test
    void streamsLocationsOfBufferedMatches() {
        try (JsonParser parser = parser("[\n  {'a': 1},\n  {'a': 2}\n]")) {
            JsonPath.Matcher matcher = PROVIDER.createPath("$[-1].a").matcher(parser);
            assertTrue(matcher.find());
            JsonLocation location = matcher.getLocation();
            assertEquals(3, location.getLineNumber());
            assertEquals(new BigDecimal(2), ((jakarta.json.JsonNumber) matcher.getValue()).bigDecimalValue());
            assertEquals(Event.END_ARRAY, parser.currentEvent());
        }
    }

    /** Counts calls of {@link JsonParser#next()} and of the skip methods. */
    private static final class CountingParser implements JsonParser {
        private final JsonParser delegate;
        int nextCalls;
        int skipObjectCalls;
        int skipArrayCalls;

        CountingParser(JsonParser delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public Event next() {
            nextCalls++;
            return delegate.next();
        }

        @Override
        public Event currentEvent() {
            return delegate.currentEvent();
        }

        @Override
        public String getString() {
            return delegate.getString();
        }

        @Override
        public boolean isIntegralNumber() {
            return delegate.isIntegralNumber();
        }

        @Override
        public int getInt() {
            return delegate.getInt();
        }

        @Override
        public long getLong() {
            return delegate.getLong();
        }

        @Override
        public BigDecimal getBigDecimal() {
            return delegate.getBigDecimal();
        }

        @Override
        public JsonLocation getLocation() {
            return delegate.getLocation();
        }

        @Override
        public JsonObject getObject() {
            return delegate.getObject();
        }

        @Override
        public JsonValue getValue() {
            return delegate.getValue();
        }

        @Override
        public JsonArray getArray() {
            return delegate.getArray();
        }

        @Override
        public Stream<JsonValue> getArrayStream() {
            return delegate.getArrayStream();
        }

        @Override
        public Stream<Map.Entry<String, JsonValue>> getObjectStream() {
            return delegate.getObjectStream();
        }

        @Override
        public Stream<JsonValue> getValueStream() {
            return delegate.getValueStream();
        }

        @Override
        public void skipArray() {
            skipArrayCalls++;
            delegate.skipArray();
        }

        @Override
        public void skipObject() {
            skipObjectCalls++;
            delegate.skipObject();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}

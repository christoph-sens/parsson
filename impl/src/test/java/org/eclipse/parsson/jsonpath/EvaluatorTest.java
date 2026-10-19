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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.JsonProviderImpl;
import org.junit.jupiter.api.Test;

/**
 * Tests of the evaluator beyond the Compliance Test Suite: the semantics of RFC 9535 on JSON-P values.
 */
class EvaluatorTest {

    private static final JsonProvider PROVIDER = new JsonProviderImpl();

    private static final JsonValue STORE = json("{'store': {"
            + "'book': ["
            + "  {'title': 'Sayings of the Century', 'price': 8.95},"
            + "  {'title': 'Sword of Honour', 'price': 12.99},"
            + "  {'title': 'Moby Dick', 'price': 8.99}],"
            + "'bicycle': {'color': 'red', 'price': 399}},"
            + "'limit': 9}");

    /** Parses JSON text in which single quotes stand for double quotes. */
    static JsonValue json(String text) {
        try (JsonReader reader = PROVIDER.createReader(new StringReader(text.replace('\'', '"')))) {
            return reader.readValue();
        }
    }

    static List<NodeImpl> query(String query, JsonValue document) {
        return new Evaluator(document, PROVIDER).run(Parser.parse(query, PROVIDER));
    }

    static List<JsonValue> values(String query, JsonValue document) {
        List<JsonValue> values = new ArrayList<>();
        for (NodeImpl node : query(query, document)) {
            values.add(node.value());
        }
        return values;
    }

    static List<String> paths(String query, JsonValue document) {
        List<String> paths = new ArrayList<>();
        for (NodeImpl node : query(query, document)) {
            paths.add(node.normalizedPath());
        }
        return paths;
    }

    private static List<JsonValue> list(String jsonArray) {
        return new ArrayList<>(json(jsonArray).asJsonArray());
    }

    /** {@code depth} arrays nested in each other around a string. */
    static JsonValue nestedArrays(int depth) {
        JsonValue value = PROVIDER.createValue("x");
        for (int i = 0; i < depth; i++) {
            value = PROVIDER.createArrayBuilder().add(value).build();
        }
        return value;
    }

    @Test
    void selectsValuesWithFilter() {
        assertEquals(list("['Sayings of the Century', 'Moby Dick']"), values("$.store.book[?@.price < 10].title", STORE));
        assertEquals(Arrays.asList("$['store']['book'][0]['title']", "$['store']['book'][2]['title']"),
                paths("$.store.book[?@.price < 10].title", STORE));
    }

    @Test
    void comparesNumbersByValueNotByScale() {
        JsonValue doc = json("[{'n': 1}, {'n': 1.0}, {'n': 1.00}, {'n': 1e0}, {'n': 10e-1}, {'n': 2}, {'n': -0}]");

        assertEquals(5, query("$[?@.n == 1]", doc).size());
        assertEquals(1, query("$[?@.n == 0.0]", doc).size());
        assertEquals(6, query("$[?@.n < 2]", doc).size());
        assertEquals(6, query("$[?@.n >= 1.0e0]", doc).size());
    }

    @Test
    void comparesArraysAndObjectsStructurallyWithNumbersByValue() {
        JsonValue doc = json("[{'a': [1, {'x': 1.0, 'y': [true, null]}], 'b': [1.0, {'y': [true, null], 'x': 1}]},"
                + "{'a': [1, 2], 'b': [2, 1]}, {'a': {'x': 1}, 'b': {'x': 1, 'y': 2}}]");

        assertEquals(Collections.singletonList("$[0]"), paths("$[?@.a == @.b]", doc));
        assertEquals(Arrays.asList("$[1]", "$[2]"), paths("$[?@.a != @.b]", doc));
    }

    @Test
    void distinguishesJsonNullFromMissingMember() {
        List<NodeImpl> nodes = query("$.a", json("{'a': null}"));

        assertEquals(1, nodes.size());
        assertSame(JsonValue.NULL, nodes.get(0).value());
        assertEquals("$['a']", nodes.get(0).normalizedPath());
        assertTrue(query("$.a", json("{}")).isEmpty());
        assertEquals(Collections.singletonList("$[0]"), paths("$[?@.a]", json("[{'a': null}, {}]")));
        assertEquals(Collections.singletonList("$[0]"), paths("$[?@.a == null]", json("[{'a': null}, {}]")));
    }

    @Test
    void comparesStringsByCodePoints() {
        // UTF-16 code units order U+E000 after U+1F600 (a surrogate pair); code points do not.
        JsonValue doc = json("['\\ue000', '\\ud83d\\ude00']");

        assertEquals(list("['\\ue000']"), values("$[?@ < '\\ud83d\\ude00']", doc));
        assertEquals(list("['\\ud83d\\ude00']"), values("$[?@ > '\\ue000']", doc));
    }

    @Test
    void countsCodePointsInLength() {
        JsonValue doc = json("['\\ud83d\\ude00', 'ab', [1], {'a': 1, 'b': 2}, 1, true, null]");

        assertEquals(list("['\\ud83d\\ude00', [1]]"), values("$[?length(@) == 1]", doc));
        assertEquals(list("['ab', {'a': 1, 'b': 2}]"), values("$[?length(@) == 2]", doc));
    }

    @Test
    void escapesNormalizedPaths() {
        JsonValue doc = json("{'it\\u0027s\\n': 1, '\\u0001\\u001f\\b\\f\\r\\t\\\\/\\u007f': 2}");

        assertEquals(Arrays.asList("$['it\\'s\\n']", "$['\\u0001\\u001f\\b\\f\\r\\t\\\\/\u007f']"), paths("$.*", doc));
    }

    @Test
    void selectsANodeAsOftenAsItIsSelected() {
        assertEquals(Arrays.asList("$[0]", "$[0]", "$[0]"), paths("$[0, 0, -1]", json("[1]")));
        assertEquals(Arrays.asList("$[0]", "$[0]"), paths("$[*, 0]", json("[1]")));
    }

    @Test
    void keepsTheMemberOrderOfTheObject() {
        assertEquals(Arrays.asList("$['z']", "$['a']", "$['m']"), paths("$.*", json("{'z': 1, 'a': 2, 'm': 3}")));
        // The descendants in document order, each with the wildcard: the root's children, then those of 'z'.
        assertEquals(Arrays.asList("$['z']", "$['a']", "$['z'][0]"), paths("$..*", json("{'z': [1], 'a': 2}")));
    }

    @Test
    void reusesAbsoluteQueriesInFilters() {
        assertEquals(list("[8.95, 8.99]"), values("$.store.book[?@.price < $.limit].price", STORE));
        assertEquals(list("[399]"), values("$.store[?count($..book[*]) == 3].price", STORE));
        assertEquals(list("['red']"), values("$.store[?value($.limit) == 9].color", STORE));
    }

    @Test
    void walksDeepDocumentsWithoutOverflowingTheStack() {
        JsonValue deep = nestedArrays(100_000);

        assertEquals(100_000, query("$..*", deep).size());
        assertEquals(1, query("$..[?@ == 'x']", deep).size());
    }

    @Test
    void comparesDeepValuesWithoutOverflowingTheStack() {
        JsonValue doc = PROVIDER.createArrayBuilder()
                .add(PROVIDER.createObjectBuilder().add("a", nestedArrays(100_000)).add("b", nestedArrays(100_000)))
                .build();

        assertEquals(1, query("$[?@.a == @.b]", doc).size());
    }

    @Test
    void matchesWholeStringsAndSearchesSubstrings() {
        JsonValue doc = json("['abc', 'xabcx', 'ab\\nc', 'ab\\u2028c']");

        assertEquals(list("['abc']"), values("$[?match(@, 'a.c')]", doc));
        assertEquals(list("['abc', 'xabcx']"), values("$[?search(@, 'a.c')]", doc));
        assertEquals(list("['ab\\u2028c']"), values("$[?match(@, 'ab.c')]", doc));
    }

    @Test
    void givesEachCallOfAQueryItsOwnPatternCache() {
        // More distinct patterns than a cache holds, in two calls: neither call evicts the other's patterns.
        JsonArrayBuilder items = PROVIDER.createArrayBuilder();
        for (int i = 0; i < 500; i++) {
            items.add(PROVIDER.createObjectBuilder().add("s", "x" + i + "y").add("p", "x" + i + "y").add("q", i + "y"));
        }

        assertEquals(500, query("$[?match(@.s, @.p) && search(@.s, @.q)]", items.build()).size());
    }

    @Test
    void rejectsPatternsThatAreNotIRegexp() {
        JsonValue doc = json("['1', 'a', 'A']");

        // \\d and inline flags are java.util.regex, not I-Regexp (RFC 9485): invalid, so no match.
        assertTrue(query("$[?match(@, '\\\\d')]", doc).isEmpty());
        assertTrue(query("$[?match(@, '(?i)a')]", doc).isEmpty());
        assertEquals(list("['1']"), values("$[?match(@, '[0-9]')]", doc));
    }

    @Test
    void matchesHostilePatternsInLinearTime() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            input.append('a');
        }
        JsonValue doc = PROVIDER.createArrayBuilder().add(input.toString()).build();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertTrue(query("$[?match(@, '((a|a)*)*b')]", doc).isEmpty());
            assertTrue(query("$[?search(@, '(a|aa){1,60}b')]", doc).isEmpty());
            assertTrue(query("$[?match(@, '.*.*.*.*!')]", doc).isEmpty());
        });
    }

    @Test
    void treatsInvalidPatternsAsNoMatch() {
        JsonValue doc = json("[{'s': 'a', 'p': '['}, {'s': 'a', 'p': 'a'}]");

        assertTrue(query("$[?match(@, '[')]", json("['[']")).isEmpty());
        assertEquals(Collections.singletonList("$[1]"), paths("$[?match(@.s, @.p)]", doc));
    }
}

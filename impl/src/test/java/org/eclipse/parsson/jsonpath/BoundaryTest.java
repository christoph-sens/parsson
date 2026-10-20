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

import static org.eclipse.parsson.jsonpath.EvaluatorTest.json;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonPath;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.parsson.JsonProviderImpl;
import org.junit.jupiter.api.Test;

/**
 * Boundaries that the Compliance Test Suite and the other tests leave open, found as surviving mutants of a PIT run
 * (evidence/mutation in christoph-sens/jsonp-api, branch evidence): string comparison by code points, the limits at
 * their exact boundary for every selector kind, the slice arithmetic against the algorithm of RFC 9535 section
 * 2.3.4.2, and the validation of limits.
 */
class BoundaryTest {

    private static final JsonProvider PROVIDER = new JsonProviderImpl();

    private static List<JsonPath.Node> query(String query, JsonValue document, Map<String, ?> config) {
        return PROVIDER.createPath(query, config).query(document);
    }

    private static List<String> strings(List<JsonPath.Node> nodes) {
        List<String> out = new ArrayList<>();
        for (JsonPath.Node node : nodes) {
            out.add(((JsonString) node.getValue()).getString());
        }
        return out;
    }

    // ---- strings are ordered by code points, not by UTF-16 code units

    @Test
    void ordersStringsByCodePoints() {
        // U+FF5E is a single UTF-16 unit above the surrogates; U+1F600 and U+1F601 are surrogate pairs
        JsonValue doc = json("['～', '😀', '😁', 'a', 'ab', '', 'b']");
        Map<String, ?> none = Map.of();

        assertEquals(List.of("😁"), strings(query("$[?@ > '😀']", doc, none)));
        assertEquals(List.of("😀", "😁"), strings(query("$[?@ > '～']", doc, none)));
        assertEquals(List.of("～", "a", "ab", "", "b"), strings(query("$[?@ < '😀']", doc, none)));
        assertEquals(List.of("～", "😀", "😁", "ab", "b"), strings(query("$[?@ > 'a']", doc, none)));
        assertEquals(List.of("～", "😀", "😁", "ab", "b"), strings(query("$[?@ >= 'ab']", doc, none)));
        assertEquals(List.of("a", ""), strings(query("$[?@ <= 'a']", doc, none)));
        assertEquals(List.of(""), strings(query("$[?@ < 'a']", doc, none)));
        assertEquals(List.of("ab"), strings(query("$[?@ == 'ab']", doc, none)));
        assertEquals(List.of("😀"), strings(query("$[?@ <= '😀' && @ >= '😀']", doc, none)));
    }

    // ---- limits at their exact boundary, for every kind of selection

    private static final JsonValue NESTED = json("{'a': [[1, 2]], 'b': 3}");

    /** Query and the depth of the deepest node it reaches (the root has depth 0). */
    private static final Object[][] DEPTHS = {
        {"$.a", 1}, {"$['a']", 1}, {"$.*", 1}, {"$[?@]", 1},
        {"$.a[0]", 2}, {"$.a[*]", 2}, {"$.a[-1]", 2}, {"$.a[0:1]", 2}, {"$.a[::-1]", 2}, {"$.a[?@]", 2},
        {"$.a[0][1]", 3}, {"$.a[0][-1]", 3}, {"$.a[0][*]", 3}, {"$.a[0][0:2]", 3}, {"$.a[0][?@ > 1]", 3},
        {"$..*", 3}, {"$..a", 3}, {"$..[1]", 3}, {"$..[?@ > 1]", 3},
    };

    @Test
    void reachesNodesUpToTheDepthLimitForEverySelector() {
        for (Object[] row : DEPTHS) {
            String query = (String) row[0];
            int depth = (Integer) row[1];
            assertDoesNotThrow(() -> query(query, NESTED, Map.of(JsonPath.MAX_DEPTH, depth)), query + " at depth " + depth);
            assertThrows(JsonException.class, () -> query(query, NESTED, Map.of(JsonPath.MAX_DEPTH, depth - 1)),
                    query + " at depth " + (depth - 1));
        }
    }

    @Test
    void countsVisitsForEverySelector() {
        // Each of these selects something, so a limit of zero must fail, whatever the exact count of visits.
        String[] queries = {
            "$.b", "$['b']", "$.*", "$[?@]", "$.a[0]", "$.a[*]", "$.a[-1]", "$.a[0:1]", "$.a[::-1]", "$.a[?@]",
            "$.a[0][1]", "$.a[0][-1]", "$.a[0][*]", "$.a[0][0:2]", "$.a[0][?@ > 1]", "$..*", "$..a", "$..[1]", "$..[?@ > 1]",
        };
        for (String query : queries) {
            assertDoesNotThrow(() -> query(query, NESTED, Map.of()), query);
            assertThrows(JsonException.class, () -> query(query, NESTED, Map.of(JsonPath.MAX_VISITED_NODES, 0)), query);
            int selected = query(query, NESTED, Map.of()).size();
            assertDoesNotThrow(() -> query(query, NESTED, Map.of(JsonPath.MAX_VISITED_NODES, 100)), query);
            // never fewer visits than selected nodes (JsonPath.MAX_VISITED_NODES)
            if (selected > 0) {
                assertThrows(JsonException.class,
                        () -> query(query, NESTED, Map.of(JsonPath.MAX_VISITED_NODES, selected - 1)), query);
            }
        }
    }

    @Test
    void rejectsNegativeLimitsAndAcceptsZero() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(-1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, -1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, -1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 1, -1));
        assertDoesNotThrow(() -> new Limits(0, 0, 0, 0, 0, 0));
    }

    // ---- slices against the algorithm of RFC 9535 section 2.3.4.2

    private static List<Integer> expectedSlice(int len, Integer start, Integer end, Integer step) {
        List<Integer> out = new ArrayList<>();
        int st = step == null ? 1 : step;
        if (st == 0) {
            return out;
        }
        int s = start != null ? start : (st > 0 ? 0 : len - 1);
        int e = end != null ? end : (st > 0 ? len : -len - 1);
        int nStart = s >= 0 ? s : len + s;
        int nEnd = e >= 0 ? e : len + e;
        if (st > 0) {
            int lower = Math.min(Math.max(nStart, 0), len);
            int upper = Math.min(Math.max(nEnd, 0), len);
            for (int i = lower; i < upper; i += st) {
                out.add(i);
            }
        } else {
            int upper = Math.min(Math.max(nStart, -1), len - 1);
            int lower = Math.min(Math.max(nEnd, -1), len - 1);
            for (int i = upper; lower < i; i += st) {
                out.add(i);
            }
        }
        return out;
    }

    @Test
    void slicesLikeTheAlgorithmOfTheRfc() {
        Integer[] bounds = {null, -9, -7, -6, -3, -1, 0, 1, 3, 5, 6, 7, 9};
        Integer[] steps = {null, 1, 2, 3, -1, -2, -3, 0, 1000, -1000};
        for (int len = 0; len <= 6; len++) {
            JsonArrayBuilder builder = PROVIDER.createArrayBuilder();
            for (int i = 0; i < len; i++) {
                builder.add(i);
            }
            JsonArray array = builder.build();
            for (Integer start : bounds) {
                for (Integer end : bounds) {
                    for (Integer step : steps) {
                        String slice = "[" + (start == null ? "" : start) + ":" + (end == null ? "" : end)
                                + (step == null ? "" : ":" + step) + "]";
                        List<Integer> actual = new ArrayList<>();
                        for (JsonPath.Node node : PROVIDER.createPath("$" + slice).query(array)) {
                            actual.add(Integer.parseInt(node.getValue().toString()));
                        }
                        assertEquals(expectedSlice(len, start, end, step), actual, "length " + len + " " + slice);
                    }
                }
            }
        }
    }

    // ---- lexical boundaries of the grammar (RFC 9535 section 2.3.1.1 and 2.1)

    private static boolean compiles(String query) {
        try {
            PROVIDER.createPath(query);
            return true;
        } catch (JsonException e) {
            return false;
        }
    }

    @Test
    void acceptsNameShorthandsUpToTheEdgesOfTheirCharacterRanges() {
        // name-first = ALPHA / "_" / %x80-D7FF / %xE000-10FFFF
        for (String first : new String[] {"a", "z", "A", "Z", "_", "\u0080", "\u00ff", "\uD7FF", "\uE000", "\uFFFF",
                "\uD83D\uDE00", "\uD800\uDC00", "\uDBFF\uDFFF"}) {
            assertEquals(true, compiles("$." + first), "name starting with U+" + Integer.toHexString(first.codePointAt(0)));
        }
        for (String first : new String[] {"0", "9", "-", "@", "[", "`", "{", "\u007F", "/", ":", "$"}) {
            assertEquals(false, compiles("$." + first), "name starting with U+" + Integer.toHexString(first.codePointAt(0)));
        }
        // name-char = name-first / DIGIT
        for (String rest : new String[] {"0", "9", "a", "Z", "_", "\u0080", "\uD7FF", "\uE000", "\uD83D\uDE00"}) {
            assertEquals(true, compiles("$.a" + rest), "name continuing with U+" + Integer.toHexString(rest.codePointAt(0)));
        }
        for (String rest : new String[] {"-", "/", ":", "@", "[", "`", "{", "\u007F", " x"}) {
            assertEquals(false, compiles("$.a" + rest), "name continuing with U+" + Integer.toHexString(rest.codePointAt(0)));
        }
    }

    @Test
    void acceptsEscapesInStringLiteralsUpToTheEdgesOfTheirRanges() {
        JsonValue doc = json("{'A': 1, 'a': 2, '0': 3, '9': 4, 'F': 5, 'f': 6, '\u0ff0': 7}");
        // hex digits: 0-9, a-f, A-F, at both ends of each range
        for (String digit : new String[] {"0", "9", "a", "f", "A", "F"}) {
            assertEquals(true, compiles("$['\\u000" + digit + "']"), digit);
            assertEquals(true, compiles("$['\\u00" + digit + "0']"), digit);
            assertEquals(true, compiles("$['\\u0" + digit + "00']"), digit);
            assertEquals(true, compiles("$['\\u" + digit + "000']"), digit);
        }
        for (String bad : new String[] {"g", "G", "/", ":", "@", "`", "x", " "}) {
            assertEquals(false, compiles("$['\\u000" + bad + "']"), bad);
            assertEquals(false, compiles("$['\\u00" + bad + "0']"), bad);
            assertEquals(false, compiles("$['\\u0" + bad + "00']"), bad);
            assertEquals(false, compiles("$['\\u" + bad + "000']"), bad);
        }
        assertEquals(false, compiles("$['\\u00']"));
        assertEquals(1, query("$['\\u0041']", doc, Map.of()).size());
        assertEquals(1, query("$['\\u0ff0']", doc, Map.of()).size());
        assertEquals(2, query("$['\\u0066', '\\u0046']", doc, Map.of()).size());
        // the single-character escapes, and the quote that is escapable only in its own kind of literal
        for (String escape : new String[] {"b", "f", "n", "r", "t", "/", "\\"}) {
            assertEquals(true, compiles("$['\\" + escape + "']"), escape);
            assertEquals(true, compiles("$[\"\\" + escape + "\"]"), escape);
        }
        assertEquals(true, compiles("$['\\'']"));
        assertEquals(false, compiles("$['\\\"']"));
        assertEquals(true, compiles("$[\"\\\"\"]"));
        assertEquals(false, compiles("$[\"\\'\"]"));
        for (String bad : new String[] {"a", "x", "0", "u", "U", "e", "v", "'", " "}) {
            if (!bad.equals("'")) {
                assertEquals(false, compiles("$['\\" + bad + "']"), bad);
            }
        }
        // a surrogate pair is one character; a lone surrogate is not allowed
        assertEquals(true, compiles("$['\\uD83D\\uDE00']"));
        assertEquals(false, compiles("$['\\uD83D']"));
        assertEquals(false, compiles("$['\\uDE00']"));
        assertEquals(false, compiles("$['\\uDE00\\uD83D']"));
        // unescaped characters: control characters are not allowed, 0x20 and above are
        assertEquals(false, compiles("$['\u0000']"));
        assertEquals(false, compiles("$['\u001F']"));
        assertEquals(true, compiles("$[' ']"));
        assertEquals(true, compiles("$['\u007F']"));
    }
}

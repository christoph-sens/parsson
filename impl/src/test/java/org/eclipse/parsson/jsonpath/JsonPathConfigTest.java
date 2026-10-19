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
import static org.eclipse.parsson.jsonpath.StreamingCheck.PROVIDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonException;
import jakarta.json.JsonPath;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;

/**
 * Tests of {@code createPath(String, Map)}: the configuration properties of {@link JsonPath}.
 */
class JsonPathConfigTest {

    private static JsonPath path(String query, String property, Object value) {
        return PROVIDER.createPath(query, Collections.singletonMap(property, value));
    }

    @Test
    void usesTheDefaultsForAnEmptyMapAndIgnoresUnknownProperties() {
        Map<String, Object> config = new HashMap<>();
        config.put("org.example.unknown", "whatever");
        JsonValue document = json("[1, 2, 3]");
        assertEquals(3, PROVIDER.createPath("$[*]", Collections.<String, Object>emptyMap()).query(document).size());
        assertEquals(3, PROVIDER.createPath("$[*]", config).query(document).size());
        assertThrows(NullPointerException.class, () -> PROVIDER.createPath("$", null));
        assertThrows(NullPointerException.class, () -> PROVIDER.createPath(null, config));
    }

    @Test
    void acceptsNumbersAndDecimalStrings() {
        JsonValue document = json("[1, 2, 3]");
        for (Object value : new Object[] {2, 2L, (short) 2, BigInteger.valueOf(2), new BigDecimal("2.0"), 2.0, "2",
            " 2 "}) {
            JsonPath path = path("$[*]", JsonPath.MAX_RESULT_SIZE, value);
            assertEquals("Query selected more than 2 nodes, the limit",
                    assertThrows(JsonException.class, () -> path.query(document)).getMessage(), String.valueOf(value));
        }
        assertEquals(3, path("$[*]", JsonPath.MAX_RESULT_SIZE, Long.MAX_VALUE).query(document).size());
    }

    @Test
    void rejectsInvalidValues() {
        for (Object value : new Object[] {-1, "-1", "abc", 1.5, "", Boolean.TRUE, new Object()}) {
            for (String property : new String[] {JsonPath.MAX_VISITED_NODES, JsonPath.MAX_DEPTH,
                JsonPath.MAX_RESULT_SIZE, JsonPath.MAX_NESTING, JsonPath.MAX_REGEX_SIZE}) {
                JsonException e = assertThrows(JsonException.class, () -> path("$", property, value),
                        property + "=" + value);
                assertTrue(e.getMessage().contains(property), e.getMessage());
            }
        }
    }

    @Test
    void limitsVisitedNodesAndDepth() {
        JsonValue document = json("[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]");
        assertEquals(10, path("$[*]", JsonPath.MAX_VISITED_NODES, 10).query(document).size());
        assertThrows(JsonException.class, () -> path("$[*]", JsonPath.MAX_VISITED_NODES, 9).query(document));
        JsonValue deep = json("[[[[1]]]]");
        assertEquals(1, path("$..[?@ == 1]", JsonPath.MAX_DEPTH, 4).query(deep).size());
        assertThrows(JsonException.class, () -> path("$..[?@ == 1]", JsonPath.MAX_DEPTH, 3).query(deep));
    }

    @Test
    void limitsTheNestingOfTheQuery() {
        assertEquals(1, path("$[?@.a]", JsonPath.MAX_NESTING, 1).query(json("[{'a': 1}]")).size());
        JsonException e = assertThrows(JsonException.class, () -> path("$[?(@.a)]", JsonPath.MAX_NESTING, 1));
        assertTrue(e.getMessage().startsWith("Expression is nested more than 1 levels deep"), e.getMessage());
        assertThrows(JsonException.class, () -> path("$[?length(@) > 1]", JsonPath.MAX_NESTING, 1));
        path("$[?@[?length(@) > 1]]", JsonPath.MAX_NESTING, 3);
        assertThrows(JsonException.class, () -> path("$[?@[?length(@) > 1]]", JsonPath.MAX_NESTING, 2));
        String nested = "$" + new String(new char[64]).replace("\0", "[?@") + new String(new char[64]).replace("\0", "]");
        PROVIDER.createPath(nested);
        assertThrows(JsonException.class, () -> PROVIDER.createPath("$[?@" + nested.substring(1) + "]"));
    }

    @Test
    void limitsTheSizeOfRegularExpressions() {
        JsonValue document = json("[{'s': 'aaaa', 'p': 'a{4}'}, {'s': 'a', 'p': 'a'}]");
        assertEquals(1, path("$[?match(@.s, 'a{4}')]", JsonPath.MAX_REGEX_SIZE, 5).query(document).size());
        assertEquals(0, path("$[?match(@.s, 'a{4}')]", JsonPath.MAX_REGEX_SIZE, 4).query(document).size());
        assertEquals(2, path("$[?match(@.s, @.p)]", JsonPath.MAX_REGEX_SIZE, 5).query(document).size());
        assertEquals(1, path("$[?match(@.s, @.p)]", JsonPath.MAX_REGEX_SIZE, 4).query(document).size());
        assertEquals(0, PROVIDER.createPath("$[?match(@, 'a{30000}')]").query(json("['a']")).size());
    }
}

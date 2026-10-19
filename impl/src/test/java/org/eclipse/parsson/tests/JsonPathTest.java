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

package org.eclipse.parsson.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonPath;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.eclipse.parsson.JsonProviderImpl;
import org.eclipse.parsson.api.JsonConfig;
import org.junit.jupiter.api.Test;

/**
 * Tests of JSONPath through the JSON-P API: {@link Json#createPath(String)}.
 */
public class JsonPathTest {

    private static JsonValue json(String text) {
        try (JsonReader reader = Json.createReader(new StringReader(text.replace('\'', '"')))) {
            return reader.readValue();
        }
    }

    @Test
    void createsPathsThroughTheApi() {
        JsonValue doc = json("{'store': {'book': [{'title': 'A', 'price': 8.95}, {'title': 'B', 'price': 12.99}]}}");

        List<JsonPath.Node> nodes = Json.createPath("$.store.book[?@.price < 10].title").query(doc);

        assertEquals(1, nodes.size());
        assertEquals(Json.createValue("A"), nodes.get(0).getValue());
        assertEquals("$['store']['book'][0]['title']", nodes.get(0).getPath());
        assertEquals("/store/book/0/title", nodes.get(0).getPointer().toString());
        assertEquals(Json.createValue("A"), nodes.get(0).getPointer().getValue(doc.asJsonObject()));
    }

    @Test
    void keepsTheQueryString() {
        assertEquals("$..a", Json.createPath("$..a").toString());
    }

    @Test
    void reportsSyntaxErrorsAsJsonException() {
        JsonException e = assertThrows(JsonException.class, () -> Json.createPath("$.a[?@.b <]"));

        assertTrue(e.getMessage().startsWith("Expected a query, a literal or a function at position 10:"),
                e.getMessage());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> Json.createPath(null));
        assertThrows(NullPointerException.class, () -> Json.createPath("$").query(null));
    }

    @Test
    void readsTheVisitLimitFromASystemProperty() {
        JsonArrayBuilder numbers = Json.createArrayBuilder();
        for (int i = 0; i < 10; i++) {
            numbers.add(i);
        }
        JsonValue doc = numbers.build();
        String property = "org.eclipse.parsson.jsonpath.maxVisitedNodes";
        System.setProperty(property, "9");
        try {
            JsonPath path = new JsonProviderImpl().createPath("$[*]");

            assertEquals("Query visited more than 9 nodes, the limit",
                    assertThrows(JsonException.class, () -> path.query(doc)).getMessage());
        } finally {
            System.clearProperty(property);
        }
        assertEquals(10, new JsonProviderImpl().createPath("$[*]").query(doc).size());
    }

    @Test
    void readsTheResultLimitFromASystemProperty() {
        String property = "org.eclipse.parsson.jsonpath.maxResultSize";
        System.setProperty(property, "1");
        try {
            JsonPath path = new JsonProviderImpl().createPath("$[*]");

            assertEquals(1, path.query(json("[1]")).size());
            assertThrows(JsonException.class, () -> path.query(json("[1, 2]")));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void usesTheMaximumDepthOfParsson() {
        JsonValue deep = JsonValue.EMPTY_JSON_ARRAY;
        for (int i = 0; i < 20; i++) {
            deep = Json.createArrayBuilder().add(deep).build();
        }
        System.setProperty(JsonConfig.MAX_DEPTH, "10");
        try {
            JsonPath path = new JsonProviderImpl().createPath("$..*");
            JsonValue doc = deep;

            assertTrue(assertThrows(JsonException.class, () -> path.query(doc)).getMessage().contains("10 levels"));
        } finally {
            System.clearProperty(JsonConfig.MAX_DEPTH);
        }
        assertEquals(20, new JsonProviderImpl().createPath("$..*").query(deep).size());
    }
}

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonPath;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.JsonProviderImpl;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Tests of {@link JsonPathImpl} and the nodes it returns: values, normalized paths and JSON Pointers.
 */
class JsonPathImplTest {

    private static final JsonProvider PROVIDER = new JsonProviderImpl();

    private static List<JsonPath.Node> query(String query, JsonValue document) {
        return JsonPathImpl.create(query, PROVIDER, Limits.DEFAULT).query(document);
    }

    private static List<String> pointers(String query, JsonValue document) {
        List<String> pointers = new ArrayList<>();
        for (JsonPath.Node node : query(query, document)) {
            pointers.add(node.getPointer().toString());
        }
        return pointers;
    }

    /** Every node that a case of the Compliance Test Suite selects is found again by its JSON Pointer. */
    @TestFactory
    Stream<DynamicTest> pointersOfSelectedNodesReferenceTheirValues() {
        return ComplianceSuite.cases().stream()
                .filter(test -> !test.invalidSelector)
                .map(test -> DynamicTest.dynamicTest(test.toString(), () -> {
                    for (JsonPath.Node node : query(test.selector, test.document)) {
                        String pointer = node.getPointer().toString();
                        if (pointer.isEmpty()) {
                            assertSame(test.document, node.getValue(), node.getPath());
                        } else {
                            JsonValue found = node.getPointer().getValue((JsonStructure) test.document);
                            assertSame(node.getValue(), found, node.getPath() + " at " + pointer);
                        }
                    }
                }));
    }

    @Test
    void escapesNamesInPointersAndPaths() {
        JsonValue doc = EvaluatorTest.json("{'a/b': {'~c': [0, 1]}, '': 2, '~01': 3}");

        assertEquals(Collections.singletonList("/a~1b/~0c/1"), pointers("$['a/b']['~c'][1]", doc));
        assertEquals(Collections.singletonList("$['a/b']['~c'][1]"), paths("$['a/b']['~c'][1]", doc));
        assertEquals(Collections.singletonList("/"), pointers("$['']", doc));
        assertEquals(Collections.singletonList("/~001"), pointers("$['~01']", doc));
    }

    @Test
    void returnsTheEmptyPointerForTheRoot() {
        List<JsonPath.Node> nodes = query("$", PROVIDER.createValue("x"));

        assertEquals(1, nodes.size());
        assertEquals("", nodes.get(0).getPointer().toString());
        assertEquals("$", nodes.get(0).getPath());
        assertEquals(PROVIDER.createValue("x"), nodes.get(0).getValue());
    }

    @Test
    void selectsNothingFromPrimitivesBelowTheRoot() {
        assertTrue(query("$.a", PROVIDER.createValue(1)).isEmpty());
        assertTrue(query("$[0]", JsonValue.TRUE).isEmpty());
        assertTrue(query("$..*", JsonValue.NULL).isEmpty());
    }

    @Test
    void returnsJsonNullAsAValue() {
        List<JsonPath.Node> nodes = query("$.a", EvaluatorTest.json("{'a': null}"));

        assertSame(JsonValue.NULL, nodes.get(0).getValue());
        assertEquals("/a", nodes.get(0).getPointer().toString());
    }

    @Test
    void keepsTheQueryString() {
        assertEquals("$.a[?@.b > 1]", JsonPathImpl.create("$.a[?@.b > 1]", PROVIDER, Limits.DEFAULT).toString());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> JsonPathImpl.create(null, PROVIDER, Limits.DEFAULT));
        assertThrows(NullPointerException.class, () -> JsonPathImpl.create("$", PROVIDER, Limits.DEFAULT).query(null));
    }

    @Test
    void returnsAnUnmodifiableList() {
        List<JsonPath.Node> nodes = query("$[*]", EvaluatorTest.json("[1, 2]"));

        assertThrows(UnsupportedOperationException.class, () -> nodes.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> nodes.add(nodes.get(0)));
    }

    @Test
    void queriesValuesOfOtherImplementations() {
        Map<String, JsonValue> book = new LinkedHashMap<>();
        book.put("title", PROVIDER.createValue("Moby Dick"));
        book.put("price", PROVIDER.createValue(8.99));
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("books", PROVIDER.createArrayBuilder().add(new MapJsonObject(book)).build());
        root.put("limit", PROVIDER.createValue(new BigDecimal("9.00")));
        JsonObject doc = new MapJsonObject(root);

        List<JsonPath.Node> nodes = query("$.books[?@.price < $.limit && @ == $.books[0]].title", doc);

        assertEquals(1, nodes.size());
        assertEquals("$['books'][0]['title']", nodes.get(0).getPath());
        assertEquals("/books/0/title", nodes.get(0).getPointer().toString());
        assertSame(book.get("title"), nodes.get(0).getValue());
        assertEquals(Collections.singletonList("$['books'][0]"), paths("$..[?length(@) == 2]", doc));
    }

    private static List<String> paths(String query, JsonValue document) {
        List<String> paths = new ArrayList<>();
        for (JsonPath.Node node : query(query, document)) {
            paths.add(node.getPath());
        }
        return paths;
    }

    /** A {@link JsonObject} that is not Parsson's, on a map. */
    private static final class MapJsonObject extends AbstractMap<String, JsonValue> implements JsonObject {

        private final Map<String, JsonValue> map;

        MapJsonObject(Map<String, JsonValue> map) {
            this.map = map;
        }

        @Override
        public Set<Entry<String, JsonValue>> entrySet() {
            return Collections.unmodifiableMap(map).entrySet();
        }

        @Override
        public JsonArray getJsonArray(String name) {
            return (JsonArray) map.get(name);
        }

        @Override
        public JsonObject getJsonObject(String name) {
            return (JsonObject) map.get(name);
        }

        @Override
        public JsonNumber getJsonNumber(String name) {
            return (JsonNumber) map.get(name);
        }

        @Override
        public JsonString getJsonString(String name) {
            return (JsonString) map.get(name);
        }

        @Override
        public String getString(String name) {
            return getJsonString(name).getString();
        }

        @Override
        public String getString(String name, String defaultValue) {
            return map.get(name) instanceof JsonString ? getString(name) : defaultValue;
        }

        @Override
        public int getInt(String name) {
            return getJsonNumber(name).intValue();
        }

        @Override
        public int getInt(String name, int defaultValue) {
            return map.get(name) instanceof JsonNumber ? getInt(name) : defaultValue;
        }

        @Override
        public boolean getBoolean(String name) {
            JsonValue value = map.get(name);
            if (value == JsonValue.TRUE || value == JsonValue.FALSE) {
                return value == JsonValue.TRUE;
            }
            throw new ClassCastException();
        }

        @Override
        public boolean getBoolean(String name, boolean defaultValue) {
            JsonValue value = map.get(name);
            return value == JsonValue.TRUE || value == JsonValue.FALSE ? value == JsonValue.TRUE : defaultValue;
        }

        @Override
        public boolean isNull(String name) {
            return map.get(name) == JsonValue.NULL;
        }

        @Override
        public ValueType getValueType() {
            return ValueType.OBJECT;
        }
    }
}

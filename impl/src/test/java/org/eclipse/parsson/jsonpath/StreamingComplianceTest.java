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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import jakarta.json.JsonException;
import jakarta.json.JsonPath;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The streaming matcher against the tree evaluation: every valid case of the JSONPath Compliance Test Suite,
 * every index and slice on arrays of length 0 to 9, and random queries on random documents.
 */
class StreamingComplianceTest {

    @TestFactory
    Stream<DynamicTest> complianceSuite() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ComplianceSuite.Case test : ComplianceSuite.cases()) {
            if (test.invalidSelector) {
                continue;
            }
            String json = test.document.toString();
            tests.add(DynamicTest.dynamicTest(test.toString(), () -> {
                JsonPath path = StreamingCheck.PROVIDER.createPath(test.selector);
                if (path.getBuffering() == JsonPath.Buffering.UNBOUNDED) {
                    assertThrows(JsonException.class, () -> path.matcher(
                            StreamingCheck.PROVIDER.createParser(new java.io.StringReader(json))));
                } else {
                    StreamingCheck.check(test.selector, json);
                }
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> indicesAndSlices() {
        String[] bounds = {"", "0", "1", "2", "4", "-1", "-2", "-4", "9"};
        String[] steps = {"", "1", "2", "3", "-1", "-2", "-3", "0"};
        List<String> selectors = new ArrayList<>();
        for (String index : new String[] {"0", "2", "-1", "-3", "8", "-9"}) {
            selectors.add(index);
        }
        for (String start : bounds) {
            for (String end : bounds) {
                for (String step : steps) {
                    selectors.add(start + ":" + end + (step.isEmpty() ? "" : ":" + step));
                }
            }
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (String selector : selectors) {
            tests.add(DynamicTest.dynamicTest(selector, () -> {
                for (int length = 0; length < 10; length++) {
                    StringBuilder array = new StringBuilder("[");
                    for (int i = 0; i < length; i++) {
                        array.append(i == 0 ? "" : ",").append("{\"v\":").append(i).append('}');
                    }
                    String json = array.append(']').toString();
                    String[] queries = {"$[" + selector + "]", "$[" + selector + "].v", "$[" + selector + ",1]",
                        "$..[" + selector + "]"};
                    for (String query : queries) {
                        if (StreamingCheck.PROVIDER.createPath(query).getBuffering() != JsonPath.Buffering.UNBOUNDED) {
                            StreamingCheck.check(query, json);
                            StreamingCheck.check("$[*]" + query.substring(1), "[" + json + ",[[1,2]]," + json + "]");
                        }
                    }
                }
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> randomQueries() {
        Random random = new Random(9535);
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String query = randomQuery(random);
            String json = randomValue(random, 0);
            tests.add(DynamicTest.dynamicTest(i + ": " + query + " on " + json, () -> {
                if (StreamingCheck.PROVIDER.createPath(query).getBuffering() != JsonPath.Buffering.UNBOUNDED) {
                    StreamingCheck.check(query, json);
                }
            }));
        }
        return tests.stream();
    }

    private static final String[] SELECTORS = {
        "'a'", "'b'", "*", "0", "1", "-1", "-2", "1:", ":2", "::2", "::-1", "-2:", "1:-1", "3:0:-2",
        "?@.a", "?@.b == 1", "?@ > 1", "?@..a", "?length(@) > 1", "?!@.a", "?@[-1] == 2", "?@.a.b"
    };

    private static String randomQuery(Random random) {
        StringBuilder query = new StringBuilder("$");
        int segments = 1 + random.nextInt(3);
        for (int s = 0; s < segments; s++) {
            query.append(random.nextInt(3) == 0 ? ".." : "").append('[');
            int selectors = 1 + (random.nextInt(4) == 0 ? 1 + random.nextInt(2) : 0);
            for (int i = 0; i < selectors; i++) {
                query.append(i == 0 ? "" : ",").append(SELECTORS[random.nextInt(SELECTORS.length)]);
            }
            query.append(']');
        }
        return query.toString();
    }

    private static String randomValue(Random random, int depth) {
        int kind = random.nextInt(depth >= 3 ? 3 : 6);
        switch (kind) {
            case 0:
                return String.valueOf(random.nextInt(4));
            case 1:
                return random.nextBoolean() ? "\"x\"" : "null";
            case 2:
                return random.nextBoolean() ? "true" : "1.0";
            case 3: {
                StringBuilder array = new StringBuilder("[");
                int size = random.nextInt(5);
                for (int i = 0; i < size; i++) {
                    array.append(i == 0 ? "" : ",").append(randomValue(random, depth + 1));
                }
                return array.append(']').toString();
            }
            default: {
                StringBuilder object = new StringBuilder("{");
                String[] names = {"a", "b", "c"};
                int size = random.nextInt(4);
                for (int i = 0; i < size; i++) {
                    object.append(i == 0 ? "" : ",").append('"').append(names[i]).append("\":")
                            .append(randomValue(random, depth + 1));
                }
                return object.append('}').toString();
            }
        }
    }
}

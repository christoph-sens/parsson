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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jakarta.json.JsonException;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.JsonProviderImpl;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the JSONPath Compliance Test Suite: invalid selectors must be rejected, valid ones must select the
 * expected values (compared as JSON values) at the expected normalized paths.
 */
class ComplianceTest {

    private static final JsonProvider PROVIDER = new JsonProviderImpl();

    @TestFactory
    Stream<DynamicTest> invalidSelectorsAreRejected() {
        return ComplianceSuite.cases().stream()
                .filter(test -> test.invalidSelector)
                .map(test -> DynamicTest.dynamicTest(test.toString(), () -> assertThrows(
                        JsonException.class, () -> Parser.parse(test.selector, PROVIDER),
                        "the selector should be rejected")));
    }

    @TestFactory
    Stream<DynamicTest> validSelectorsSelectTheExpectedNodes() {
        return ComplianceSuite.cases().stream()
                .filter(test -> !test.invalidSelector)
                .map(test -> DynamicTest.dynamicTest(test.toString(), () -> run(test)));
    }

    private static void run(ComplianceSuite.Case test) {
        List<NodeImpl> nodes = new Evaluator(test.document, PROVIDER, Limits.DEFAULT).run(Parser.parse(test.selector, PROVIDER));
        List<JsonValue> values = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (NodeImpl node : nodes) {
            values.add(node.getValue());
            paths.add(node.getPath());
        }
        for (int i = 0; i < test.results.size(); i++) {
            boolean pathsMatch = test.resultPaths.isEmpty() || paths.equals(test.resultPaths.get(i));
            if (pathsMatch && sameValues(values, test.results.get(i))) {
                return;
            }
        }
        fail("Expected " + (test.results.size() == 1 ? test.results.get(0) : "one of " + test.results)
                + (test.resultPaths.isEmpty() ? "" : " at " + test.resultPaths)
                + ", but the query selected " + values + " at " + paths);
    }

    private static boolean sameValues(List<JsonValue> actual, List<JsonValue> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!Values.jsonEquals(actual.get(i), expected.get(i), Integer.MAX_VALUE)) {
                return false;
            }
        }
        return true;
    }
}

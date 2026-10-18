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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.eclipse.parsson.JsonProviderImpl;

/**
 * The cases of the JSONPath Compliance Test Suite
 * (<a href="https://github.com/jsonpath-standard/jsonpath-compliance-test-suite">jsonpath-compliance-test-suite</a>).
 * The build downloads {@code cts.json} at a fixed commit and passes its location in the system property
 * {@code jsonpath.cts}; see {@code impl/pom.xml}.
 */
final class ComplianceSuite {

    /** One case of the suite. */
    static final class Case {

        final String name;
        final String selector;
        final boolean invalidSelector;
        /** The document, or {@code null} for an invalid selector. */
        final JsonValue document;
        /** The values the selector selects: one list, or several alternatives where member order is not defined. */
        final List<JsonArray> results;
        /** The normalized paths of the selected nodes, one list per alternative; empty if the case gives none. */
        final List<List<String>> resultPaths;

        Case(JsonObject test) {
            this.name = test.getString("name");
            this.selector = test.getString("selector");
            this.invalidSelector = test.getBoolean("invalid_selector", false);
            this.document = test.get("document");
            List<JsonArray> results = new ArrayList<>();
            List<List<String>> paths = new ArrayList<>();
            if (test.containsKey("result")) {
                results.add(test.getJsonArray("result"));
                if (test.containsKey("result_paths")) {
                    paths.add(strings(test.getJsonArray("result_paths")));
                }
            } else if (test.containsKey("results")) {
                results.addAll(test.getJsonArray("results").getValuesAs(JsonArray.class));
                if (test.containsKey("results_paths")) {
                    for (JsonArray alternative : test.getJsonArray("results_paths").getValuesAs(JsonArray.class)) {
                        paths.add(strings(alternative));
                    }
                }
            }
            this.results = Collections.unmodifiableList(results);
            this.resultPaths = Collections.unmodifiableList(paths);
        }

        @Override
        public String toString() {
            return name + " | " + selector;
        }
    }

    private static List<Case> cases;

    private ComplianceSuite() {
    }

    /** Returns the cases of the suite, in the order of the suite. */
    static synchronized List<Case> cases() {
        if (cases == null) {
            List<Case> list = new ArrayList<>();
            for (JsonObject test : read().getJsonArray("tests").getValuesAs(JsonObject.class)) {
                list.add(new Case(test));
            }
            cases = Collections.unmodifiableList(list);
        }
        return cases;
    }

    private static JsonObject read() {
        String location = System.getProperty("jsonpath.cts", "target/jsonpath-cts/cts.json");
        Path file = Paths.get(location);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("JSONPath Compliance Test Suite not found at " + file.toAbsolutePath()
                    + "; the Maven build downloads it in the phase generate-test-resources");
        }
        try (InputStream in = Files.newInputStream(file);
             JsonReader reader = new JsonProviderImpl().createReader(in)) {
            return reader.readObject();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    private static List<String> strings(JsonArray array) {
        List<String> strings = new ArrayList<>();
        for (JsonString s : array.getValuesAs(JsonString.class)) {
            strings.add(s.getString());
        }
        return strings;
    }
}

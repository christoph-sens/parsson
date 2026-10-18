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

import java.util.stream.Stream;

import jakarta.json.JsonException;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.JsonProviderImpl;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the JSONPath Compliance Test Suite: invalid selectors must be rejected, valid ones accepted.
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
    Stream<DynamicTest> validSelectorsAreAccepted() {
        return ComplianceSuite.cases().stream()
                .filter(test -> !test.invalidSelector)
                .map(test -> DynamicTest.dynamicTest(test.toString(), () -> Parser.parse(test.selector, PROVIDER)));
    }
}

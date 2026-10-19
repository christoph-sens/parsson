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

import java.math.BigDecimal;
import java.util.Map;

import jakarta.json.JsonException;
import jakarta.json.JsonPath;

import org.eclipse.parsson.jsonpath.regex.IRegexp;

/**
 * Limits for parsing and running a query, to bound the work spent on queries and documents from untrusted
 * sources (RFC 9535, section 4.1). A query that exceeds a limit fails with a {@link jakarta.json.JsonException}.
 *
 * <ul>
 *     <li>{@code maxVisitedNodes}: node visits per run; each node a selector produces, each node a descendant
 *     segment walks through and each node a filter tests counts, so the count is roughly proportional to the
 *     time a query takes. A streaming run also counts each value it reads from the parser.</li>
 *     <li>{@code maxResultSize}: nodes in a result.</li>
 *     <li>{@code maxDepth}: how deep a query walks into the query argument and how deep {@code ==} compares
 *     values; the root has depth 0, its children depth 1.</li>
 *     <li>{@code maxBufferedNodes}: nodes of the values a streaming run holds because it cannot report or
 *     decide them yet.</li>
 *     <li>{@code maxNesting}: nesting of filters, parenthesized expressions and function calls in the
 *     query string, checked by the parser.</li>
 *     <li>{@code maxRegexSize}: automaton instructions of a regular expression in {@code match()} and
 *     {@code search()}; a larger one is an invalid pattern.</li>
 * </ul>
 */
final class Limits {

    /** Default maximum number of node visits per run, which bounds a query to a few seconds of work. */
    static final long DEFAULT_MAX_VISITED_NODES = 100_000_000L;

    /** Default maximum number of nodes in a result: unlimited. */
    static final int DEFAULT_MAX_RESULT_SIZE = Integer.MAX_VALUE;

    /** Default maximum nesting depth, the default of Parsson's parser. */
    static final int DEFAULT_MAX_DEPTH = 1000;

    /** Default maximum number of nodes a streaming run holds, roughly a gigabyte. */
    static final long DEFAULT_MAX_BUFFERED_NODES = 10_000_000L;

    static final Limits DEFAULT = new Limits(DEFAULT_MAX_VISITED_NODES, DEFAULT_MAX_RESULT_SIZE, DEFAULT_MAX_DEPTH);

    final long maxVisitedNodes;
    final int maxResultSize;
    final int maxDepth;
    final long maxBufferedNodes;
    final int maxNesting;
    final int maxRegexSize;

    /**
     * Creates limits with the default nesting and regular expression limits.
     *
     * @throws IllegalArgumentException if a limit is negative
     */
    Limits(long maxVisitedNodes, int maxResultSize, int maxDepth) {
        this(maxVisitedNodes, maxResultSize, maxDepth, DEFAULT_MAX_BUFFERED_NODES, Parser.MAX_NESTING,
                IRegexp.MAX_PROGRAM_SIZE);
    }

    /**
     * Creates limits.
     *
     * @throws IllegalArgumentException if a limit is negative
     */
    Limits(long maxVisitedNodes, int maxResultSize, int maxDepth, long maxBufferedNodes, int maxNesting,
           int maxRegexSize) {
        if (maxVisitedNodes < 0 || maxResultSize < 0 || maxDepth < 0 || maxBufferedNodes < 0 || maxNesting < 0
                || maxRegexSize < 0) {
            throw new IllegalArgumentException("Limits must not be negative: maxVisitedNodes=" + maxVisitedNodes
                    + ", maxResultSize=" + maxResultSize + ", maxDepth=" + maxDepth + ", maxBufferedNodes="
                    + maxBufferedNodes + ", maxNesting=" + maxNesting + ", maxRegexSize=" + maxRegexSize);
        }
        this.maxVisitedNodes = maxVisitedNodes;
        this.maxResultSize = maxResultSize;
        this.maxDepth = maxDepth;
        this.maxBufferedNodes = maxBufferedNodes;
        this.maxNesting = maxNesting;
        this.maxRegexSize = maxRegexSize;
    }

    /**
     * Returns these limits, overridden by the properties of {@link JsonPath} in {@code config}:
     * {@link JsonPath#MAX_VISITED_NODES}, {@link JsonPath#MAX_RESULT_SIZE}, {@link JsonPath#MAX_DEPTH},
     * {@link JsonPath#MAX_BUFFERED_NODES}, {@link JsonPath#MAX_NESTING} and {@link JsonPath#MAX_REGEX_SIZE}.
     * Other properties are ignored.
     *
     * @throws JsonException if one of these properties is not a non-negative integer, given as a
     *     {@link Number} or a decimal {@link String}, in the range of the limit
     */
    Limits with(Map<String, ?> config) {
        return new Limits(
                property(config, JsonPath.MAX_VISITED_NODES, maxVisitedNodes, Long.MAX_VALUE),
                (int) property(config, JsonPath.MAX_RESULT_SIZE, maxResultSize, Integer.MAX_VALUE),
                (int) property(config, JsonPath.MAX_DEPTH, maxDepth, Integer.MAX_VALUE),
                property(config, JsonPath.MAX_BUFFERED_NODES, maxBufferedNodes, Long.MAX_VALUE),
                (int) property(config, JsonPath.MAX_NESTING, maxNesting, Integer.MAX_VALUE),
                (int) property(config, JsonPath.MAX_REGEX_SIZE, maxRegexSize, Integer.MAX_VALUE));
    }

    private static long property(Map<String, ?> config, String name, long defaultValue, long max) {
        Object value = config.get(name);
        if (value == null) {
            return defaultValue;
        }
        long result;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            result = ((Number) value).longValue();
        } else if (value instanceof Number || value instanceof String) {
            try {
                result = new BigDecimal(value.toString().trim()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw invalid(name, value);
            }
        } else {
            throw invalid(name, value);
        }
        if (result < 0) {
            throw invalid(name, value);
        }
        // Larger values than a limit can hold mean "no limit".
        return Math.min(result, max);
    }

    private static JsonException invalid(String name, Object value) {
        return new JsonException(JsonPathMessages.get("path.config.invalid", name, String.valueOf(value)));
    }
}

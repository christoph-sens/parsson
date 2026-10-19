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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.json.JsonException;
import jakarta.json.JsonPath;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

/**
 * Parsson's implementation of {@link JsonPath}: a parsed RFC 9535 query.
 *
 * <p>Instances are immutable and safe for use by multiple concurrent threads.
 */
public final class JsonPathImpl implements JsonPath {

    private final String query;
    private final Ast.Query ast;
    private final JsonProvider provider;
    private final Limits limits;

    private JsonPathImpl(String query, Ast.Query ast, JsonProvider provider, Limits limits) {
        this.query = query;
        this.ast = ast;
        this.provider = provider;
        this.limits = limits;
    }

    /**
     * Parses a query.
     *
     * @param query the query string
     * @param provider creates the values of literals and function results, and the {@link jakarta.json.JsonPointer}s
     *     of selected nodes
     * @param maxVisitedNodes the maximum number of node visits per evaluation: each node a selector produces, each
     *     node a descendant segment walks through and each node a filter tests
     * @param maxResultSize the maximum number of nodes in a result
     * @param maxDepth the maximum depth to which a query walks into the query argument and compares values; the
     *     root has depth 0
     * @return the query
     * @throws NullPointerException if {@code query} is {@code null}
     * @throws IllegalArgumentException if a limit is negative
     * @throws JsonException if {@code query} is not a well-formed and valid JSONPath query; the message gives the
     *     reason, the position and an excerpt of the query
     */
    public static JsonPath create(String query, JsonProvider provider, long maxVisitedNodes, int maxResultSize,
                                  int maxDepth) {
        return create(query, provider, new Limits(maxVisitedNodes, maxResultSize, maxDepth));
    }

    static JsonPath create(String query, JsonProvider provider, Limits limits) {
        Objects.requireNonNull(query, "query");
        return new JsonPathImpl(query, Parser.parse(query, provider), provider, limits);
    }

    /**
     * {@inheritDoc}
     *
     * @throws JsonException if the evaluation exceeds a limit: visited nodes, result size or depth
     */
    @Override
    public List<Node> query(JsonValue target) {
        Objects.requireNonNull(target, "target");
        return Collections.<Node>unmodifiableList(new Evaluator(target, provider, limits).run(ast));
    }

    @Override
    public String toString() {
        return query;
    }
}

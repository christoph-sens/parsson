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

/**
 * Limits for running a query, to bound the work spent on queries and documents from untrusted sources
 * (RFC 9535, section 4.1). A query that exceeds a limit fails with a {@link jakarta.json.JsonException}.
 *
 * <ul>
 *     <li>{@code maxVisitedNodes}: node visits per run; each node a selector produces, each node a descendant
 *     segment walks through and each node a filter tests counts, so the count is roughly proportional to the
 *     time a query takes.</li>
 *     <li>{@code maxResultSize}: nodes in a result.</li>
 *     <li>{@code maxDepth}: how deep a query walks into the query argument and how deep {@code ==} compares
 *     values; the root has depth 0, its children depth 1.</li>
 * </ul>
 */
final class Limits {

    /** Default maximum number of node visits per run, which bounds a query to a few seconds of work. */
    static final long DEFAULT_MAX_VISITED_NODES = 100_000_000L;

    /** Default maximum number of nodes in a result: unlimited. */
    static final int DEFAULT_MAX_RESULT_SIZE = Integer.MAX_VALUE;

    /** Default maximum nesting depth, the default of Parsson's parser. */
    static final int DEFAULT_MAX_DEPTH = 1000;

    static final Limits DEFAULT = new Limits(DEFAULT_MAX_VISITED_NODES, DEFAULT_MAX_RESULT_SIZE, DEFAULT_MAX_DEPTH);

    final long maxVisitedNodes;
    final int maxResultSize;
    final int maxDepth;

    /**
     * Creates limits.
     *
     * @throws IllegalArgumentException if a limit is negative
     */
    Limits(long maxVisitedNodes, int maxResultSize, int maxDepth) {
        if (maxVisitedNodes < 0 || maxResultSize < 0 || maxDepth < 0) {
            throw new IllegalArgumentException("Limits must not be negative: maxVisitedNodes=" + maxVisitedNodes
                    + ", maxResultSize=" + maxResultSize + ", maxDepth=" + maxDepth);
        }
        this.maxVisitedNodes = maxVisitedNodes;
        this.maxResultSize = maxResultSize;
        this.maxDepth = maxDepth;
    }
}

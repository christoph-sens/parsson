/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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

package org.eclipse.parsson.jsonpath.regex;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compiled patterns that one {@code match()} or {@code search()} call of a query read from documents,
 * as in {@code match(@.value, @.pattern)}. Each call has its own cache, so patterns from one query's
 * documents cannot evict those of another query, and a cache holds at most {@link #MAX_ENTRIES} expressions
 * with at most {@link #MAX_INSTRUCTIONS} instructions together. When it is full it is cleared, as patterns
 * usually repeat and a full cache means unusual input.
 *
 * <p>Queries are evaluated concurrently, so reads are lock-free and changes hold the cache's lock.
 */
public final class RegexCache {

    /** Maximum number of expressions per call. */
    static final int MAX_ENTRIES = 64;

    /** Maximum number of automaton instructions of the expressions per call, by default. */
    static final long MAX_INSTRUCTIONS = 2L * IRegexp.MAX_PROGRAM_SIZE;

    private final Map<String, Optional<IRegexp>> entries = new ConcurrentHashMap<>();

    /** Instructions of the expressions in {@link #entries}; guarded by the lock. */
    private long instructions;

    /** Maximum number of automaton instructions per expression. */
    private final int maxProgramSize;

    /** Maximum number of automaton instructions of the cached expressions together. */
    private final long maxInstructions;

    /** Creates an empty cache, one per {@code match()} or {@code search()} call of a query. */
    public RegexCache() {
        this(IRegexp.MAX_PROGRAM_SIZE);
    }

    /**
     * Creates an empty cache for expressions of at most {@code maxProgramSize} instructions; together they
     * may have twice as many.
     *
     * @param maxProgramSize the maximum number of automaton instructions per expression
     */
    public RegexCache(int maxProgramSize) {
        this.maxProgramSize = maxProgramSize;
        this.maxInstructions = 2L * maxProgramSize;
    }

    /**
     * Returns the compiled expression, or empty if {@code regexp} is not a valid I-Regexp.
     *
     * @param regexp the expression, typically read from a document
     * @return the compiled expression, or empty if {@code regexp} is not a valid I-Regexp or exceeds a limit
     */
    public Optional<IRegexp> get(String regexp) {
        Optional<IRegexp> cached = entries.get(regexp);
        if (cached != null) {
            return cached;
        }
        Optional<IRegexp> compiled = IRegexp.compile(regexp, maxProgramSize);
        long weight = compiled.map(r -> (long) r.programSize()).orElse(1L);
        synchronized (this) {
            if (entries.size() >= MAX_ENTRIES || instructions + weight > maxInstructions) {
                for (Optional<IRegexp> evicted : entries.values()) {
                    evicted.ifPresent(IRegexp::retire);
                }
                entries.clear();
                instructions = 0;
            }
            Optional<IRegexp> concurrent = entries.putIfAbsent(regexp, compiled);
            if (concurrent != null) {
                return concurrent;
            }
            instructions += weight;
        }
        return compiled;
    }

    /** Number of cached expressions; for tests. */
    int size() {
        return entries.size();
    }

    /** Instructions of the cached expressions; for tests. */
    synchronized long instructions() {
        return instructions;
    }
}

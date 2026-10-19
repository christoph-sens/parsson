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

import java.util.Optional;

import org.eclipse.parsson.jsonpath.regex.IRegexp;
import org.eclipse.parsson.jsonpath.regex.RegexCache;

/**
 * The compiled patterns of one {@code match()} or {@code search()} call of a query, in I-Regexp (RFC 9485),
 * matched in time linear in the length of the input. A literal pattern ({@code match(@.a, 'a.*')}) is
 * compiled once, when the query is parsed. Patterns read from documents ({@code match(@.value, @.pattern)})
 * are compiled on first use and kept in a bounded cache of the call, so patterns from the documents of one
 * query cannot evict those of another.
 */
final class Patterns {

    private final String literal;
    private final Optional<IRegexp> literalRegexp;
    private final RegexCache cache;
    private final int maxRegexSize;

    /**
     * Creates the patterns of a call.
     *
     * @param literal the pattern if it is a string literal, otherwise {@code null}
     * @param maxRegexSize the maximum number of automaton instructions of a pattern
     */
    Patterns(String literal, int maxRegexSize) {
        this.literal = literal;
        this.maxRegexSize = maxRegexSize;
        this.literalRegexp = literal != null ? IRegexp.compile(literal, maxRegexSize) : Optional.<IRegexp>empty();
        this.cache = literal != null ? null : new RegexCache(maxRegexSize);
    }

    /** Returns the compiled pattern, or empty if {@code pattern} is not a valid I-Regexp. */
    Optional<IRegexp> get(String pattern) {
        if (cache == null) {
            // A literal pattern: the argument is always this literal.
            return pattern.equals(literal) ? literalRegexp : IRegexp.compile(pattern, maxRegexSize);
        }
        return cache.get(pattern);
    }
}

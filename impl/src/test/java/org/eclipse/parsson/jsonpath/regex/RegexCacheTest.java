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

import static org.eclipse.parsson.jsonpath.regex.IRegexpTest.repeat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The per-call cache of patterns that queries read from documents. */
class RegexCacheTest {

    @Test
    void returnsTheSameExpressionForTheSamePattern() {
        RegexCache cache = new RegexCache();

        assertSame(cache.get("a+").get(), cache.get("a+").get());
        assertFalse(cache.get("(").isPresent());
        assertEquals(2, cache.size());
    }

    @Test
    void boundsTheNumberOfExpressions() {
        RegexCache cache = new RegexCache();
        for (int n = 0; n < 1_000; n++) {
            assertTrue(cache.get("a{" + n + "}").get().matches(repeat("a", n)));
            assertTrue(cache.size() <= RegexCache.MAX_ENTRIES);
        }
    }

    @Test
    void boundsTheInstructionsOfTheExpressions() {
        RegexCache cache = new RegexCache();
        for (int n = 0; n < 60; n++) {
            assertTrue(cache.get("a{5000}x{0," + n + "}").isPresent());
            assertTrue(cache.instructions() <= RegexCache.MAX_INSTRUCTIONS);
        }
        assertTrue(cache.get("a{5000}").get().matches(repeat("a", 5000)));
    }

    @Test
    void returnsTheMemoryOfEvictedAutomata() {
        // A budget of its own: the shared one may be full from other tests in the same JVM.
        DfaBudget budget = new DfaBudget(IRegexp.MAX_DFA_BYTES);
        RegexCache cache = new RegexCache(IRegexp.MAX_PROGRAM_SIZE, budget);
        IRegexp evicted = cache.get("(a|b)*a(a|b){8}").get();
        evicted.matches(repeat("ab", 50));
        long automaton = evicted.automatonBytes(false);
        assertTrue(automaton > 0);
        assertEquals(automaton, budget.bytes());

        for (int n = 0; n < RegexCache.MAX_ENTRIES; n++) {
            cache.get("x{" + n + "}");
        }

        assertEquals(0, evicted.automatonBytes(false));
        assertEquals(0, budget.generation(), "evicting does not discard the other automata");
        assertEquals(0, budget.bytes());
    }

    @Test
    void discardsAllAutomataWhenTheBudgetIsFull() {
        // Smaller than the bound of one automaton, so the shared budget is what runs full.
        long maxBytes = 1L << 16;
        DfaBudget budget = new DfaBudget(maxBytes);
        RegexCache cache = new RegexCache(IRegexp.MAX_PROGRAM_SIZE, budget);
        IRegexp regexp = cache.get("(a|b)*a(a|b){12}").get();
        Random random = new Random(7);
        for (int i = 0; i < 2_000 && budget.generation() == 0; i++) {
            StringBuilder input = new StringBuilder();
            for (int j = 0; j < 60; j++) {
                input.append(random.nextBoolean() ? 'a' : 'b');
            }
            regexp.matches(input.toString());
            assertTrue(budget.bytes() <= maxBytes);
        }

        assertTrue(budget.generation() > 0, "a full budget discards all automata");
        assertTrue(regexp.matches(repeat("ab", 10) + "a" + repeat("b", 12)), "automata are rebuilt on demand");
    }
}

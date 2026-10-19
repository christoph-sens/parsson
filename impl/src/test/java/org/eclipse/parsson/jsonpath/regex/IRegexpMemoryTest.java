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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Memory bounds of the regex caches. Expressions can come from documents, so a query over untrusted data
 * must not be able to make the caches retain unbounded memory.
 */
class IRegexpMemoryTest {

    /** Needs 2^13 deterministic states; the suffix makes each pattern a distinct cache entry. */
    private static String explosive(int n) {
        return "(a|b)*a(a|b){12}c{0," + n + "}";
    }

    private static Pattern java(int n) {
        return Pattern.compile("(?:a|b)*a(?:a|b){12}c{0," + n + "}");
    }

    private static String randomAb(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextBoolean() ? 'a' : 'b');
        }
        return sb.toString();
    }

    private static void assertAtMost(long limit, long actual, String what) {
        assertTrue(actual <= limit, what + ": " + actual + " > " + limit);
    }

    @Test
    void boundsTheMemoryOfManyDocumentSourcedPatterns() {
        int generationBefore = IRegexp.dfaGeneration();
        Random random = new Random(41);
        // Held like the expressions of long-lived queries, which discarding has to reach as well.
        List<IRegexp> held = new ArrayList<>();
        for (int n = 0; n < 256; n++) {
            IRegexp regexp = IRegexp.compile(explosive(n)).get();
            held.add(regexp);
            Pattern java = java(n);
            for (int i = 0; i < 400; i++) {
                String input = randomAb(random, 60);
                boolean matches = regexp.matches(input);
                boolean found = regexp.find(input);
                if (i % 20 == 0) {
                    assertEquals(java.matcher(input).matches(), matches, "match " + n + " on " + input);
                    assertEquals(java.matcher(input).find(), found, "search " + n + " on " + input);
                }
            }
            assertAtMost(IRegexp.MAX_DFA_BYTES, IRegexp.cachedDfaBytes(), "cached bytes");
        }
        // The scenario retained 818 MB in Treetrail before the bound; now it has to run into the global bound.
        assertTrue(IRegexp.dfaGeneration() > generationBefore);
        long retained = 0;
        for (IRegexp regexp : held) {
            retained += regexp.automatonBytes(false) + regexp.automatonBytes(true);
        }
        assertAtMost(IRegexp.MAX_DFA_BYTES, retained, "retained bytes");
    }

    @Test
    void boundsOneAutomaton() {
        IRegexp regexp = IRegexp.compile("(a|b)*a(a|b){12}").get();
        Pattern java = Pattern.compile("(?:a|b)*a(?:a|b){12}");
        Random random = new Random(1);
        for (int i = 0; i < 5_000; i++) {
            String input = randomAb(random, 80);
            assertEquals(java.matcher(input).matches(), regexp.matches(input));
            assertAtMost(IRegexp.MAX_DFA_BYTES_PER_AUTOMATON, regexp.automatonBytes(false), "automaton bytes");
        }
    }

    @Test
    void continuesWithTheSimulationWhereTheAutomatonIsFull() {
        // 2^13 deterministic states, more than an automaton may hold: matching continues where it stops,
        // also after a code point above U+FFFF and in search mode.
        IRegexp regexp = IRegexp.compile("(a|😀)*a(a|b|😀){12}").get();
        Pattern java = Pattern.compile("(?:a|😀)*a(?:a|b|😀){12}");
        String[] symbols = {"a", "b", "😀"};
        Random random = new Random(12);
        for (int i = 0; i < 3_000; i++) {
            StringBuilder input = new StringBuilder();
            int length = 10 + random.nextInt(40);
            for (int j = 0; j < length; j++) {
                input.append(symbols[random.nextInt(3)]);
            }
            String s = input.toString();
            assertEquals(java.matcher(s).matches(), regexp.matches(s), "match " + s);
            assertEquals(java.matcher(s).find(), regexp.find(s), "search " + s);
        }
    }

    @Test
    void boundsTransitionsOnNonAsciiCodePoints() {
        // Every distinct code point outside ASCII gets its own cached transition.
        IRegexp regexp = IRegexp.compile("\\p{Lo}*x").get();
        StringBuilder input = new StringBuilder();
        for (int cp = 0x4E00; cp < 0x4E00 + 20_000; cp++) {
            input.appendCodePoint(cp);
        }
        assertFalse(regexp.find(input.toString()));
        assertTrue(regexp.matches(input + "x"));
        assertAtMost(IRegexp.MAX_DFA_BYTES_PER_AUTOMATON, regexp.automatonBytes(true), "search automaton bytes");
        assertAtMost(IRegexp.MAX_DFA_BYTES_PER_AUTOMATON, regexp.automatonBytes(false), "match automaton bytes");
    }

    @Test
    void staysCorrectWhileAutomataAreDiscardedConcurrently() {
        List<String> inputs = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            inputs.add(randomAb(random, 40));
        }
        AtomicInteger mismatches = new AtomicInteger();
        IntStream.range(0, 128).parallel().forEach(n -> {
            IRegexp regexp = IRegexp.compile(explosive(1_000 + n)).get();
            Pattern java = java(1_000 + n);
            for (String input : inputs) {
                if (regexp.matches(input) != java.matcher(input).matches()
                        || regexp.find(input) != java.matcher(input).find()) {
                    mismatches.incrementAndGet();
                }
            }
        });
        assertEquals(0, mismatches.get());
        assertAtMost(IRegexp.MAX_DFA_BYTES, IRegexp.cachedDfaBytes(), "cached bytes");
    }
}

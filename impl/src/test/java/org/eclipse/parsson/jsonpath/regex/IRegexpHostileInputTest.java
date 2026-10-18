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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regular expression denial of service (ReDoS): patterns and inputs that make a backtracking matcher such as
 * java.util.regex take exponential or high polynomial time. Patterns may come from documents
 * ({@code match(@.value, @.pattern)}), so a JSONPath implementation has to match them in time linear in the
 * input. Each case first checks that java.util.regex does not finish within {@link #JAVA_BUDGET} on a short
 * input, so the case is hostile on the running JDK (JDK 9 and later defuse some textbook cases such as
 * {@code (a|a)*b}), and then that {@link IRegexp} handles an input of {@link #LONG_INPUT} characters, both
 * for {@code match()} and {@code search()}, within {@link #IREGEXP_BUDGET}.
 */
class IRegexpHostileInputTest {

    /** Time java.util.regex gets on the short input; it needs many orders of magnitude more. */
    private static final Duration JAVA_BUDGET = Duration.ofMillis(250);

    /** Length of the repeated part of the input for {@link IRegexp}. */
    private static final int LONG_INPUT = 100_000;

    /** Time {@link IRegexp} gets for {@code match()} and {@code search()} on the long input. */
    private static final Duration IREGEXP_BUDGET = Duration.ofSeconds(10);

    /**
     * The pattern (the same in I-Regexp and java.util.regex once groups are made non-capturing), the
     * repeated unit of the input, the length of the short input for java.util.regex, the suffix that makes
     * the match fail late, and whether the long input matches and contains a match.
     */
    @ParameterizedTest(name = "{0} on \"{1}\"*n + \"{3}\"")
    @CsvSource(
            delimiter = ';',
            value = {
                // Exponential: nested or counted repetitions with overlapping alternatives.
                "(a|aa){1,60}b               ; a ; 40   ; !  ; false ; false",
                "(a|a){0,60}b                ; a ; 40   ; !  ; false ; false",
                "(a*){1,40}b                 ; a ; 40   ; !  ; false ; false",
                "(a+){2,30}b                 ; a ; 40   ; !  ; false ; false",
                "((a|a)*)*b                  ; a ; 40   ; !  ; false ; false",
                "(\\p{Ll}|[a-z]){1,60}!      ; a ; 40   ; x  ; false ; false",
                "([a-z]*[a-z]*){1,10}!       ; a ; 40   ; x  ; false ; false",
                // Polynomial of high degree: C(n, 20) steps for java.util.regex.
                "(.*a){20}                   ; a ; 40   ; !  ; false ; true",
                // Polynomial: n^3 and n^4 steps for java.util.regex.
                "\\p{L}*\\p{L}*\\p{L}*x      ; a ; 3000 ; !  ; false ; false",
                ".*.*.*.*!                   ; a ; 3000 ; x  ; false ; false",
            })
    void matchesInLinearTimeWhereJavaRegexBacktracks(
            String pattern, String unit, int shortLength, String suffix, boolean matches, boolean found) {
        Pattern java = Pattern.compile(pattern.replace("(", "(?:"));
        String shortInput = repeat(unit, shortLength) + suffix;
        assertThrows(BudgetExceeded.class,
                () -> java.matcher(new Budgeted(shortInput, System.nanoTime() + JAVA_BUDGET.toNanos())).matches(),
                "java.util.regex finished in time, so the case is not hostile on this JDK");

        IRegexp regexp = IRegexp.compile(pattern).get();
        String longInput = repeat(unit, LONG_INPUT) + suffix;
        assertTimeoutPreemptively(IREGEXP_BUDGET, () -> {
            assertEquals(matches, regexp.matches(longInput), "match()");
            assertEquals(found, regexp.find(longInput), "search()");
        });
    }

    /** Thrown by {@link Budgeted} once the time is up, so that java.util.regex stops backtracking. */
    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** A string that throws {@link BudgetExceeded} on every access after a deadline. */
    private static final class Budgeted implements CharSequence {
        private final String string;
        private final long deadline;

        Budgeted(String string, long deadline) {
            this.string = string;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return string.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) {
                throw new BudgetExceeded();
            }
            return string.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Budgeted(string.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return string;
        }
    }
}

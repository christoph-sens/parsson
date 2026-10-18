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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IRegexpTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "abc",
                "a|b",
                "(ab)*",
                "a{2}",
                "a{2,}",
                "a{2,3}",
                "[a-z]",
                "[^a-z]",
                "[-a]",
                "[a-]",
                "\\p{Lu}",
                "\\P{L}",
                "[\\p{Nd}x]",
                "\\.",
                "\\n",
                "[\\]]",
                ".",
                "^a$",
                "()",
                "a||b"
            })
    void acceptsValidExpressions(String regexp) {
        assertTrue(IRegexp.compile(regexp).isPresent(), regexp);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "(",
                ")",
                "a**",
                "a{3,2}",
                "a{",
                "[]",
                "[^]",
                "[b-a]",
                "[a-c-e]",
                "\\d",
                "\\w",
                "\\s",
                "\\p{IsBasicLatin}",
                "\\p{Cs}",
                "(?:a)",
                "a+?",
                "\\b",
                "[a-\\p{L}]",
                "{1}",
                "a{99999999999}"
            })
    void rejectsExpressionsOutsideIRegexp(String regexp) {
        assertFalse(IRegexp.compile(regexp).isPresent(), regexp);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "a.c    ; abc   ; true",
                "a.c    ; a\\nc ; false",
                "[a-c]+ ; abcab ; true",
                "[^a-c] ; d     ; true",
                "[^a-c] ; b     ; false",
                "\\p{Lu}\\p{Ll}* ; Hello ; true",
                "\\P{L}+ ; 123  ; true",
                "[\\P{L}\\P{N}] ; a ; true",
                "a{2,3} ; aaaa  ; false",
                "a{2,}  ; aaaa  ; true",
                "(ab|cd){2} ; abcd ; true",
                "x*     ; ''    ; true",
                // Regular-language semantics: the first pass matches the empty string at '^'.
                // java.util.regex answers false here; RE2 and Rust's regex answer true.
                "(^|a){2} ; a  ; true",
            })
    void matchesWholeInput(String regexp, String input, boolean expected) {
        String subject = input.equals("''") ? "" : input.replace("\\n", "\n");
        assertEquals(expected, IRegexp.compile(regexp).get().matches(subject));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " ", "\u0085", "😀"})
    void dotMatchesEverythingButLineFeedAndCarriageReturn(String subject) {
        assertTrue(IRegexp.compile(".").get().matches(subject));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r"})
    void dotDoesNotMatchLineFeedOrCarriageReturn(String subject) {
        assertFalse(IRegexp.compile(".").get().matches(subject));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "+", "?", "(", ")", "|", "{", "}", "^", ".", "-", "[", "]", "\\"})
    void treatsMetacharactersLiterallyWhenEscaped(String c) {
        assertTrue(IRegexp.compile("\\" + c).get().matches(c));
    }

    @Test
    void searchFindsSubstrings() {
        IRegexp regexp = IRegexp.compile("b+").get();

        assertTrue(regexp.find("abbbc"));
        assertFalse(regexp.find("ac"));
        assertFalse(IRegexp.compile("^b").get().find("ab"));
        assertTrue(IRegexp.compile("b$").get().find("ab"));
    }

    @Test
    void matchesSupplementaryCharactersAsOneCodePoint() {
        assertTrue(IRegexp.compile("a.b").get().matches("a😀b"));
        assertTrue(IRegexp.compile("[😀-🙏]").get().matches("😃"));
    }

    @Test
    void evilPatternsRunInLinearTime() {
        String input = repeat("a", 100_000) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertFalse(IRegexp.compile("(a*)*b").get().matches(input));
            assertFalse(IRegexp.compile("(a|a)*b").get().matches(input));
            assertFalse(IRegexp.compile("(a|aa)+b").get().find(input));
            assertFalse(IRegexp.compile("(.*a){20}").get().matches(input));
        });
    }

    @Test
    void rejectsExpressionsThatExpandTooMuch() {
        assertTrue(IRegexp.compile("a{1000}").isPresent());
        assertFalse(IRegexp.compile("(a{1000}){1000}").isPresent());
    }

    /** Found by the CI fuzzer: repeating an empty group did not grow the program, so nothing stopped it. */
    @Test
    void compilesHugeRepetitionsOfNothingQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertTrue(IRegexp.compile("(a|b)*a(){00000001390468697}").get().matches("ba"));
            assertTrue(IRegexp.compile("((){2000000000}){2000000000}x").get().matches("x"));
            assertTrue(IRegexp.compile("(a{0}){2000000000}").get().matches(""));
            assertTrue(IRegexp.compile("(){0,2000000000}y").get().find("xy"));
        });
    }

    @Test
    void rejectsDeeplyNestedGroups() {
        assertFalse(IRegexp.compile(repeat("(", 10_000) + "a" + repeat(")", 10_000)).isPresent());
    }

    @Test
    void fallsBackToTheSimulationWhenTheDfaGrowsTooLarge() {
        // The deterministic automaton for "the 13th character from the end is an a" needs 2^13 states.
        IRegexp regexp = IRegexp.compile("(a|b)*a(a|b){12}").get();
        Pattern java = Pattern.compile("(?:a|b)*a(?:a|b){12}");
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            StringBuilder input = new StringBuilder();
            for (int j = 0; j < 40; j++) {
                input.append(random.nextBoolean() ? 'a' : 'b');
            }
            assertEquals(java.matcher(input).matches(), regexp.matches(input.toString()), input.toString());
        }
    }

    @Test
    void canBeSharedBetweenThreads() {
        IRegexp regexp = IRegexp.compile("[a-c]+x?(d|e)*\\p{Lu}").get();
        Pattern java = Pattern.compile("[a-c]+x?(?:d|e)*\\p{Lu}");
        List<String> inputs = new ArrayList<>();
        Random random = new Random(2);
        for (int i = 0; i < 5_000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = random.nextInt(12); j > 0; j--) {
                sb.append("abcxdeQÄ😀".charAt(random.nextInt(9)));
            }
            inputs.add(sb.toString());
        }
        assertEquals(
                inputs.stream().filter(s -> java.matcher(s).matches()).count(),
                inputs.parallelStream().filter(regexp::matches).count());
    }

    /** Compares the automaton with java.util.regex on random expressions and inputs. */
    @Test
    void agreesWithJavaRegexOnRandomExpressions() {
        Random random = new Random(9535);
        for (int i = 0; i < 3_000; i++) {
            StringBuilder iregexp = new StringBuilder();
            StringBuilder java = new StringBuilder();
            randomRegexp(random, 3, iregexp, java);
            IRegexp ours = IRegexp.compile(iregexp.toString())
                    .orElseThrow(() -> new AssertionError("rejected " + iregexp));
            Pattern theirs = Pattern.compile(java.toString());
            for (int j = 0; j < 20; j++) {
                String input = randomInput(random);
                assertEquals(theirs.matcher(input).matches(), ours.matches(input),
                        "match " + iregexp + " on " + input);
                assertEquals(theirs.matcher(input).find(), ours.find(input),
                        "search " + iregexp + " on " + input);
            }
        }
    }

    private static void randomRegexp(Random random, int depth, StringBuilder iregexp, StringBuilder java) {
        int pieces = 1 + random.nextInt(3);
        for (int p = 0; p < pieces; p++) {
            // Anchors only at the top level: java.util.regex mishandles empty loop passes over anchors.
            int kind = random.nextInt(depth > 0 ? 9 : 5);
            if (kind == 5 && depth < 3) {
                kind = 0;
            }
            switch (kind) {
                case 0:
                case 1:
                    String c = random.nextBoolean() ? "a" : "b";
                    iregexp.append(c);
                    java.append(c);
                    break;
                case 2:
                    iregexp.append('.');
                    java.append("[^\\n\\r]");
                    break;
                case 3:
                    iregexp.append("[ab]");
                    java.append("[ab]");
                    break;
                case 4:
                    iregexp.append("[^a]");
                    java.append("[^a]");
                    break;
                case 5:
                    boolean begin = random.nextBoolean();
                    iregexp.append(begin ? "^" : "$");
                    java.append(begin ? "\\A" : "\\z");
                    continue;
                default:
                    iregexp.append('(');
                    java.append("(?:");
                    randomRegexp(random, depth - 1, iregexp, java);
                    if (random.nextInt(3) == 0) {
                        iregexp.append('|');
                        java.append('|');
                        randomRegexp(random, depth - 1, iregexp, java);
                    }
                    iregexp.append(')');
                    java.append(')');
                    break;
            }
            String quantifier = new String[] {"", "", "*", "+", "?", "{2}", "{1,3}", "{0,}"}[random.nextInt(8)];
            iregexp.append(quantifier);
            java.append(quantifier);
        }
    }

    private static String randomInput(Random random) {
        StringBuilder sb = new StringBuilder();
        int length = random.nextInt(9);
        for (int i = 0; i < length; i++) {
            sb.append("aabbc\n".charAt(random.nextInt(6)));
        }
        return sb.toString();
    }

    static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}

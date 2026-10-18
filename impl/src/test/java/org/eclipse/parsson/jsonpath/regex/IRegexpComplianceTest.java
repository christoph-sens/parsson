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

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The regular expression cases of the JSONPath Compliance Test Suite, run directly against {@link IRegexp}.
 *
 * <p>Derived from {@code cts.json} of https://github.com/jsonpath-standard/jsonpath-compliance-test-suite at
 * commit 20ebdcce5008c8a777ee9c80ad853ecf606950b5 (BSD-2-Clause): every test whose selector calls
 * {@code match()} or {@code search()} with a pattern (a string literal, or one read from the document), and
 * for each string the filter applies the function to, whether the test selects it (inverted for
 * {@code !match()} and {@code !search()}). Duplicates are left out, and so are the tests that never reach the
 * regex engine: invalid selectors and non-string arguments. The full selectors run in the TCK.
 */
class IRegexpComplianceTest {

    private static final Object[][] CASES = {
        {"functions, match, found match", "match", "a.*", "ab", true},
        {"functions, match, regex from the document", "match", "b.?b", "abc", false},
        {"functions, match, regex from the document", "match", "b.?b", "bcd", false},
        {"functions, match, regex from the document", "match", "b.?b", "bab", true},
        {"functions, match, regex from the document", "match", "b.?b", "bba", false},
        {"functions, match, regex from the document", "match", "b.?b", "bbab", false},
        {"functions, match, regex from the document", "match", "b.?b", "b", false},
        {"functions, match, not a match", "match", "a.*", "bc", false},
        {"functions, match, filter, match function, unicode char class, uppercase", "match", "\\p{Lu}", "\u0436", false},
        {"functions, match, filter, match function, unicode char class, uppercase", "match", "\\p{Lu}", "\u0416", true},
        {"functions, match, filter, match function, unicode char class, uppercase", "match", "\\p{Lu}", "1", false},
        {"functions, match, filter, match function, unicode char class, uppercase", "match", "\\p{Lu}", "\u0436\u0416", false},
        {"functions, match, filter, match function, unicode char class negated, uppercase", "match", "\\P{Lu}", "\u0436", true},
        {"functions, match, filter, match function, unicode char class negated, uppercase", "match", "\\P{Lu}", "\u0416", false},
        {"functions, match, filter, match function, unicode char class negated, uppercase", "match", "\\P{Lu}", "1", true},
        {"functions, match, filter, match function, unicode, surrogate pair", "match", "a.b", "a\uD800\uDD01b", true},
        {"functions, match, filter, match function, unicode, surrogate pair", "match", "a.b", "ab", false},
        {"functions, match, filter, match function, unicode, surrogate pair", "match", "a.b", "1", false},
        {"functions, match, dot matcher on \\u2028", "match", ".", "\u2028", true},
        {"functions, match, dot matcher on \\u2028", "match", ".", "\r", false},
        {"functions, match, dot matcher on \\u2028", "match", ".", "\n", false},
        {"functions, match, dot matcher on \\u2029", "match", ".", "\u2029", true},
        {"functions, match, arg is a function expression", "match", "a.*", "ba", false},
        {"functions, match, dot in character class", "match", "a[.b]c", "abc", true},
        {"functions, match, dot in character class", "match", "a[.b]c", "a.c", true},
        {"functions, match, dot in character class", "match", "a[.b]c", "axc", false},
        {"functions, match, escaped dot", "match", "a\\.c", "abc", false},
        {"functions, match, escaped dot", "match", "a\\.c", "a.c", true},
        {"functions, match, escaped dot", "match", "a\\.c", "axc", false},
        {"functions, match, escaped backslash before dot", "match", "a\\\\.c", "abc", false},
        {"functions, match, escaped backslash before dot", "match", "a\\\\.c", "a.c", false},
        {"functions, match, escaped backslash before dot", "match", "a\\\\.c", "axc", false},
        {"functions, match, escaped backslash before dot", "match", "a\\\\.c", "a\\\u2028c", true},
        {"functions, match, escaped left square bracket", "match", "a\\[.c", "abc", false},
        {"functions, match, escaped left square bracket", "match", "a\\[.c", "a.c", false},
        {"functions, match, escaped left square bracket", "match", "a\\[.c", "a[\u2028c", true},
        {"functions, match, escaped right square bracket", "match", "a[\\].]c", "abc", false},
        {"functions, match, escaped right square bracket", "match", "a[\\].]c", "a.c", true},
        {"functions, match, escaped right square bracket", "match", "a[\\].]c", "a\u2028c", false},
        {"functions, match, escaped right square bracket", "match", "a[\\].]c", "a]c", true},
        {"functions, match, explicit caret", "match", "^ab.*", "abc", true},
        {"functions, match, explicit caret", "match", "^ab.*", "axc", false},
        {"functions, match, explicit caret", "match", "^ab.*", "ab", true},
        {"functions, match, explicit caret", "match", "^ab.*", "xab", false},
        {"functions, match, explicit dollar", "match", ".*bc$", "abc", true},
        {"functions, match, explicit dollar", "match", ".*bc$", "axc", false},
        {"functions, match, explicit dollar", "match", ".*bc$", "ab", false},
        {"functions, match, explicit dollar", "match", ".*bc$", "abcx", false},
        {"functions, search, at the end", "search", "a.*", "the end is ab", true},
        {"functions, search, at the start", "search", "a.*", "ab is at the start", true},
        {"functions, search, in the middle", "search", "a.*", "contains two matches", true},
        {"functions, search, regex from the document", "search", "b.?b", "abc", false},
        {"functions, search, regex from the document", "search", "b.?b", "bcd", false},
        {"functions, search, regex from the document", "search", "b.?b", "bab", true},
        {"functions, search, regex from the document", "search", "b.?b", "bba", true},
        {"functions, search, regex from the document", "search", "b.?b", "bbab", true},
        {"functions, search, regex from the document", "search", "b.?b", "b", false},
        {"functions, search, not a match", "search", "a.*", "bc", false},
        {"functions, search, filter, search function, unicode char class, uppercase", "search", "\\p{Lu}", "\u0436", false},
        {"functions, search, filter, search function, unicode char class, uppercase", "search", "\\p{Lu}", "\u0416", true},
        {"functions, search, filter, search function, unicode char class, uppercase", "search", "\\p{Lu}", "1", false},
        {"functions, search, filter, search function, unicode char class, uppercase", "search", "\\p{Lu}", "\u0436\u0416", true},
        {"functions, search, filter, search function, unicode char class negated, uppercase", "search", "\\P{Lu}", "\u0436", true},
        {"functions, search, filter, search function, unicode char class negated, uppercase", "search", "\\P{Lu}", "\u0416", false},
        {"functions, search, filter, search function, unicode char class negated, uppercase", "search", "\\P{Lu}", "1", true},
        {"functions, search, filter, search function, unicode, surrogate pair", "search", "a.b", "a\uD800\uDD01bc", true},
        {"functions, search, filter, search function, unicode, surrogate pair", "search", "a.b", "abc", false},
        {"functions, search, filter, search function, unicode, surrogate pair", "search", "a.b", "1", false},
        {"functions, search, dot matcher on \\u2028", "search", ".", "\u2028", true},
        {"functions, search, dot matcher on \\u2028", "search", ".", "\r\u2028\n", true},
        {"functions, search, dot matcher on \\u2028", "search", ".", "\r", false},
        {"functions, search, dot matcher on \\u2028", "search", ".", "\n", false},
        {"functions, search, dot matcher on \\u2029", "search", ".", "\u2029", true},
        {"functions, search, dot matcher on \\u2029", "search", ".", "\r\u2029\n", true},
        {"functions, search, dot in character class", "search", "a[.b]c", "x abc y", true},
        {"functions, search, dot in character class", "search", "a[.b]c", "x a.c y", true},
        {"functions, search, dot in character class", "search", "a[.b]c", "x axc y", false},
        {"functions, search, escaped dot", "search", "a\\.c", "x abc y", false},
        {"functions, search, escaped dot", "search", "a\\.c", "x a.c y", true},
        {"functions, search, escaped dot", "search", "a\\.c", "x axc y", false},
        {"functions, search, escaped backslash before dot", "search", "a\\\\.c", "x abc y", false},
        {"functions, search, escaped backslash before dot", "search", "a\\\\.c", "x a.c y", false},
        {"functions, search, escaped backslash before dot", "search", "a\\\\.c", "x axc y", false},
        {"functions, search, escaped backslash before dot", "search", "a\\\\.c", "x a\\\u2028c y", true},
        {"functions, search, escaped left square bracket", "search", "a\\[.c", "x abc y", false},
        {"functions, search, escaped left square bracket", "search", "a\\[.c", "x a.c y", false},
        {"functions, search, escaped left square bracket", "search", "a\\[.c", "x a[\u2028c y", true},
        {"functions, search, escaped right square bracket", "search", "a[\\].]c", "x abc y", false},
        {"functions, search, escaped right square bracket", "search", "a[\\].]c", "x a.c y", true},
        {"functions, search, escaped right square bracket", "search", "a[\\].]c", "x a\u2028c y", false},
        {"functions, search, escaped right square bracket", "search", "a[\\].]c", "x a]c y", true},
        {"whitespace, functions, space between arg and comma", "search", "[a-z]+", "foo", true},
        {"whitespace, functions, space between arg and comma", "search", "[a-z]+", "123", false},
    };

    static Stream<Arguments> cases() {
        return Arrays.stream(CASES).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}: {1}(\"{3}\", \"{2}\")")
    @MethodSource("cases")
    void agreesWithTheComplianceTestSuite(String test, String function, String regexp, String input,
            boolean expected) {
        IRegexp compiled = IRegexp.compile(regexp).get();
        assertEquals(expected, function.equals("match") ? compiled.matches(input) : compiled.find(input));
    }
}

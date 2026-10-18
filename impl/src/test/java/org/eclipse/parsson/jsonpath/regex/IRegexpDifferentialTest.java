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
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Differential test of I-Regexp against java.util.regex (RFC 9485, section 5): random expressions from
 * the part of I-Regexp that maps directly to Java, random inputs, and both must agree on {@code match()}
 * and {@code search()}.
 *
 * <p>Anchors are only generated at the start and end of an expression: java.util.regex mishandles empty
 * loop iterations over anchors, see {@code IRegexpTest} for {@code (^|a){2}}.
 */
class IRegexpDifferentialTest {

    private static final int EXPRESSIONS = 20_000;
    private static final int INPUTS_PER_EXPRESSION = 30;

    /** Code points the generators draw from: ASCII, Latin-1, a combining mark, a space, an emoji. */
    private static final int[] ALPHABET = {
        'a', 'b', 'c', 'x', 'A', 'Z', '0', '7', '-', '.', ' ', '_', '\n', '\r', '\t', 0x00E9, 0x00DC, 0x00DF, 0x0301,
        0x00A0, 0x2028, 0x1F600, 0x4E00, '$', '^', '['
    };

    private static final List<String> CATEGORIES =
            Arrays.asList("L", "Lu", "Ll", "N", "Nd", "P", "S", "Z", "Zs", "M", "C");

    @Test
    void agreesWithJavaRegex() {
        Random random = new Random(9485);
        for (int i = 0; i < EXPRESSIONS; i++) {
            StringBuilder iregexp = new StringBuilder();
            StringBuilder java = new StringBuilder();
            if (random.nextInt(8) == 0) {
                iregexp.append('^');
                java.append("\\A");
            }
            alternation(random, 3, iregexp, java);
            if (random.nextInt(8) == 0) {
                iregexp.append('$');
                java.append("\\z");
            }
            IRegexp ours = IRegexp.compile(iregexp.toString())
                    .orElseThrow(() -> new AssertionError("rejected valid I-Regexp " + iregexp));
            Pattern theirs = Pattern.compile(java.toString());
            for (int j = 0; j < INPUTS_PER_EXPRESSION; j++) {
                String input = input(random);
                assertEquals(theirs.matcher(input).matches(), ours.matches(input),
                        "match(" + iregexp + ") on " + escape(input) + ", Java " + java);
                assertEquals(theirs.matcher(input).find(), ours.find(input),
                        "search(" + iregexp + ") on " + escape(input) + ", Java " + java);
            }
        }
    }

    private static void alternation(Random random, int depth, StringBuilder iregexp, StringBuilder java) {
        branch(random, depth, iregexp, java);
        while (random.nextInt(4) == 0) {
            iregexp.append('|');
            java.append('|');
            branch(random, depth, iregexp, java);
        }
    }

    private static void branch(Random random, int depth, StringBuilder iregexp, StringBuilder java) {
        int pieces = random.nextInt(4);
        for (int p = 0; p < pieces; p++) {
            atom(random, depth, iregexp, java);
            quantifier(random, iregexp, java);
        }
    }

    private static void atom(Random random, int depth, StringBuilder iregexp, StringBuilder java) {
        switch (random.nextInt(depth > 0 ? 7 : 6)) {
            case 0:
            case 1:
                int c = ALPHABET[random.nextInt(ALPHABET.length)];
                if (c == '$') {
                    // '$' cannot be escaped in I-Regexp, and a bare '$' is an anchor here: use a class.
                    iregexp.append("[$]");
                } else if (isMeta(c)) {
                    iregexp.append('\\').appendCodePoint(c);
                } else if (c == '\n' || c == '\r' || c == '\t') {
                    iregexp.append(c == '\n' ? "\\n" : c == '\r' ? "\\r" : "\\t");
                } else {
                    iregexp.appendCodePoint(c);
                }
                java.append(literal(c));
                break;
            case 2:
                iregexp.append('.');
                java.append("[^\\n\\r]");
                break;
            case 3:
                category(random, iregexp, java);
                break;
            case 4:
            case 5:
                charClass(random, iregexp, java);
                break;
            default:
                iregexp.append('(');
                java.append("(?:");
                alternation(random, depth - 1, iregexp, java);
                iregexp.append(')');
                java.append(')');
                break;
        }
    }

    private static void category(Random random, StringBuilder iregexp, StringBuilder java) {
        String name = CATEGORIES.get(random.nextInt(CATEGORIES.size()));
        String escape = (random.nextBoolean() ? "\\p{" : "\\P{") + name + "}";
        iregexp.append(escape);
        java.append(escape);
    }

    private static void charClass(Random random, StringBuilder iregexp, StringBuilder java) {
        boolean negated = random.nextBoolean();
        iregexp.append(negated ? "[^" : "[");
        java.append(negated ? "[^" : "[");
        int items = 1 + random.nextInt(3);
        for (int i = 0; i < items; i++) {
            if (random.nextInt(4) == 0) {
                String escape = (random.nextBoolean() ? "\\p{" : "\\P{")
                        + CATEGORIES.get(random.nextInt(CATEGORIES.size())) + "}";
                iregexp.append(escape);
                java.append(escape);
                continue;
            }
            int lo = ALPHABET[random.nextInt(ALPHABET.length)];
            classChar(lo, iregexp);
            java.append(literal(lo));
            if (random.nextInt(3) == 0) {
                int hi = ALPHABET[random.nextInt(ALPHABET.length)];
                if (hi >= lo) {
                    iregexp.append('-');
                    classChar(hi, iregexp);
                    java.append('-').append(literal(hi));
                }
            }
        }
        iregexp.append(']');
        java.append(']');
    }

    private static void classChar(int c, StringBuilder iregexp) {
        if (c == '-' || c == '[' || c == ']' || c == '\\' || c == '^') {
            iregexp.append('\\').appendCodePoint(c);
        } else if (c == '\n' || c == '\r' || c == '\t') {
            iregexp.append(c == '\n' ? "\\n" : c == '\r' ? "\\r" : "\\t");
        } else {
            iregexp.appendCodePoint(c);
        }
    }

    private static void quantifier(Random random, StringBuilder iregexp, StringBuilder java) {
        String q;
        switch (random.nextInt(9)) {
            case 0:
                q = "*";
                break;
            case 1:
                q = "+";
                break;
            case 2:
                q = "?";
                break;
            case 3:
                q = "{" + random.nextInt(3) + "}";
                break;
            case 4:
                q = "{" + random.nextInt(3) + ",}";
                break;
            case 5:
                int min = random.nextInt(3);
                q = "{" + min + "," + (min + random.nextInt(3)) + "}";
                break;
            default:
                q = "";
                break;
        }
        iregexp.append(q);
        java.append(q);
    }

    private static boolean isMeta(int c) {
        return "()*+-.?[\\]^{|}".indexOf(c) >= 0;
    }

    private static String literal(int c) {
        return "\\x{" + Integer.toHexString(c) + "}";
    }

    private static String input(Random random) {
        StringBuilder sb = new StringBuilder();
        int length = random.nextInt(9);
        for (int i = 0; i < length; i++) {
            sb.appendCodePoint(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        s.codePoints().forEach(c -> sb.append(
                c < 0x20 || c > 0x7e ? String.format("\\u{%x}", c) : String.valueOf((char) c)));
        return sb.append('"').toString();
    }
}

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

import java.util.ArrayList;
import java.util.List;

/** Parser for the I-Regexp grammar (RFC 9485, section 3). */
final class RegexParser {
    private final String source;
    private int pos;
    private int nesting;

    RegexParser(String source) {
        this.source = source;
    }

    RegexTree parse() {
        RegexTree node = regexp();
        if (pos != source.length()) {
            throw new InvalidRegexp();
        }
        return node;
    }

    private RegexTree regexp() {
        List<RegexTree> branches = new ArrayList<>();
        branches.add(branch());
        while (peek() == '|') {
            pos++;
            branches.add(branch());
        }
        return branches.size() == 1 ? branches.get(0) : new RegexTree.Alternation(branches);
    }

    private RegexTree branch() {
        List<RegexTree> pieces = new ArrayList<>();
        while (pos < source.length() && peek() != '|' && peek() != ')') {
            pieces.add(piece());
        }
        return pieces.size() == 1 ? pieces.get(0) : new RegexTree.Sequence(pieces);
    }

    private RegexTree piece() {
        RegexTree atom = atom();
        int c = peek();
        if (c == '*') {
            pos++;
            return new RegexTree.Repeat(atom, 0, -1);
        }
        if (c == '+') {
            pos++;
            return new RegexTree.Repeat(atom, 1, -1);
        }
        if (c == '?') {
            pos++;
            return new RegexTree.Repeat(atom, 0, 1);
        }
        if (c == '{') {
            pos++;
            int min = quantity();
            int max = min;
            if (peek() == ',') {
                pos++;
                max = peek() == '}' ? -1 : quantity();
                if (max >= 0 && max < min) {
                    throw new InvalidRegexp();
                }
            }
            expect('}');
            return new RegexTree.Repeat(atom, min, max);
        }
        return atom;
    }

    private int quantity() {
        int start = pos;
        while (pos < source.length() && source.charAt(pos) >= '0' && source.charAt(pos) <= '9') {
            pos++;
        }
        if (start == pos) {
            throw new InvalidRegexp();
        }
        try {
            return Integer.parseInt(source.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new InvalidRegexp();
        }
    }

    private RegexTree atom() {
        int c = next();
        switch (c) {
            case '(':
                if (++nesting > IRegexp.MAX_NESTING) {
                    throw new InvalidRegexp();
                }
                RegexTree inner = regexp();
                expect(')');
                nesting--;
                return inner;
            case '.':
                // I-Regexp '.' matches any character except line feed and carriage return.
                return new RegexTree.Chars(CharSet.ranges(true, new int[] {'\n', '\n', '\r', '\r'}));
            case '\\':
                return new RegexTree.Chars(escape());
            case '[':
                return new RegexTree.Chars(charClass());
            // The I-Regexp grammar lists '^' and '$' as normal characters, but the Compliance
            // Test Suite ("explicit caret", "explicit dollar") expects them to act as anchors,
            // as in the regex dialects RFC 9485 section 5 maps to. See the constant.
            case '^':
            case '$':
                if (IRegexp.CARET_AND_DOLLAR_ARE_ANCHORS) {
                    return c == '^' ? new RegexTree.Begin() : new RegexTree.End();
                }
                return new RegexTree.Chars(CharSet.single(c));
            default:
                if (!isNormalChar(c)) {
                    throw new InvalidRegexp();
                }
                return new RegexTree.Chars(CharSet.single(c));
        }
    }

    private static boolean isNormalChar(int c) {
        return (c <= 0x27)
                || c == ','
                || c == '-'
                || (c >= 0x2F && c <= 0x3E)
                || (c >= 0x40 && c <= 0x5A)
                || (c >= 0x5E && c <= 0x7A)
                || (c >= 0x7E && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0x10FFFF);
    }

    /** Parses an escape after the backslash: a single character or a category. */
    private CharSet escape() {
        int c = next();
        if (c == 'p' || c == 'P') {
            return CharSet.category(category(), c == 'P');
        }
        return CharSet.single(singleCharEscape(c));
    }

    private static int singleCharEscape(int c) {
        switch (c) {
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case '(':
            case ')':
            case '*':
            case '+':
            case '-':
            case '.':
            case '?':
            case '[':
            case '\\':
            case ']':
            case '^':
            case '{':
            case '|':
            case '}':
                return c;
            default:
                throw new InvalidRegexp();
        }
    }

    private long category() {
        expect('{');
        int start = pos;
        while (pos < source.length() && source.charAt(pos) != '}') {
            pos++;
        }
        String name = source.substring(start, pos);
        expect('}');
        Long mask = CharSet.CATEGORIES.get(name);
        if (mask == null) {
            throw new InvalidRegexp();
        }
        return mask;
    }

    private CharSet charClass() {
        boolean negated = false;
        if (peek() == '^') {
            pos++;
            negated = true;
        }
        CharSet.Builder builder = new CharSet.Builder(negated);
        boolean first = true;
        while (true) {
            int c = peek();
            if (c == ']' && !first) {
                pos++;
                return builder.build();
            }
            if (c == '-') {
                pos++;
                if (first || peek() == ']') {
                    // A literal '-' is only allowed first or last.
                    builder.range('-', '-');
                    first = false;
                    continue;
                }
                throw new InvalidRegexp();
            }
            classItem(builder);
            first = false;
        }
    }

    private void classItem(CharSet.Builder builder) {
        int c = next();
        if (c == '\\') {
            int e = next();
            if (e == 'p' || e == 'P') {
                builder.category(category(), e == 'P');
                return;
            }
            rangeFrom(singleCharEscape(e), builder);
            return;
        }
        rangeFrom(classChar(c), builder);
    }

    private void rangeFrom(int lo, CharSet.Builder builder) {
        if (peek() == '-' && pos + 1 < source.length() && source.charAt(pos + 1) != ']') {
            pos++;
            int c = next();
            int hi = c == '\\' ? singleCharEscape(next()) : classChar(c);
            if (hi < lo) {
                throw new InvalidRegexp();
            }
            builder.range(lo, hi);
        } else {
            builder.range(lo, lo);
        }
    }

    private static int classChar(int c) {
        if (c == '-' || c == '[' || c == ']') {
            throw new InvalidRegexp();
        }
        return c;
    }

    private int peek() {
        return pos < source.length() ? source.codePointAt(pos) : -1;
    }

    private int next() {
        if (pos >= source.length()) {
            throw new InvalidRegexp();
        }
        int c = source.codePointAt(pos);
        if (Character.isSurrogate(source.charAt(pos)) && Character.charCount(c) == 1) {
            throw new InvalidRegexp();
        }
        pos += Character.charCount(c);
        return c;
    }

    private void expect(int c) {
        if (next() != c) {
            throw new InvalidRegexp();
        }
    }
}

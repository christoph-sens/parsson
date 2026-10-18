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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A set of code points for {@link IRegexp}: code point ranges plus Unicode general categories,
 * optionally negated.
 */
final class CharSet {

    /** Unicode general categories allowed by I-Regexp, as bit masks over {@link UnicodeCategories#type(int)}. */
    static final Map<String, Long> CATEGORIES;

    static {
        long lu = bit(Character.UPPERCASE_LETTER);
        long ll = bit(Character.LOWERCASE_LETTER);
        long lt = bit(Character.TITLECASE_LETTER);
        long lm = bit(Character.MODIFIER_LETTER);
        long lo = bit(Character.OTHER_LETTER);
        long mn = bit(Character.NON_SPACING_MARK);
        long me = bit(Character.ENCLOSING_MARK);
        long mc = bit(Character.COMBINING_SPACING_MARK);
        long nd = bit(Character.DECIMAL_DIGIT_NUMBER);
        long nl = bit(Character.LETTER_NUMBER);
        long no = bit(Character.OTHER_NUMBER);
        long pc = bit(Character.CONNECTOR_PUNCTUATION);
        long pd = bit(Character.DASH_PUNCTUATION);
        long ps = bit(Character.START_PUNCTUATION);
        long pe = bit(Character.END_PUNCTUATION);
        long pi = bit(Character.INITIAL_QUOTE_PUNCTUATION);
        long pf = bit(Character.FINAL_QUOTE_PUNCTUATION);
        long po = bit(Character.OTHER_PUNCTUATION);
        long zs = bit(Character.SPACE_SEPARATOR);
        long zl = bit(Character.LINE_SEPARATOR);
        long zp = bit(Character.PARAGRAPH_SEPARATOR);
        long sm = bit(Character.MATH_SYMBOL);
        long sc = bit(Character.CURRENCY_SYMBOL);
        long sk = bit(Character.MODIFIER_SYMBOL);
        long so = bit(Character.OTHER_SYMBOL);
        long cc = bit(Character.CONTROL);
        long cf = bit(Character.FORMAT);
        long co = bit(Character.PRIVATE_USE);
        long cn = bit(Character.UNASSIGNED);
        long cs = bit(Character.SURROGATE);
        Map<String, Long> categories = new HashMap<>();
        categories.put("L", lu | ll | lt | lm | lo);
        categories.put("Lu", lu);
        categories.put("Ll", ll);
        categories.put("Lt", lt);
        categories.put("Lm", lm);
        categories.put("Lo", lo);
        categories.put("M", mn | me | mc);
        categories.put("Mn", mn);
        categories.put("Me", me);
        categories.put("Mc", mc);
        categories.put("N", nd | nl | no);
        categories.put("Nd", nd);
        categories.put("Nl", nl);
        categories.put("No", no);
        categories.put("P", pc | pd | ps | pe | pi | pf | po);
        categories.put("Pc", pc);
        categories.put("Pd", pd);
        categories.put("Ps", ps);
        categories.put("Pe", pe);
        categories.put("Pi", pi);
        categories.put("Pf", pf);
        categories.put("Po", po);
        categories.put("Z", zs | zl | zp);
        categories.put("Zs", zs);
        categories.put("Zl", zl);
        categories.put("Zp", zp);
        categories.put("S", sm | sc | sk | so);
        categories.put("Sm", sm);
        categories.put("Sc", sc);
        categories.put("Sk", sk);
        categories.put("So", so);
        // "C" includes Cs like Unicode's definition; lone surrogates can occur in Java strings.
        categories.put("C", cc | cf | co | cn | cs);
        categories.put("Cc", cc);
        categories.put("Cf", cf);
        categories.put("Co", co);
        categories.put("Cn", cn);
        CATEGORIES = Collections.unmodifiableMap(categories);
    }

    private final boolean negated;
    /** Sorted, non-overlapping pairs [lo, hi]. */
    private final int[] ranges;
    /** Matches if the code point's category is in this mask. */
    private final long categories;
    /** Each entry matches if the code point's category is NOT in that mask. */
    private final long[] complementedCategories;

    private CharSet(boolean negated, int[] ranges, long categories, long[] complementedCategories) {
        this.negated = negated;
        this.ranges = ranges;
        this.categories = categories;
        this.complementedCategories = complementedCategories;
    }

    private static long bit(int type) {
        return 1L << type;
    }

    static CharSet single(int c) {
        return new CharSet(false, new int[] {c, c}, 0, new long[0]);
    }

    static CharSet ranges(boolean negated, int[] ranges) {
        return new CharSet(negated, ranges, 0, new long[0]);
    }

    static CharSet category(long mask, boolean complement) {
        return complement
                ? new CharSet(false, new int[0], 0, new long[] {mask})
                : new CharSet(false, new int[0], mask, new long[0]);
    }

    boolean matches(int cp) {
        return contains(cp) != negated;
    }

    private boolean contains(int cp) {
        int low = 0;
        int high = ranges.length / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (cp < ranges[2 * mid]) {
                high = mid - 1;
            } else if (cp > ranges[2 * mid + 1]) {
                low = mid + 1;
            } else {
                return true;
            }
        }
        if (categories == 0 && complementedCategories.length == 0) {
            return false;
        }
        long type = bit(UnicodeCategories.type(cp));
        if ((categories & type) != 0) {
            return true;
        }
        for (long mask : complementedCategories) {
            if ((mask & type) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Collects the items of a character class expression. */
    static final class Builder {
        private final boolean negated;
        private final List<int[]> ranges = new ArrayList<>();
        private long categories;
        private final List<Long> complemented = new ArrayList<>();

        Builder(boolean negated) {
            this.negated = negated;
        }

        void range(int lo, int hi) {
            ranges.add(new int[] {lo, hi});
        }

        void category(long mask, boolean complement) {
            if (complement) {
                complemented.add(mask);
            } else {
                categories |= mask;
            }
        }

        CharSet build() {
            ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] r : ranges) {
                int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && r[0] <= last[1] + 1) {
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    merged.add(r.clone());
                }
            }
            int[] flat = new int[merged.size() * 2];
            for (int i = 0; i < merged.size(); i++) {
                flat[2 * i] = merged.get(i)[0];
                flat[2 * i + 1] = merged.get(i)[1];
            }
            long[] comp = complemented.stream().mapToLong(Long::longValue).toArray();
            return new CharSet(negated, flat, categories, comp);
        }
    }

    @Override
    public String toString() {
        return (negated ? "^" : "") + Arrays.toString(ranges) + " cat=" + Long.toHexString(categories);
    }
}

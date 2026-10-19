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

/**
 * The Unicode general category of a code point, for I-Regexp's {@code \p{..}} escapes, from one fixed
 * Unicode version ({@link UnicodeCategoryData#VERSION}) instead of the JDK's, whose Unicode version
 * differs between Java 11, 17, 21 and 25. Categories are {@link Character#getType(int)} constants.
 *
 * <p>The data are runs of code points with the same category: their first code points, sorted, and their
 * categories. Latin-1 has a direct table.
 */
final class UnicodeCategories {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static final int[] RUN_STARTS = new int[UnicodeCategoryData.RUNS];
    private static final byte[] RUN_TYPES = new byte[UnicodeCategoryData.RUNS];
    private static final byte[] LATIN_1 = new byte[256];

    static {
        String data = UnicodeCategoryData.RUN_DATA;
        for (int run = 0; run < UnicodeCategoryData.RUNS; run++) {
            int start = 0;
            for (int i = 0; i < 4; i++) {
                start = (start << 6) | ALPHABET.indexOf(data.charAt(5 * run + i));
            }
            RUN_STARTS[run] = start;
            RUN_TYPES[run] = (byte) ALPHABET.indexOf(data.charAt(5 * run + 4));
        }
        for (int cp = 0; cp < LATIN_1.length; cp++) {
            LATIN_1[cp] = lookUp(cp);
        }
    }

    private UnicodeCategories() {
    }

    /** Returns the general category of a code point as a {@link Character#getType(int)} constant. */
    static int type(int cp) {
        return cp < LATIN_1.length ? LATIN_1[cp] : lookUp(cp);
    }

    private static byte lookUp(int cp) {
        int low = 0;
        int high = RUN_STARTS.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (RUN_STARTS[mid] <= cp) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return RUN_TYPES[low];
    }
}

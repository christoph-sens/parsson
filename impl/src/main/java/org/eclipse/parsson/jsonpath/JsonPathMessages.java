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

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.ResourceBundle;

/**
 * Messages of JSONPath errors, from the resource bundle {@code org.eclipse.parsson.jsonpath.messages}.
 */
final class JsonPathMessages {

    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("org.eclipse.parsson.jsonpath.messages");

    /** Maximum number of query characters shown in a syntax error. */
    private static final int EXCERPT_LENGTH = 60;

    private JsonPathMessages() {
    }

    /** Returns the message with the given key; arguments are passed as strings, so numbers are not localized. */
    static String get(String key, Object... arguments) {
        return MessageFormat.format(BUNDLE.getString(key), arguments);
    }

    /**
     * Returns the message of a syntax error: the reason, the position and an excerpt of at most
     * {@value #EXCERPT_LENGTH} characters of the query around it, with a caret under the position, so that
     * long queries do not end up in logs in full.
     *
     * <pre>
     * Expected a query, a literal or a function at position 18:
     *   $.store[?@.price &lt;]
     *                     ^
     * </pre>
     */
    static String syntaxError(String reason, String query, int position) {
        return get("path.syntax.error", reason, String.valueOf(position), excerpt(query, position));
    }

    /** Two lines: the query around {@code position}, shortened with "...", and a caret under it. */
    private static String excerpt(String query, int position) {
        int start = 0;
        int end = query.length();
        if (end > EXCERPT_LENGTH) {
            start = Math.max(0, Math.min(position - EXCERPT_LENGTH / 2, end - EXCERPT_LENGTH));
            end = start + EXCERPT_LENGTH;
        }
        StringBuilder line = new StringBuilder("  ");
        if (start > 0) {
            line.append("...");
        }
        int caret = line.length() + (position - start);
        for (int i = start; i < end; i++) {
            char c = query.charAt(i);
            // Blanks such as newlines and tabs would break the alignment of the caret.
            line.append(c < 0x20 ? ' ' : c);
        }
        if (end < query.length()) {
            line.append("...");
        }
        char[] indent = new char[caret];
        Arrays.fill(indent, ' ');
        return line.append('\n').append(indent).append('^').toString();
    }
}

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

import jakarta.json.JsonValue;

/**
 * A node of the query argument: a value together with its location. The location is kept as a link to
 * the parent node and the step from there, a member name or an array index, so that a child costs one
 * object and extending a path is O(1); the chain of parents is the path. Path strings are built only
 * when asked for.
 */
final class NodeImpl {

    private final JsonValue value;
    private final NodeImpl parent;
    /** The member name of the last step, or {@code null} if it is an array index or this is the root. */
    private final String name;
    /** The array index of the last step, or -1 if it is a member name or this is the root. */
    private final int index;
    private final int depth;

    /**
     * The normalized path, once built. Racy but benign: strings are immutable, and a thread that sees no
     * path builds the same one again.
     */
    private String path;

    private NodeImpl(JsonValue value, NodeImpl parent, String name, int index) {
        this.value = value;
        this.parent = parent;
        this.name = name;
        this.index = index;
        this.depth = parent == null ? 0 : parent.depth + 1;
    }

    /** Returns the root node of a query argument. */
    static NodeImpl root(JsonValue document) {
        return new NodeImpl(document, null, null, -1);
    }

    /** Returns the member of this node with the given name and value. */
    NodeImpl child(JsonValue value, String name) {
        return new NodeImpl(value, this, name, -1);
    }

    /** Returns the element of this node with the given index and value. */
    NodeImpl child(JsonValue value, int index) {
        return new NodeImpl(value, this, null, index);
    }

    JsonValue value() {
        return value;
    }

    /** Returns the number of steps from the root; the root has depth 0. */
    int depth() {
        return depth;
    }

    /**
     * Returns the location as a normalized path (RFC 9535, section 2.7), for example
     * {@code $['store']['book'][0]}. The path continues from the nearest ancestor that has built its path
     * already, so the paths of nodes below other selected nodes, as in {@code $..*}, cost only their own
     * steps.
     */
    String normalizedPath() {
        String known = path;
        if (known != null) {
            return known;
        }
        String prefix = "$";
        int count = 0;
        for (NodeImpl n = this; n.parent != null; n = n.parent) {
            String ancestorPath = n.path;
            if (ancestorPath != null) {
                prefix = ancestorPath;
                break;
            }
            count++;
        }
        NodeImpl[] steps = new NodeImpl[count];
        NodeImpl n = this;
        for (int i = count - 1; i >= 0; i--) {
            steps[i] = n;
            n = n.parent;
        }
        StringBuilder sb = new StringBuilder(prefix.length() + 8 * count).append(prefix);
        for (NodeImpl step : steps) {
            if (step.name != null) {
                appendName(sb, step.name);
            } else {
                sb.append('[').append(step.index).append(']');
            }
        }
        known = sb.toString();
        path = known;
        return known;
    }

    /** Appends a name step of a normalized path, {@code ['name']}, escaped as RFC 9535, section 2.7 prescribes. */
    static void appendName(StringBuilder sb, String name) {
        sb.append("['");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\'':
                    sb.append("\\'");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00").append(c < 0x10 ? '0' : '1').append(Character.forDigit(c & 0xf, 16));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append("']");
    }

    @Override
    public String toString() {
        return normalizedPath() + " = " + value;
    }
}

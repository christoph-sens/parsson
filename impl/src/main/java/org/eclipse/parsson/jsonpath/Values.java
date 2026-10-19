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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;

import org.eclipse.parsson.jsonpath.Ast.ComparisonOp;

/**
 * Comparison of values in filters (RFC 9535, section 2.3.5.2.2). A value is a {@link JsonValue}, or
 * {@code null} for the special result Nothing (an empty node list or a function without result).
 *
 * <p>Numbers are compared by {@link JsonNumber#bigDecimalValue()} with {@code compareTo}, so {@code 1} and
 * {@code 1.0} are equal; {@link JsonNumber#equals} is not used because it depends on the scale. Strings are
 * compared by Unicode code points, not by UTF-16 code units.
 */
final class Values {

    private Values() {
    }

    /**
     * Compares two values; {@code !=} is the negation of {@code ==}.
     *
     * @param maxDepth how deep arrays and objects are compared before the comparison fails
     */
    static boolean compare(JsonValue left, ComparisonOp op, JsonValue right, int maxDepth) {
        switch (op) {
            case EQ:
                return equal(left, right, maxDepth);
            case NE:
                return !equal(left, right, maxDepth);
            case LT:
                return less(left, right);
            case LE:
                return lessOrEqual(left, right, maxDepth);
            case GT:
                return less(right, left);
            case GE:
                return lessOrEqual(right, left, maxDepth);
            default:
                throw new IllegalStateException(op.name());
        }
    }

    private static boolean equal(JsonValue a, JsonValue b, int maxDepth) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return jsonEquals(a, b, maxDepth);
    }

    private static boolean less(JsonValue a, JsonValue b) {
        if (a == null || b == null) {
            return false;
        }
        ValueType ka = a.getValueType();
        ValueType kb = b.getValueType();
        if (ka == ValueType.NUMBER && kb == ValueType.NUMBER) {
            return compareNumbers((JsonNumber) a, (JsonNumber) b) < 0;
        }
        if (ka == ValueType.STRING && kb == ValueType.STRING) {
            return compareCodePoints(((JsonString) a).getString(), ((JsonString) b).getString()) < 0;
        }
        return false;
    }

    /** {@code a < b || a == b}, comparing numbers and strings once. */
    private static boolean lessOrEqual(JsonValue a, JsonValue b, int maxDepth) {
        if (a != null && b != null) {
            ValueType ka = a.getValueType();
            ValueType kb = b.getValueType();
            if (ka == ValueType.NUMBER && kb == ValueType.NUMBER) {
                return compareNumbers((JsonNumber) a, (JsonNumber) b) <= 0;
            }
            if (ka == ValueType.STRING && kb == ValueType.STRING) {
                return compareCodePoints(((JsonString) a).getString(), ((JsonString) b).getString()) <= 0;
            }
        }
        return equal(a, b, maxDepth);
    }

    /** Compares strings by Unicode code points (UTF-16 order differs above U+FFFF). */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    private static int compareNumbers(JsonNumber a, JsonNumber b) {
        return a.bigDecimalValue().compareTo(b.bigDecimalValue());
    }

    /** A pair of values still to compare, at a depth below the compared values. */
    private static final class Pending {

        final JsonValue a;
        final JsonValue b;
        final int depth;

        Pending(JsonValue a, JsonValue b, int depth) {
            this.a = a;
            this.b = b;
            this.depth = depth;
        }
    }

    /**
     * Whether two values are equal as JSON values, the semantics of {@code ==} in filters: numbers by value,
     * strings by content, arrays element by element, objects member by member regardless of order. Uses an
     * explicit stack, so that deep values cannot overflow the call stack.
     *
     * @param maxDepth how deep arrays and objects are compared before the comparison fails
     * @throws jakarta.json.JsonException if the values are nested deeper than {@code maxDepth}
     */
    static boolean jsonEquals(JsonValue a, JsonValue b, int maxDepth) {
        ValueType kind = a.getValueType();
        if (kind != b.getValueType()) {
            return false;
        }
        if (kind != ValueType.ARRAY && kind != ValueType.OBJECT) {
            // Most comparisons in filters are between scalars; they need no stack.
            return scalarEqual(kind, a, b);
        }
        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(a, b, 0));
        while (!pending.isEmpty()) {
            Pending p = pending.pop();
            kind = p.a.getValueType();
            if (kind != p.b.getValueType()) {
                return false;
            }
            if (kind == ValueType.ARRAY) {
                JsonArray aa = (JsonArray) p.a;
                JsonArray ab = (JsonArray) p.b;
                int size = aa.size();
                if (size != ab.size()) {
                    return false;
                }
                if (size > 0 && p.depth >= maxDepth) {
                    throw Evaluator.tooDeep(maxDepth);
                }
                for (int i = 0; i < size; i++) {
                    pending.push(new Pending(aa.get(i), ab.get(i), p.depth + 1));
                }
            } else if (kind == ValueType.OBJECT) {
                JsonObject oa = (JsonObject) p.a;
                JsonObject ob = (JsonObject) p.b;
                if (oa.size() != ob.size()) {
                    return false;
                }
                if (!oa.isEmpty() && p.depth >= maxDepth) {
                    throw Evaluator.tooDeep(maxDepth);
                }
                for (Map.Entry<String, JsonValue> member : oa.entrySet()) {
                    JsonValue other = ob.get(member.getKey());
                    if (other == null) {
                        return false;
                    }
                    pending.push(new Pending(member.getValue(), other, p.depth + 1));
                }
            } else if (!scalarEqual(kind, p.a, p.b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean scalarEqual(ValueType kind, JsonValue a, JsonValue b) {
        switch (kind) {
            case NUMBER:
                return compareNumbers((JsonNumber) a, (JsonNumber) b) == 0;
            case STRING:
                return ((JsonString) a).getString().equals(((JsonString) b).getString());
            default:
                // null, true and false: the value type is the value
                return true;
        }
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import org.eclipse.parsson.JsonProviderImpl;
import org.eclipse.parsson.jsonpath.Ast.Comparison;
import org.eclipse.parsson.jsonpath.Ast.Filter;
import org.eclipse.parsson.jsonpath.Ast.Literal;
import org.eclipse.parsson.jsonpath.Ast.Query;
import org.junit.jupiter.api.Test;

/**
 * Tests of the parser beyond the Compliance Test Suite: error messages and positions, limits, literals.
 */
class ParserTest {

    private static final JsonProvider PROVIDER = new JsonProviderImpl();

    private static Query parse(String query) {
        return Parser.parse(query, PROVIDER);
    }

    private static String error(String query) {
        return assertThrows(JsonException.class, () -> parse(query)).getMessage();
    }

    private static int position(String query) {
        String message = error(query);
        String marker = " at position ";
        int start = message.indexOf(marker) + marker.length();
        return Integer.parseInt(message.substring(start, message.indexOf(':', start)));
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static JsonValue rightLiteral(String query) {
        Filter filter = (Filter) parse(query).segments.get(0).selectors.get(0);
        return ((Literal) ((Comparison) filter.expr).right).value;
    }

    @Test
    void showsAnExcerptWithACaretInSyntaxErrors() {
        assertEquals("Expected a query, a literal or a function at position 18:\n"
                        + "  $.store[?@.price <]\n"
                        + "                    ^",
                error("$.store[?@.price <]"));
    }

    @Test
    void shortensLongQueriesInSyntaxErrors() {
        String query = "$." + repeat("a", 200) + "[?@.x <]" + repeat(".b", 100);

        String[] lines = error(query).split("\n");

        assertEquals(3, lines.length);
        assertEquals("Expected a query, a literal or a function at position 209:", lines[0]);
        assertTrue(lines[1].startsWith("  ...") && lines[1].endsWith("..."), lines[1]);
        assertEquals(2 + 3 + 60 + 3, lines[1].length());
        assertEquals(']', lines[1].charAt(lines[2].indexOf('^')));
    }

    @Test
    void keepsTheCaretAlignedWhenTheQueryContainsLineBreaks() {
        String[] lines = error("$[?@.a ==\n\t]").split("\n");

        assertEquals("  $[?@.a ==  ]", lines[1]);
        assertEquals(2 + 11, lines[2].indexOf('^'));
    }

    @Test
    void pointsTypeErrorsAtTheOperand() {
        assertEquals(3, position("$[?length(@.a) && @.b]"));
        assertEquals(11, position("$[?@.b || !length(@.a)]"));
    }

    @Test
    void rejectsDeeplyNestedExpressions() {
        String nested = repeat("(", 1000) + "@.a" + repeat(")", 1000);

        assertTrue(error("$[?" + nested + "]").contains("nested more than 64 levels"));
    }

    @Test
    void acceptsNestingUpToTheLimit() {
        // The filter itself is the first level.
        String nested = repeat("(", Parser.MAX_NESTING - 1) + "@.a" + repeat(")", Parser.MAX_NESTING - 1);

        parse("$[?" + nested + "]");
    }

    @Test
    void rejectsJaywayOnlySyntax() {
        // Jayway JsonPath accepts these; RFC 9535 does not.
        error("$.book[?(@.price < 10)].length()");
        error("$.book[?@.tags in ['a']]");
        error("$.book[?@.title =~ /a.*/]");
    }

    @Test
    void namesUnknownFunctions() {
        assertTrue(error("$[?foo(@.a)]").startsWith("Unknown function 'foo' at position 3:"));
        assertTrue(error("$[?length(@.a, @.b) == 1]").startsWith("Function 'length' expects 1 argument(s)"));
    }

    @Test
    void limitsIntegersToTheIJsonRange() {
        parse("$[9007199254740991]");
        parse("$[-9007199254740991]");
        parse("$[-9007199254740991:9007199254740991:9007199254740991]");
        assertTrue(error("$[9007199254740992]").startsWith("Integer out of range at position 2:"));
        error("$[-9007199254740992]");
        error("$[99999999999999999999999]");
        error("$[0:1:9007199254740992]");
    }

    @Test
    void parsesNumberLiteralsExactly() {
        JsonValue value = rightLiteral("$[?@ == 0.1]");

        assertEquals(new BigDecimal("0.1"), ((JsonNumber) value).bigDecimalValue());
        assertEquals(new BigDecimal("1E+400"), ((JsonNumber) rightLiteral("$[?@ == 1e400]")).bigDecimalValue());
        assertEquals(new BigDecimal("-12.5E-7"), ((JsonNumber) rightLiteral("$[?@ == -12.5E-7]")).bigDecimalValue());
    }

    @Test
    void rejectsNumbersBeyondTheRangeOfBigDecimal() {
        assertTrue(error("$[?@ == 1e99999999999]").startsWith("Number out of range"));
    }

    @Test
    void parsesOtherLiterals() {
        assertEquals("a'bé😀", ((JsonString) rightLiteral("$[?@ == 'a\\'b\\u00e9\\ud83d\\ude00']")).getString());
        assertSame(JsonValue.TRUE, rightLiteral("$[?@ == true]"));
        assertSame(JsonValue.FALSE, rightLiteral("$[?@ == false]"));
        assertSame(JsonValue.NULL, rightLiteral("$[?@ == null]"));
    }

    @Test
    void knowsSingularQueries() {
        assertTrue(parse("$.a['b'][0]").isSingular());
        assertTrue(parse("$").isSingular());
        assertFalse(parse("$.a[*]").isSingular());
        assertFalse(parse("$..a").isSingular());
        assertFalse(parse("$[0,1]").isSingular());
        assertFalse(parse("$[0:1]").isSingular());
    }

    @Test
    void rejectsQueriesThatDoNotStartWithTheRoot() {
        assertTrue(error("").startsWith("Query must start with '$' at position 0:"));
        error("@.a");
        error(" $.a");
        error("$.a ");
    }
}

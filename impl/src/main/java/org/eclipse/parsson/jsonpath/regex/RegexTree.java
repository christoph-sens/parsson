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

import java.util.List;

/**
 * Syntax tree of an I-Regexp (RFC 9485), as {@link RegexParser} builds it. The node types are the nested
 * classes; the private constructor keeps the hierarchy closed.
 */
abstract class RegexTree {

    private RegexTree() {
    }

    static final class Alternation extends RegexTree {
        final List<RegexTree> branches;

        Alternation(List<RegexTree> branches) {
            this.branches = branches;
        }
    }

    static final class Sequence extends RegexTree {
        final List<RegexTree> pieces;

        Sequence(List<RegexTree> pieces) {
            this.pieces = pieces;
        }
    }

    static final class Repeat extends RegexTree {
        final RegexTree atom;
        final int min;
        /** -1 for "unbounded". */
        final int max;

        Repeat(RegexTree atom, int min, int max) {
            this.atom = atom;
            this.min = min;
            this.max = max;
        }
    }

    static final class Chars extends RegexTree {
        final CharSet set;

        Chars(CharSet set) {
            this.set = set;
        }
    }

    static final class Begin extends RegexTree {
    }

    static final class End extends RegexTree {
    }
}

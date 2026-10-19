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

/**
 * The nondeterministic automaton of an I-Regexp, built by Thompson's construction: one instruction per
 * index, with an operation, a successor, an alternative successor (SPLIT) and a character set (CHAR).
 */
final class RegexProgram {

    // Instructions of the automaton.
    static final int CHAR = 0;
    static final int SPLIT = 1;
    static final int JUMP = 2;
    static final int BEGIN = 3;
    static final int END = 4;
    static final int MATCH = 5;

    final int[] ops;
    final int[] next;
    final int[] alt;
    final CharSet[] sets;

    private RegexProgram(Builder builder) {
        int size = builder.ops.size();
        ops = new int[size];
        next = new int[size];
        alt = new int[size];
        sets = new CharSet[size];
        for (int i = 0; i < size; i++) {
            ops[i] = builder.ops.get(i);
            next[i] = builder.next.get(i);
            alt[i] = builder.alt.get(i);
            sets[i] = builder.sets.get(i);
        }
    }

    /**
     * Compiles a syntax tree.
     *
     * @param maxSize the maximum number of instructions
     * @throws InvalidRegexp if the automaton would exceed {@code maxSize} instructions
     */
    static RegexProgram compile(RegexTree tree, int maxSize) {
        Builder builder = new Builder(maxSize);
        builder.emit(tree);
        builder.add(MATCH, -1, -1, NO_SET);
        return new RegexProgram(builder);
    }

    /** Returns the number of instructions. */
    int size() {
        return ops.length;
    }

    /** Placeholder in {@link #sets} for instructions other than CHAR; never consulted. */
    private static final CharSet NO_SET = CharSet.ranges(false, new int[0]);

    /** Thompson construction of the automaton. */
    private static final class Builder {
        final List<Integer> ops = new ArrayList<>();
        final List<Integer> next = new ArrayList<>();
        final List<Integer> alt = new ArrayList<>();
        final List<CharSet> sets = new ArrayList<>();
        final int maxSize;

        Builder(int maxSize) {
            this.maxSize = maxSize;
        }

        int add(int op, int nextPc, int altPc, CharSet set) {
            if (ops.size() >= maxSize) {
                throw new InvalidRegexp();
            }
            ops.add(op);
            next.add(nextPc);
            alt.add(altPc);
            sets.add(set);
            return ops.size() - 1;
        }

        int pc() {
            return ops.size();
        }

        void emit(RegexTree node) {
            if (node instanceof RegexTree.Sequence) {
                for (RegexTree piece : ((RegexTree.Sequence) node).pieces) {
                    emit(piece);
                }
            } else if (node instanceof RegexTree.Chars) {
                add(CHAR, pc() + 1, -1, ((RegexTree.Chars) node).set);
            } else if (node instanceof RegexTree.Begin) {
                add(BEGIN, pc() + 1, -1, NO_SET);
            } else if (node instanceof RegexTree.End) {
                add(END, pc() + 1, -1, NO_SET);
            } else if (node instanceof RegexTree.Alternation) {
                emitAlternation(((RegexTree.Alternation) node).branches);
            } else if (node instanceof RegexTree.Repeat) {
                emitRepeat((RegexTree.Repeat) node);
            } else {
                throw new IllegalStateException();
            }
        }

        private void emitAlternation(List<RegexTree> branches) {
            List<Integer> jumps = new ArrayList<>();
            for (int i = 0; i < branches.size() - 1; i++) {
                int split = add(SPLIT, pc() + 1, -1, NO_SET);
                emit(branches.get(i));
                jumps.add(add(JUMP, -1, -1, NO_SET));
                alt.set(split, pc());
            }
            emit(branches.get(branches.size() - 1));
            for (int jump : jumps) {
                next.set(jump, pc());
            }
        }

        private void emitRepeat(RegexTree.Repeat repeat) {
            if (emitsNothing(repeat.atom)) {
                // Repeating nothing is nothing. Without this, `(){2000000000}` loops two billion times
                // without growing the program, so the program size limit would never stop it.
                return;
            }
            for (int i = 0; i < repeat.min; i++) {
                emit(repeat.atom);
            }
            if (repeat.max < 0) {
                int loop = add(SPLIT, pc() + 1, -1, NO_SET);
                emit(repeat.atom);
                add(JUMP, loop, -1, NO_SET);
                alt.set(loop, pc());
                return;
            }
            List<Integer> splits = new ArrayList<>();
            for (int i = repeat.min; i < repeat.max; i++) {
                splits.add(add(SPLIT, pc() + 1, -1, NO_SET));
                emit(repeat.atom);
            }
            for (int split : splits) {
                alt.set(split, pc());
            }
        }

        /** Whether a node compiles to no instructions: an empty group, or a repetition of one. */
        private static boolean emitsNothing(RegexTree node) {
            if (node instanceof RegexTree.Sequence) {
                for (RegexTree piece : ((RegexTree.Sequence) node).pieces) {
                    if (!emitsNothing(piece)) {
                        return false;
                    }
                }
                return true;
            }
            if (node instanceof RegexTree.Repeat) {
                RegexTree.Repeat repeat = (RegexTree.Repeat) node;
                return repeat.max == 0 || emitsNothing(repeat.atom);
            }
            return false;
        }
    }
}

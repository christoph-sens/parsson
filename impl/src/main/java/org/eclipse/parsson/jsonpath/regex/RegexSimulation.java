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

import static org.eclipse.parsson.jsonpath.regex.RegexProgram.BEGIN;
import static org.eclipse.parsson.jsonpath.regex.RegexProgram.CHAR;
import static org.eclipse.parsson.jsonpath.regex.RegexProgram.END;
import static org.eclipse.parsson.jsonpath.regex.RegexProgram.JUMP;
import static org.eclipse.parsson.jsonpath.regex.RegexProgram.MATCH;
import static org.eclipse.parsson.jsonpath.regex.RegexProgram.SPLIT;

/** Set-of-states simulation; each instruction is visited at most once per input position. */
final class RegexSimulation {

    private final RegexProgram program;
    private final String input;
    private final boolean search;
    private int[] current;
    private int currentSize;
    private int[] following;
    private int followingSize;
    private final int[] visited;
    private int generation = 1;
    private final int[] stack;

    private RegexSimulation(RegexProgram program, String input, boolean search) {
        this.program = program;
        this.input = input;
        this.search = search;
        this.current = new int[program.ops.length];
        this.following = new int[program.ops.length];
        this.visited = new int[program.ops.length];
        this.stack = new int[2 * program.ops.length + 2];
    }

    /** Adds the closure of {@code pc} at {@code pos} to the current list; returns true on a match. */
    boolean add(int pc, int pos) {
        return closure(pc, pos, false);
    }

    /** Advances all threads over {@code cp}; returns true on a match. */
    boolean step(int cp, int pos) {
        generation++;
        followingSize = 0;
        boolean matched = false;
        for (int i = 0; i < currentSize; i++) {
            int pc = current[i];
            if (program.sets[pc].matches(cp)) {
                matched |= closure(program.next[pc], pos, true);
            }
        }
        if (search) {
            matched |= closure(0, pos, true);
        }
        int[] tmp = current;
        current = following;
        following = tmp;
        currentSize = followingSize;
        return matched;
    }

    boolean isDead() {
        return currentSize == 0 && !search;
    }

    private boolean closure(int start, int pos, boolean intoFollowing) {
        boolean matched = false;
        int top = 0;
        stack[top++] = start;
        while (top > 0) {
            int pc = stack[--top];
            if (visited[pc] == generation) {
                continue;
            }
            visited[pc] = generation;
            switch (program.ops[pc]) {
                case CHAR:
                    if (intoFollowing) {
                        following[followingSize++] = pc;
                    } else {
                        current[currentSize++] = pc;
                    }
                    break;
                case SPLIT:
                    stack[top++] = program.alt[pc];
                    stack[top++] = program.next[pc];
                    break;
                case JUMP:
                    stack[top++] = program.next[pc];
                    break;
                case BEGIN:
                    if (pos == 0) {
                        stack[top++] = program.next[pc];
                    }
                    break;
                case END:
                    if (pos == input.length()) {
                        stack[top++] = program.next[pc];
                    }
                    break;
                case MATCH:
                    if (search || pos == input.length()) {
                        matched = true;
                    }
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
        return matched;
    }

    /** Whether the input matches (or, with {@code search}, contains a match), simulating all threads at once. */
    static boolean run(RegexProgram program, String input, boolean search) {
        RegexSimulation sim = new RegexSimulation(program, input, search);
        if (sim.add(0, 0)) {
            return true;
        }
        return sim.runFrom(0);
    }

    /**
     * Continues where {@link LazyDfa} stopped: at {@code pos}, before the end of the input, with the CHAR
     * instructions of its current state as the threads. Its pending END instructions cannot pass before the
     * end, and a match up to {@code pos} has already been reported.
     */
    static boolean resume(RegexProgram program, String input, boolean search, int[] chars, int pos) {
        RegexSimulation sim = new RegexSimulation(program, input, search);
        System.arraycopy(chars, 0, sim.current, 0, chars.length);
        sim.currentSize = chars.length;
        return sim.runFrom(pos);
    }

    private boolean runFrom(int start) {
        int length = input.length();
        int pos = start;
        while (pos < length) {
            int cp = input.codePointAt(pos);
            int nextPos = pos + Character.charCount(cp);
            if (step(cp, nextPos)) {
                return true;
            }
            if (isDead()) {
                return false;
            }
            pos = nextPos;
        }
        return false;
    }
}

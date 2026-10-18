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

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazily built deterministic automaton for non-empty inputs. Each state is a set of CHAR
 * instructions waiting for the next code point, plus END instructions that only pass at the end
 * of the input, plus whether MATCH was reached. States and transitions are shared between threads:
 * states are immutable apart from their transition caches, whose entries are published racily but
 * safely (all fields of a state are final).
 */
final class LazyDfa {

    /** Maximum number of cached deterministic states per automaton. */
    static final int MAX_STATES = 2_000;

    /** Code points below this get a transition slot in every deterministic state. */
    private static final int ASCII = 0x80;

    /** Estimated bytes of a deterministic state besides its arrays: objects, key, map entry. */
    private static final long STATE_OVERHEAD = 160;

    /** Estimated bytes of one cached transition on a non-ASCII code point. */
    private static final long TRANSITION_OVERHEAD = 64;

    private final RegexProgram program;
    private final boolean search;
    private final int generation;
    private final AtomicLong bytes = new AtomicLong();
    private final Map<StateKey, State> states = new ConcurrentHashMap<>();
    /** Null if the budget was exhausted before the start state could be cached. */
    private final State start;

    LazyDfa(RegexProgram program, boolean search, int generation) {
        this.program = program;
        this.search = search;
        this.generation = generation;
        this.start = closureState(null, -1, true, new Scratch(program));
    }

    /**
     * Reserves memory for a new state or transition. Returns false if this automaton is full or
     * discarded; if all automata together are full, discards them all and returns false.
     */
    private boolean reserve(long cost) {
        if (generation != IRegexp.dfaGeneration()) {
            return false;
        }
        if (bytes.addAndGet(cost) > IRegexp.MAX_DFA_BYTES_PER_AUTOMATON) {
            bytes.addAndGet(-cost);
            return false;
        }
        if (!IRegexp.reserveShared(cost, generation)) {
            bytes.addAndGet(-cost);
            return false;
        }
        return true;
    }

    private void release(long cost) {
        bytes.addAndGet(-cost);
        IRegexp.releaseShared(cost, generation);
    }

    /**
     * Returns whether the input matches. Where the state budget is exhausted, the set-of-states simulation
     * takes over at the current position, with the threads of the current state.
     */
    boolean run(String input) {
        State state = start;
        if (state == null) {
            return RegexSimulation.run(program, input, search);
        }
        if (search && state.match) {
            return true;
        }
        // Allocated on the first transition that is not cached yet, then reused for this input.
        Scratch scratch = null;
        int length = input.length();
        int pos = 0;
        while (pos < length) {
            int at = pos;
            char c = input.charAt(pos);
            int cp;
            if (c < ASCII) {
                cp = c;
                pos++;
            } else {
                cp = input.codePointAt(pos);
                pos += Character.charCount(cp);
            }
            State following = state.transition(cp);
            if (following == null) {
                if (scratch == null) {
                    scratch = new Scratch(program);
                }
                following = closureState(state, cp, false, scratch);
                if (following == null) {
                    return RegexSimulation.resume(program, input, search, state.chars, at);
                }
                state.cache(cp, following);
            }
            state = following;
            if (search && state.match) {
                return true;
            }
            if (!search && state.dead) {
                return false;
            }
        }
        return state.match || state.acceptsAtEnd();
    }

    /**
     * Working memory for computing states: the sets are filled anew for each state, and {@code visited}
     * holds the mark of the closure that last visited an instruction, so it never needs clearing.
     */
    private static final class Scratch {
        final int[] visited;
        int mark;
        final int[] chars;
        final int[] ends;
        int charCount;
        int endCount;
        boolean match;
        final int[] stack;

        Scratch(RegexProgram program) {
            int size = program.ops.length;
            visited = new int[size];
            chars = new int[size];
            ends = new int[size];
            stack = new int[2 * size + 2];
        }
    }

    /**
     * Computes the state after reading {@code cp} in {@code from}, or the start state if
     * {@code from} is null. Returns null if the state budget is exhausted.
     */
    private State closureState(State from, int cp, boolean atStart, Scratch scratch) {
        scratch.mark++;
        scratch.charCount = 0;
        scratch.endCount = 0;
        scratch.match = false;
        if (from == null) {
            closure(0, atStart, scratch);
        } else {
            for (int pc : from.chars) {
                if (program.sets[pc].matches(cp)) {
                    closure(program.next[pc], false, scratch);
                }
            }
            if (search) {
                closure(0, false, scratch);
            }
        }
        StateKey key = new StateKey(
                sorted(scratch.chars, scratch.charCount), sorted(scratch.ends, scratch.endCount), scratch.match);
        State state = states.get(key);
        if (state != null) {
            return state;
        }
        if (states.size() >= MAX_STATES) {
            return null;
        }
        long cost = STATE_OVERHEAD + 4L * (key.chars.length + key.ends.length + ASCII);
        if (!reserve(cost)) {
            return null;
        }
        State created = new State(key.chars, key.ends, key.match);
        State existing = states.putIfAbsent(key, created);
        if (existing != null) {
            release(cost);
            return existing;
        }
        return created;
    }

    private void closure(int startPc, boolean atStart, Scratch scratch) {
        int[] visited = scratch.visited;
        int[] stack = scratch.stack;
        int mark = scratch.mark;
        int top = 0;
        stack[top++] = startPc;
        while (top > 0) {
            int pc = stack[--top];
            if (visited[pc] == mark) {
                continue;
            }
            visited[pc] = mark;
            switch (program.ops[pc]) {
                case CHAR:
                    scratch.chars[scratch.charCount++] = pc;
                    break;
                case END:
                    scratch.ends[scratch.endCount++] = pc;
                    break;
                case MATCH:
                    scratch.match = true;
                    break;
                case BEGIN:
                    if (atStart) {
                        stack[top++] = program.next[pc];
                    }
                    break;
                case SPLIT:
                    stack[top++] = program.alt[pc];
                    stack[top++] = program.next[pc];
                    break;
                case JUMP:
                    stack[top++] = program.next[pc];
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private static int[] sorted(int[] values, int count) {
        int[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return copy;
    }

    /** Whether MATCH is reachable from a pending END instruction once the input is exhausted. */
    private boolean reachesMatchAtEnd(int startPc) {
        boolean[] visited = new boolean[program.ops.length];
        int[] stack = new int[2 * program.ops.length + 2];
        int top = 0;
        stack[top++] = startPc;
        while (top > 0) {
            int pc = stack[--top];
            if (visited[pc]) {
                continue;
            }
            visited[pc] = true;
            switch (program.ops[pc]) {
                case MATCH:
                    return true;
                case END:
                case JUMP:
                    stack[top++] = program.next[pc];
                    break;
                case SPLIT:
                    stack[top++] = program.alt[pc];
                    stack[top++] = program.next[pc];
                    break;
                default:
                    // CHAR needs input; BEGIN fails because the input is not empty.
                    break;
            }
        }
        return false;
    }

    private final class State {
        final int[] chars;
        final int[] ends;
        final boolean match;
        final boolean dead;
        /** Transitions on ASCII characters, indexed directly: one lookup per step. */
        final State[] ascii = new State[ASCII];
        /** Transitions on other code points, created on first use; null until then. */
        volatile Map<Integer, State> other;

        /** Null until computed. */
        volatile Boolean acceptsAtEnd;

        State(int[] chars, int[] ends, boolean match) {
            this.chars = chars;
            this.ends = ends;
            this.match = match;
            this.dead = chars.length == 0 && ends.length == 0 && !match;
        }

        /** Returns the cached transition on {@code cp}, or null. */
        State transition(int cp) {
            if (cp < ASCII) {
                return ascii[cp];
            }
            Map<Integer, State> transitions = other;
            return transitions == null ? null : transitions.get(cp);
        }

        void cache(int cp, State state) {
            if (cp < ASCII) {
                ascii[cp] = state;
                return;
            }
            Map<Integer, State> transitions = other;
            if (transitions == null) {
                synchronized (this) {
                    transitions = other;
                    if (transitions == null) {
                        if (!reserve(TRANSITION_OVERHEAD)) {
                            return;
                        }
                        transitions = new ConcurrentHashMap<>();
                        other = transitions;
                    }
                }
            }
            if (transitions.size() < MAX_STATES && !transitions.containsKey(cp) && reserve(TRANSITION_OVERHEAD)) {
                if (transitions.putIfAbsent(cp, state) != null) {
                    release(TRANSITION_OVERHEAD);
                }
            }
        }

        boolean acceptsAtEnd() {
            Boolean cached = acceptsAtEnd;
            if (cached == null) {
                boolean accepts = false;
                for (int pc : ends) {
                    if (reachesMatchAtEnd(program.next[pc])) {
                        accepts = true;
                        break;
                    }
                }
                cached = accepts;
                acceptsAtEnd = cached;
            }
            return cached;
        }
    }

    int generation() {
        return generation;
    }

    /** Estimated bytes held by this automaton; for tests. */
    long bytes() {
        return bytes.get();
    }

    /** Identity of a deterministic state. */
    private static final class StateKey {
        final int[] chars;
        final int[] ends;
        final boolean match;
        private final int hash;

        StateKey(int[] chars, int[] ends, boolean match) {
            this.chars = chars;
            this.ends = ends;
            this.match = match;
            this.hash = 31 * (31 * Arrays.hashCode(chars) + Arrays.hashCode(ends)) + Boolean.hashCode(match);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof StateKey)) {
                return false;
            }
            StateKey other = (StateKey) o;
            return match == other.match
                    && Arrays.equals(chars, other.chars)
                    && Arrays.equals(ends, other.ends);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}

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

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The memory budget that the cached automata of a set of expressions share. When the automata together
 * would exceed it, they are all discarded at once and rebuilt on demand, as RE2 does: the generation is
 * incremented, and automata of an older generation stop growing and are dropped.
 *
 * <p>Production code uses one budget for the whole JVM, {@link #SHARED}. Tests create their own, so that
 * other tests filling the shared budget cannot change their outcome.
 */
final class DfaBudget {

    /** The budget of all expressions that {@link IRegexp#compile(String, int)} compiles. */
    static final DfaBudget SHARED = new DfaBudget(IRegexp.MAX_DFA_BYTES);

    private final long maxBytes;

    /**
     * The expressions compiled against this budget and still reachable, so that discarding the automata
     * reaches those that queries hold but do not use; guarded by this budget's lock.
     */
    private final Map<IRegexp, Boolean> live = new WeakHashMap<>();

    /** Estimated bytes held by the automata of the current generation. */
    private final AtomicLong bytes = new AtomicLong();

    /** Generation of the automata; incremented to discard all of them at once. */
    private volatile int generation;

    /**
     * Creates a budget.
     *
     * @param maxBytes the approximate memory, in bytes, that the automata may hold together
     */
    DfaBudget(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** The current generation of the automata. */
    int generation() {
        return generation;
    }

    /** Estimated bytes held by the automata of the current generation. */
    long bytes() {
        return bytes.get();
    }

    /** Registers an expression, so that discarding the automata reaches it. */
    synchronized void register(IRegexp regexp) {
        live.put(regexp, Boolean.TRUE);
    }

    /** Reserves memory for an automaton of a generation; if the budget is full, discards all automata and returns false. */
    boolean reserve(long cost, int generation) {
        if (bytes.addAndGet(cost) > maxBytes) {
            bytes.addAndGet(-cost);
            discard(generation);
            return false;
        }
        return true;
    }

    /** Returns memory reserved by {@link #reserve} unless its generation has been discarded since. */
    void release(long cost, int generation) {
        if (generation == this.generation) {
            bytes.addAndGet(-cost);
        }
    }

    /**
     * Discards all automata unless that already happened since {@code generation} was read. The expressions
     * drop their automata so that the memory can be reclaimed; an automaton in use by a running match stops
     * growing and is collected when that match ends.
     */
    private synchronized void discard(int generation) {
        if (this.generation == generation) {
            this.generation = generation + 1;
            bytes.set(0);
            for (IRegexp regexp : live.keySet()) {
                regexp.dropAutomata();
            }
        }
    }
}

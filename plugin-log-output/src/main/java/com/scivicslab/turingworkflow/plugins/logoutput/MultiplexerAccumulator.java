/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.plugins.logoutput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Multiplexer accumulator that forwards output to multiple downstream accumulators.
 *
 * <p>Architecture:</p>
 * <pre>
 * Node/NodeGroup Actors
 *        |
 *        v
 * MultiplexerAccumulator
 *        |
 *        +--&gt; ConsoleAccumulator --&gt; System.out
 *        +--&gt; FileAccumulator --&gt; text file
 *        +--&gt; DatabaseAccumulator --&gt; H2 database
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class MultiplexerAccumulator implements Accumulator {

    /** The list of downstream accumulator targets. */
    private final List<Accumulator> targets = new ArrayList<>();

    /** Thread-safe counter of entries forwarded by this multiplexer. */
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Constructs an empty {@code MultiplexerAccumulator} with no downstream targets.
     * Use {@link #addTarget(Accumulator)} to register targets.
     */
    public MultiplexerAccumulator() {
    }

    /**
     * Registers a downstream accumulator target. Entries added to this
     * multiplexer will be forwarded to all registered targets.
     *
     * @param target the accumulator to add; {@code null} values are silently ignored
     */
    public void addTarget(Accumulator target) {
        if (target != null) {
            targets.add(target);
        }
    }

    /**
     * Removes a downstream accumulator target.
     *
     * @param target the accumulator to remove
     * @return {@code true} if the target was found and removed
     */
    public boolean removeTarget(Accumulator target) {
        return targets.remove(target);
    }

    /**
     * Returns the number of currently registered downstream targets.
     *
     * @return the target count
     */
    public int getTargetCount() {
        return targets.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the entry to every registered downstream target. If any
     * target throws an exception, a warning is printed to {@code System.err}
     * and the remaining targets are still processed.</p>
     */
    @Override
    public void add(String source, String type, String data) {
        for (Accumulator target : targets) {
            try {
                target.add(source, type, data);
            } catch (Exception e) {
                System.err.println("Warning: Failed to write to accumulator target: " + e.getMessage());
            }
        }
        count.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a multi-line summary including the total forwarded count
     * and the individual summary of each downstream target.</p>
     */
    @Override
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("MultiplexerAccumulator: ").append(count.get()).append(" entries forwarded to ")
          .append(targets.size()).append(" targets\n");
        for (int i = 0; i < targets.size(); i++) {
            sb.append("  Target ").append(i + 1).append(": ").append(targets.get(i).getSummary()).append("\n");
        }
        return sb.toString();
    }

    /** {@inheritDoc} */
    @Override
    public int getCount() {
        return count.get();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resets the multiplexer's own counter and also clears every
     * downstream target.</p>
     */
    @Override
    public void clear() {
        count.set(0);
        for (Accumulator target : targets) {
            target.clear();
        }
    }
}

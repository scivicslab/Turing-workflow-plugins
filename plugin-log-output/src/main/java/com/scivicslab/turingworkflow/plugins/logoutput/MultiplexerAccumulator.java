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
 * Distributes output messages to multiple downstream accumulators, allowing a single workflow action to write to
 * several destinations like the console, a file, or a database simultaneously.
 *
 * <ul>
 * <li>{@code add(source, type, data)} forwards the message to all registered targets.</li>
 * <li>{@code getSummary()} returns a multi-line string containing the total number of forwarded entries and the
 * individual summary of each target.</li>
 * <li>{@code getCount()} returns the total number of entries forwarded by this multiplexer.</li>
 * <li>{@code clear()} resets the multiplexer's counter and clears all downstream targets.</li>
 * <li>{@code addTarget(target)} registers a new downstream accumulator.</li>
 * <li>{@code removeTarget(target)} removes a previously registered target.</li>
 * <li>{@code getTargetCount()} returns the number of currently registered targets.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <pre>{@code
 * mux.addTarget(consoleAccumulator);
 * mux.addTarget(fileAccumulator);
 * mux.add("node-web1", "stdout", "Deploy OK");
 * }</pre>
 *
 * <p>If a target throws an exception during an {@code add} call, the error is printed to {@code System.err} and the
 * remaining targets are still processed.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class MultiplexerAccumulator implements Accumulator {

    /** The list of downstream accumulator targets that receive forwarded output. */
    private final List<Accumulator> targets = new ArrayList<>();

    /** The total number of entries forwarded by this multiplexer. */
    private final AtomicInteger count = new AtomicInteger(0);

    /** Constructs an empty {@code MultiplexerAccumulator} with no downstream targets. */
    public MultiplexerAccumulator() {
    }

    /**
     * Registers a downstream accumulator as a target for output distribution.
     *
     * @param target the accumulator to be added; {@code null} values have no effect
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
     * @return {@code true} if the target was found and removed, {@code false} otherwise
     */
    public boolean removeTarget(Accumulator target) {
        return targets.remove(target);
    }

    /**
     * Returns the number of downstream accumulators currently registered to this multiplexer.
     *
     * @return the number of registered targets
     */
    public int getTargetCount() {
        return targets.size();
    }

    /**
     * Adds the message to all registered downstream targets.
     *
     * <p>If a target throws an exception, a warning is printed to {@code System.err} and the remaining targets are
     * still processed.</p>
     *
     * @param source the name of the source of the message
     * @param type the type of the message, such as "stdout" or "stderr"
     * @param data the actual message text to be recorded
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
     * Returns a multi-line string containing the total number of entries forwarded by this multiplexer and the
     * individual summary of each registered downstream target.
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

    /** Returns the total number of entries forwarded by this multiplexer. */
    @Override
    public int getCount() {
        return count.get();
    }

    /** Resets the multiplexer's own counter and also clears every downstream target. */
    @Override
    public void clear() {
        count.set(0);
        for (Accumulator target : targets) {
            target.clear();
        }
    }
}

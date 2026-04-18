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

package com.scivicslab.turingworkflow.plugins.logdb;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;

/**
 * Accumulator that writes output to an H2 database via DistributedLogStore.
 *
 * <p>This accumulator writes all output to the H2 database asynchronously.
 * It uses a dedicated executor to avoid blocking workflow execution.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class DatabaseAccumulator implements Accumulator {

    /** The actor reference for sending log entries to the distributed log store. */
    private final ActorRef<DistributedLogStore> logStoreActor;
    /** The executor service used for asynchronous database write operations. */
    private final ExecutorService dbExecutor;
    /** The session ID for associating accumulated entries with a workflow execution. */
    private final long sessionId;
    /** The count of entries processed (both written and skipped). */
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Constructs a DatabaseAccumulator.
     *
     * @param logStoreActor the actor reference for the distributed log store
     * @param dbExecutor the executor service for async DB writes
     * @param sessionId the session ID for this workflow execution
     */
    public DatabaseAccumulator(ActorRef<DistributedLogStore> logStoreActor,
                               ExecutorService dbExecutor,
                               long sessionId) {
        this.logStoreActor = logStoreActor;
        this.dbExecutor = dbExecutor;
        this.sessionId = sessionId;
    }

    /**
     * Adds an output entry to the database log store.
     *
     * <p>If the log store actor is not available or the session ID is invalid,
     * the entry is silently skipped but still counted. Null or empty data is
     * also skipped. Writing is fire-and-forget to avoid blocking workflow execution.</p>
     *
     * @param source the source identifier (typically the node ID)
     * @param type   the output type (used as the label in the log entry)
     * @param data   the output data to log, may be {@code null} or empty
     */
    @Override
    public void add(String source, String type, String data) {
        if (logStoreActor == null || sessionId < 0) {
            count.incrementAndGet();
            return;
        }

        if (data == null || data.isEmpty()) {
            count.incrementAndGet();
            return;
        }

        String formattedData = formatOutput(source, data);

        // Fire-and-forget: don't wait for DB write to complete
        logStoreActor.tell(
            store -> store.logAction(sessionId, source, type, "output", 0, 0L, formattedData),
            dbExecutor
        );
        count.incrementAndGet();
    }

    private String formatOutput(String source, String data) {
        String prefix = "[" + (source != null ? source : "") + "] ";
        StringBuilder sb = new StringBuilder();

        String[] lines = data.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(prefix).append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Returns a summary string describing the accumulator state.
     *
     * @return a human-readable summary including the entry count and session ID
     */
    @Override
    public String getSummary() {
        return "DatabaseAccumulator: " + count.get() + " entries written to database (session " + sessionId + ")";
    }

    /**
     * Returns the number of entries processed by this accumulator.
     *
     * @return the total entry count (including skipped entries)
     */
    @Override
    public int getCount() {
        return count.get();
    }

    /**
     * Resets the entry count to zero.
     */
    @Override
    public void clear() {
        count.set(0);
    }
}

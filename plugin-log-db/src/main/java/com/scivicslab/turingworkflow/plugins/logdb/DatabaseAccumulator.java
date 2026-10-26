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
 * Records the output of a workflow into an H2 database by sending log entries to a distributed log store.
 *
 * <p>It performs the following operations:
 * <ul>
 * <li>{@link Accumulator#add(String, String, String)} records a single output entry as an action result in the
 * database.</li>
 * <li>{@link Accumulator#getSummary()} returns a string describing the number of entries processed and the session
 * ID.</li>
 * <li>{@link Accumulator#getCount()} returns the total number of entries processed, including those that were
 * skipped.</li>
 * <li>{@link Accumulator#clear()} resets the processed entry count to zero.</li>
 * </ul>
 *
 * <p>It holds a reference to a log store actor, an executor service for asynchronous writes, and the session ID.
 *
 * <p>One instance is used for a single workflow execution session.
 *
 * <p>If the log store actor is unavailable, the session ID is invalid, or the data is null or empty, the entry is
 * skipped and the count is still incremented.
 *
 * <pre>{@code
 * DatabaseAccumulator dbAcc = new DatabaseAccumulator(logStoreActor, dbExecutor, sessionId);
 * multiplexer.addTarget(dbAcc);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class DatabaseAccumulator implements Accumulator {

    /** The actor reference used to send log entries to the distributed log store. */
    private final ActorRef<DistributedLogStore> logStoreActor;
    /** The executor service used to perform asynchronous database write operations. */
    private final ExecutorService dbExecutor;
    /** The session ID used to associate all accumulated log entries with a specific workflow execution. */
    private final long sessionId;
    /**
     * The total number of entries processed by this accumulator, including those successfully written to the database
     * and those skipped due to invalid data or session issues.
     */
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Initializes a new DatabaseAccumulator that writes output to an H2 database asynchronously.
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
     * Sends an output entry to the H2 database log store.
     *
     * <p>If the log store actor is unavailable, the session ID is invalid, or the data is null or empty, the entry is
     * skipped. The entry is always counted toward the total number of processed entries regardless of whether it was
     * successfully recorded.</p>
     *
     * @param source the identifier of the source, typically a node ID
     * @param type the type of output, used as the label in the log entry
     * @param data the text to be logged
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
     * Returns a string that summarizes the number of entries processed and the associated session ID.
     *
     * @return a string in the form {@code "DatabaseAccumulator: <count> entries written to database (session
     * <sessionId>)"}
     */
    @Override
    public String getSummary() {
        return "DatabaseAccumulator: " + count.get() + " entries written to database (session " + sessionId + ")";
    }

    /**
     * Returns the total number of entries processed by this accumulator.
     *
     * @return the total count of entries, including those that were skipped.
     */
    @Override
    public int getCount() {
        return count.get();
    }

    /** Resets the count of processed entries to zero. */
    @Override
    public void clear() {
        count.set(0);
    }
}

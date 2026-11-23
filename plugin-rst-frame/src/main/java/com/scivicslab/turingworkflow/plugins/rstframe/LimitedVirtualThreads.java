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

package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Runs a batch's items each on a virtual thread of its own, at most {@code parallel} at once.
 *
 * <p>The batches here mostly wait on the LLM writer, so their items run on virtual threads rather
 * than on a pool of real (platform) threads: a pool of real threads outside the ManagedThreadPool
 * is not allowed ({@code RealThreadsOutsideManagedThreadPool_261122_oo01}). A {@link Semaphore}
 * keeps at most {@code parallel} items running, as the fixed pool of {@code parallel} real threads
 * did.</p>
 *
 * <p>It is used the way that pool was: {@link #submit} each item, wait on the futures, then
 * {@link #shutdown}. {@code submit} takes the item's permit on the calling thread before it starts
 * the item's virtual thread, so items start in the order they are submitted, as from the pool's
 * queue; when all permits are taken, {@code submit} waits for one. A failure of an item reaches its
 * future as it did from the pool.</p>
 */
final class LimitedVirtualThreads {

    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    /**
     * @param parallel the most items that run at once; less than 1 is taken as 1, as the pool took it
     */
    LimitedVirtualThreads(int parallel) {
        this.permits = new Semaphore(Math.max(1, parallel));
    }

    /**
     * Starts {@code item} on a virtual thread of its own once fewer than {@code parallel} items run.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits for a permit
     */
    Future<?> submit(Runnable item) throws InterruptedException {
        permits.acquire();
        try {
            return virtualThreads.submit(() -> {
                try {
                    item.run();
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            permits.release();   // not started
            throw e;
        }
    }

    /**
     * Starts {@code item} on a virtual thread of its own once fewer than {@code parallel} items run.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits for a permit
     */
    <T> Future<T> submit(Callable<T> item) throws InterruptedException {
        permits.acquire();
        try {
            return virtualThreads.submit(() -> {
                try {
                    return item.call();
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            permits.release();   // not started
            throw e;
        }
    }

    /** Takes no more items; those started run to their end, as after the pool's {@code shutdown}. */
    void shutdown() {
        virtualThreads.shutdown();
    }
}

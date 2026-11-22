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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends one request and reads its whole response under two limits: a total limit on everything from sending the
 * request to the last byte of the body, and an idle limit that applies only once the first byte of the body has
 * arrived.
 *
 * <p>The idle limit does not start earlier because a non-streaming chat-completions server sends nothing while the
 * request waits in a queue or the model generates; only the total limit covers that time.</p>
 */
final class LimitedExchange {

    private LimitedExchange() {
    }

    /**
     * A complete response.
     *
     * @param status the HTTP status code
     * @param body the whole body, decoded as UTF-8
     */
    record Reply(int status, String body) {
    }

    /**
     * Returns the complete response to {@code request}.
     *
     * @param http the client to send with
     * @param request the request; its own timeout, if any, is not relied on
     * @param totalSeconds seconds the whole exchange may take
     * @param idleSeconds seconds the body may stall once it has begun
     * @param what names the request in a failure message, e.g. the URL and the model
     * @throws IOException if the exchange fails or a limit is hit; the connection is closed first
     * @throws InterruptedException if the waiting thread is interrupted; the exchange is cancelled first
     */
    static Reply send(HttpClient http, HttpRequest request, long totalSeconds, long idleSeconds, String what)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(totalSeconds);
        CompletableFuture<HttpResponse<InputStream>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw totalHit(what, totalSeconds);
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw asIOException(e);
        }

        InputStream in = response.body();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        AtomicLong lastByteAt = new AtomicLong();       // 0 until the first byte of the body arrives
        CompletableFuture<Void> complete = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (body) {
                        body.write(buffer, 0, n);
                    }
                    lastByteAt.set(System.nanoTime());
                }
                complete.complete(null);
            } catch (IOException e) {
                complete.completeExceptionally(e);
            }
        });

        long idleNanos = TimeUnit.SECONDS.toNanos(idleSeconds);
        while (true) {
            long now = System.nanoTime();
            long wait = deadline - now;
            if (wait <= 0) {
                closeQuietly(in);
                throw totalHit(what, totalSeconds);
            }
            long last = lastByteAt.get();
            // before the first byte only the total limit applies, but look again each second so the idle
            // clock is noticed soon after the body begins
            if (last == 0) wait = Math.min(wait, TimeUnit.SECONDS.toNanos(1));
            if (last != 0) {
                long idleLeft = last + idleNanos - now;
                if (idleLeft <= 0) {
                    closeQuietly(in);
                    throw new IOException(what + ": the response body stopped for " + idleSeconds
                            + " s after it began (idle limit)");
                }
                wait = Math.min(wait, idleLeft);
            }
            try {
                complete.get(wait, TimeUnit.NANOSECONDS);
                break;
            } catch (TimeoutException e) {
                // check the limits again
            } catch (InterruptedException e) {
                closeQuietly(in);
                throw e;
            } catch (ExecutionException e) {
                throw asIOException(e);
            }
        }
        synchronized (body) {
            return new Reply(response.statusCode(), body.toString(StandardCharsets.UTF_8));
        }
    }

    private static IOException totalHit(String what, long totalSeconds) {
        return new IOException(what + ": no complete response within " + totalSeconds + " s (total limit)");
    }

    private static IOException asIOException(ExecutionException e) {
        return e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // the connection is being abandoned
        }
    }
}

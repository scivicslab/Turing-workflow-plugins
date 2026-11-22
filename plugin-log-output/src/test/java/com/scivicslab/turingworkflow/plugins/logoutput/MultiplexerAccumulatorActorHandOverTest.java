/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scivicslab.turingworkflow.plugins.logoutput;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Verifies that a log record the multiplexer actor cannot hand over is not lost silently: it is
 * written to {@code System.err}, never logged through JUL, which would re-enter the log path.
 */
@DisplayName("MultiplexerAccumulatorActor — a record that cannot be handed over goes to System.err")
class MultiplexerAccumulatorActorHandOverTest {

    private IIActorSystem system;
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("multiplexer-hand-over-test", 2);
        originalErr = System.err;
        System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
        system.terminateIIActors();
        system.terminate();
    }

    private void awaitErrContains(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!errBytes.toString(StandardCharsets.UTF_8).contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains(text),
                "System.err should contain " + text + ": " + errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("add on a closed multiplexer writes the record to System.err")
    void add_multiplexerClosed_writesRecordToStderr() throws Exception {
        MultiplexerAccumulatorActor actor = new MultiplexerAccumulatorActor("closedMultiplexer",
                new MultiplexerAccumulator(), system);
        actor.close();

        actor.add(new MultiplexerAccumulatorActor.AddArgs("node-01", "stdout", "record-after-close"));

        awaitErrContains("record-after-close");
    }

    @Test
    @DisplayName("add whose accumulator throws writes the record to System.err")
    void add_accumulatorThrows_writesRecordToStderr() throws Exception {
        MultiplexerAccumulator failing = new MultiplexerAccumulator() {
            @Override
            public void add(String source, String type, String data) {
                throw new IllegalStateException("target refused");
            }
        };
        MultiplexerAccumulatorActor actor = new MultiplexerAccumulatorActor("failingMultiplexer", failing, system);

        actor.add(new MultiplexerAccumulatorActor.AddArgs("node-01", "stdout", "record-that-failed"));

        awaitErrContains("record-that-failed");
        actor.close();
    }
}

package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * FileGate.of must not turn the sandbox off when a {@code files} actor exists but cannot be asked
 * whether it is a gate.
 */
@Tag("PluginRstFrame_FileGate_261026_oo01")
@DisplayName("FileGate.of - a files actor that cannot be asked refuses instead of opening")
class FileGateLookupTest {

    /** A files actor whose mailbox answers every ask with the given future. */
    static class UnaskableFiles extends IIActorRef<Object> {
        private final CompletableFuture<Object> answer;

        UnaskableFiles(IIActorSystem system, CompletableFuture<Object> answer) {
            super(FileGate.ACTOR, new Object(), system);
            this.answer = answer;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> CompletableFuture<R> ask(Function<Object, R> action) {
            return (CompletableFuture<R>) answer;
        }
    }

    private IIActorSystem system;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("file-gate-lookup-test");
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        system.terminate();
    }

    @Test
    void of_filesActorAskFails_throwsSecurityException() {
        system.addIIActor(new UnaskableFiles(system,
                CompletableFuture.failedFuture(new IllegalStateException("mailbox closed"))));

        assertThrows(SecurityException.class, () -> FileGate.of(system),
                "a files actor that cannot be asked must not yield an open gate");
    }

    @Test
    void of_interruptedWhileAskingFilesActor_throwsSecurityExceptionAndKeepsInterrupt() {
        system.addIIActor(new UnaskableFiles(system, new CompletableFuture<>()));

        Thread.currentThread().interrupt();
        assertThrows(SecurityException.class, () -> FileGate.of(system),
                "an interrupted lookup of the files actor must not yield an open gate");
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
    }

    @Test
    void of_noFilesActor_returnsOpenGate(@TempDir Path dir) {
        FileGate gate = FileGate.of(system);

        assertFalse(gate.exists(dir.resolve("missing")));
        assertTrue(gate.exists(dir));
    }
}

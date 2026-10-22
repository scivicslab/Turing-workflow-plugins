package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The way this plugin's actors read and write files. A program that runs workflows written by others (an LLM, for one)
 * limits what they may touch: it registers an actor named {@value #ACTOR} whose object is a {@code FileGate} that
 * refuses what lies outside its range, and every actor of this plugin goes through it. Without such an actor (the
 * plugin used from the command line) the gate is {@link #open()}, which refuses nothing.
 */
public interface FileGate {

    /** The name of the actor a program registers its gate under. */
    String ACTOR = "files";

    /** @return the file's text; refused ({@link SecurityException}) when outside what may be read */
    String read(Path file) throws IOException;

    /** Writes the text, creating the directories above it; refused when outside what may be written. */
    void write(Path file, String text) throws IOException;

    /**
     * @param suffix what a name must end with; every file when empty
     * @param depth  how many levels to walk, 1 for the directory alone
     * @return the regular files under {@code dir}, in path order; refused when {@code dir} is outside what may be read
     */
    List<Path> list(Path dir, String suffix, int depth) throws IOException;

    /** Refuses ({@link SecurityException}) when {@code file} is outside what may be written, for a file written by other means (a database). */
    void mayWrite(Path file);

    /** @return whether a file or directory is at {@code path}; refused when it is outside what may be read or written */
    boolean exists(Path path);

    /** @return a gate that refuses nothing */
    static FileGate open() {
        return new FileGate() {
            @Override
            public String read(Path file) throws IOException {
                return Files.readString(file);
            }

            @Override
            public void write(Path file, String text) throws IOException {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, text);
            }

            @Override
            public List<Path> list(Path dir, String suffix, int depth) throws IOException {
                List<Path> out = new ArrayList<>();
                try (var walk = Files.walk(dir, Math.max(1, depth))) {
                    walk.filter(Files::isRegularFile).filter(p -> suffix.isEmpty() || p.getFileName().toString().endsWith(suffix))
                        .sorted().forEach(out::add);
                }
                return out;
            }

            @Override
            public void mayWrite(Path file) {
            }

            @Override
            public boolean exists(Path path) {
                return Files.exists(path);
            }
        };
    }

    /**
     * @return the gate of the actor named {@value #ACTOR} in {@code system}, reached through that actor's mailbox; or
     *         {@link #open()} when there is no such actor, or its object is not a gate
     */
    @SuppressWarnings("unchecked")
    static FileGate of(IIActorSystem system) {
        IIActorRef<Object> ref = system == null ? null : (IIActorRef<Object>) system.getIIActor(ACTOR);
        if (ref == null) return open();
        boolean isGate;
        try {
            isGate = ref.ask(o -> o instanceof FileGate).get();
        } catch (InterruptedException | ExecutionException e) {
            isGate = false;
        }
        if (!isGate) return open();
        return new FileGate() {
            @Override
            public String read(Path file) throws IOException {
                return call(ref, g -> g.read(file));
            }

            @Override
            public void write(Path file, String text) throws IOException {
                call(ref, g -> {
                    g.write(file, text);
                    return null;
                });
            }

            @Override
            public List<Path> list(Path dir, String suffix, int depth) throws IOException {
                return call(ref, g -> g.list(dir, suffix, depth));
            }

            @Override
            public void mayWrite(Path file) {
                try {
                    call(ref, g -> {
                        g.mayWrite(file);
                        return null;
                    });
                } catch (IOException e) {
                    throw new SecurityException(e.getMessage(), e);
                }
            }

            @Override
            public boolean exists(Path path) {
                try {
                    return call(ref, g -> g.exists(path));
                } catch (IOException e) {
                    throw new SecurityException(e.getMessage(), e);
                }
            }
        };
    }

    /** One call on the gate behind an actor; its {@link IOException} and {@link SecurityException} come back as they were thrown. */
    private static <R> R call(IIActorRef<Object> ref, GateCall<R> c) throws IOException {
        try {
            return ref.ask(o -> {
                try {
                    return c.on((FileGate) o);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
            if (t instanceof java.io.UncheckedIOException u) throw u.getCause();
            if (t instanceof SecurityException s) throw s;
            if (t instanceof RuntimeException r) throw r;
            throw new IOException(t);
        }
    }

    /** One call on a gate. */
    interface GateCall<R> {
        R on(FileGate gate) throws IOException;
    }
}

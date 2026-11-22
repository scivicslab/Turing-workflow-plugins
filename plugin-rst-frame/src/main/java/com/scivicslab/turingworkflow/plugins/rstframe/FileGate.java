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
 * Controls file access for the plugin's actors by restricting them to a specific directory range. A workflow can
 * register an actor named {@value #ACTOR} that uses a {@code FileGate} to enforce these boundaries, ensuring all other
 * plugin actors stay within the allowed scope.
 *
 * <ul>
 * <li>{@code read(Path file)} returns the file's text; throws a {@link SecurityException} if the file is outside the
 * allowed range.</li>
 * <li>{@code write(Path file, String text)} writes the text to the file and creates any necessary parent directories;
 * throws a {@link SecurityException} if the file is outside the allowed range.</li>
 * <li>{@code list(Path dir, String suffix, int depth)} returns a list of regular files under {@code dir} in path
 * order, filtered by {@code suffix} and limited by {@code depth}; throws a {@link SecurityException} if {@code dir} is
 * outside the allowed range.</li>
 * <li>{@code mayWrite(Path file)} marks a file as safe for writing, such as one created by a database, to prevent a
 * {@link SecurityException} when it is later accessed; throws a {@link SecurityException} if the file is outside the
 * allowed range.</li>
 * <li>{@code exists(Path path)} returns whether a file or directory exists at {@code path}; throws a {@link
 * SecurityException} if the path is outside the allowed range.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public interface FileGate {

    /** The name used to look up the file-access actor in an {@link IActorSystem}. */
    String ACTOR = "files";

    /**
     * Returns the text of the file at {@code file}; throws a {@link SecurityException} if the file is outside the
     * allowed range.
     */
    String read(Path file) throws IOException;

    /**
     * Writes the given text to the file, creating any missing parent directories.
     *
     * @param file the path of the file to be written
     * @param text the text to write to the file
     * @throws IOException if an I/O error occurs
     */
    void write(Path file, String text) throws IOException;

    /**
     * Returns a list of regular files under {@code dir} that end with {@code suffix} and are within {@code depth}
     * levels of the directory.
     *
     * @param dir the directory to start the search in
     * @param suffix the required ending for each file name; an empty string matches all files
     * @param depth the maximum number of levels to walk, where 1 returns only the files in {@code dir} itself
     * @return a list of paths to the matching files, sorted in path order; a {@link SecurityException} is thrown if
     * {@code dir} is outside what may be read
     */
    List<Path> list(Path dir, String suffix, int depth) throws IOException;

    /**
     * Signals that a file was written by a means other than this gate, such as a database, to prevent the gate from
     * refusing the path as an unauthorized write.
     *
     * @param file the path of the file written by other means
     */
    void mayWrite(Path file);

    /**
     * Returns whether a file or directory is located at {@code path}. Refuses with a {@link SecurityException} if
     * {@code path} is outside the range of what may be read or written.
     */
    boolean exists(Path path);

    /** Returns a gate that refuses nothing. */
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
     * Returns the gate of the actor named {@value #ACTOR} in {@code system}, reached through that actor's mailbox.
     *
     * If the actor named {@value #ACTOR} is not registered in {@code system}, or if its object is not a {@link
     * FileGate}, returns a gate that refuses nothing via {@link #open()}.
     *
     * <p>If the actor is registered but cannot be asked whether its object is a {@link FileGate}, throws a {@link
     * SecurityException}: a gate that was configured must not silently turn into one that allows everything.</p>
     *
     * @param system the actor system to search for the {@value #ACTOR} actor
     * @throws SecurityException if the {@value #ACTOR} actor exists but the check fails or is interrupted; the
     *         interrupt flag is kept
     */
    @SuppressWarnings("unchecked")
    static FileGate of(IIActorSystem system) {
        IIActorRef<Object> ref = system == null ? null : (IIActorRef<Object>) system.getIIActor(ACTOR);
        if (ref == null) return open();
        boolean isGate;
        try {
            isGate = ref.ask(o -> o instanceof FileGate).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecurityException("interrupted while asking the '" + ACTOR + "' actor whether it is a FileGate", e);
        } catch (ExecutionException e) {
            throw new SecurityException("cannot ask the '" + ACTOR + "' actor whether it is a FileGate: " + e.getCause(), e);
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

    /**
     * Executes a single operation on the actor representing the gate.
     *
     * @param ref the actor reference to which the operation is sent
     * @param c the operation to perform on the gate
     * @return the result of the operation
     * @throws IOException if the operation fails due to an I/O error or if the thread is interrupted
     */
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

    /** A function that performs a single operation on a {@link FileGate}. */
    interface GateCall<R> {
        R on(FileGate gate) throws IOException;
    }
}

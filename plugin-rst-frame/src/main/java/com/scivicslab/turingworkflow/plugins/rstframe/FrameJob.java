package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transforms a single sample and its associated code files into a frame.
 *
 * <ul>
 * <li>{@code open(samples, name)}: Opens the sample {@code <samples>/<name>.lisp} and its corresponding {@code
 * <name>.<block>.txt} files, creating a {@link SampleToFrame} and returning a success message containing the path to
 * the lisp file and the number of code files loaded.</li>
 * <li>{@code write(dir)}: Writes the current frame to {@code <dir>/<name>.lisp} as a new file, returning the absolute
 * path to the written file.</li>
 * </ul>
 *
 * <p>Holds a single {@link SampleToFrame} instance between calls to {@code open} and {@code write}.
 *
 * <p>One instance may serve several workflows or threads at once.
 *
 * <p>Fails with an {@link IllegalStateException} if {@code write} is called before {@code open} has successfully
 * loaded a sample.
 */
public final class FrameJob extends QuestionWork {

    private SampleToFrame frame;

    public FrameJob(FileGate files) {
        super(files);
    }

    /**
     * Opens a sample file and its associated code files to create a frame.
     *
     * Returns a success message containing the path to the opened {@code .lisp} file and the number of code files
     * found.
     *
     * If the name is blank, throws an {@link IllegalArgumentException}.
     *
     * <pre>{@code
     * open("samples", "secure-model")
     * }</pre>
     *
     * @param samples the directory containing the samples
     * @param name the name of the sample to open, used to find {@code <name>.lisp} and {@code <name>.<block>.txt}
     * files
     *
     * @return a message stating the path of the opened file and the count of associated code files
     * @throws IOException if an error occurs while reading the files
     */
    public String open(String samples, String name) throws IOException {
        if (blank(name)) throw new IllegalArgumentException("the sample's name is required");
        Path dir = path(samples, "the samples' directory");
        Path f = dir.resolve(name.strip() + ".lisp");
        String text = files.read(f);
        Map<String, String> code = new TreeMap<>();
        String prefix = name.strip() + ".";
        for (Path c : files.list(dir, ".txt", 1)) {
            String n = c.getFileName().toString();
            if (n.startsWith(prefix)) code.put(n, files.read(c));
        }
        frame = new SampleToFrame(text, name.strip(), code);
        asking(frame);
        return "opened " + f + " with " + code.size() + " code file(s)";
    }

    /**
     * Writes the current frame to a new file in the specified directory.
     *
     * Returns the absolute path of the newly created {@code <dir>/<name>.lisp} file.
     *
     * If no sample has been opened via {@code open}, the action fails with an error.
     *
     * <pre>{@code
     * write("output/frames")
     * }</pre>
     *
     * @param dir the directory where the file is to be written
     * @return the absolute path of the written file
     * @throws IOException if an I/O error occurs during writing
     */
    public String write(String dir) throws IOException {
        if (frame == null) throw new IllegalStateException("no sample is open");
        Path f = path(dir, "the frames' directory").resolve(frame.name() + ".lisp");
        writeNew(f, frame.frameFile(), "a frame");
        return f.toString();
    }
}

package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The work of {@code make-frame.yaml} ({@code FramesAndSamplesFromBooks_261021_oo01}): one sample, with its code files,
 * made into a frame ({@link SampleToFrame}).
 */
public final class FrameJob extends QuestionWork {

    private SampleToFrame frame;

    public FrameJob(FileGate files) {
        super(files);
    }

    /** Opens the sample {@code <samples>/<name>.lisp} with its code files ({@code <name>.<block>.txt}). */
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

    /** Writes the frame as {@code <dir>/<name>.lisp}, never over a file there; the message is its path. */
    public String write(String dir) throws IOException {
        if (frame == null) throw new IllegalStateException("no sample is open");
        Path f = path(dir, "the frames' directory").resolve(frame.name() + ".lisp");
        writeNew(f, frame.frameFile(), "a frame");
        return f.toString();
    }
}

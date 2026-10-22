package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The work of {@code concept-consolidate.yaml}: a tree of concept classes made smaller ({@link ConceptConsolidation})
 * and written, standing alone, to one file.
 */
public final class ConsolidationJob extends QuestionWork implements AutoCloseable {

    private ConceptClasses classes;
    private ConceptConsolidation consolidation;

    public ConsolidationJob(FileGate files) {
        super(files);
    }

    /**
     * @param classesDir the directory of class files; blank for none
     * @param input      the result of step 3, read on top of them; blank for none
     */
    public String open(String classesDir, String input) throws IOException {
        Map<String, String> read = new LinkedHashMap<>();
        if (!blank(classesDir)) read.putAll(lispFiles(path(classesDir, "the classes' directory"), Integer.MAX_VALUE));
        String result = "";
        if (!blank(input)) {
            Path in = path(input, "the result of step 3");
            result = files.read(in);
            read.put(in.toString(), result);
        }
        if (read.isEmpty()) throw new IllegalStateException("no class file and no result of step 3 to make smaller");
        close();
        classes = new ConceptClasses(read);
        int before = classes.names().size();
        consolidation = new ConceptConsolidation(classes, result);
        asking(consolidation);
        return "opened " + before + " class(es) from " + read.size() + " file(s)";
    }

    /** Writes the smaller tree, never over a file already there; the message is its path. */
    public String write(String out, String input) throws IOException {
        if (consolidation == null) throw new IllegalStateException("no tree is being made smaller");
        Path f = path(out, "the output file");
        writeNew(f, consolidation.resultFile(blank(input) ? "the class files" : input.strip()), "the smaller tree");
        return f.toString();
    }

    @Override
    public void close() {
        if (classes != null) classes.close();
        classes = null;
    }
}

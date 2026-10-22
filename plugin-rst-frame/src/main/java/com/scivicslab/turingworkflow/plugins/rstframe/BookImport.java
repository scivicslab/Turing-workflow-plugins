package com.scivicslab.turingworkflow.plugins.rstframe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the steps that read a book's structure into the H2 database ({@code FrameOntology_261021_oo01}). No LLM is used.
 *
 * <pre>java ... BookImport toc &lt;db-file&gt; &lt;book-name&gt; &lt;book-dir&gt;</pre>
 * {@code toc}: reads the table of contents from the headings of every markdown file under the book's directory, in path
 * order, and replaces the book's rows of {@code toc_node}.
 */
public final class BookImport {

    private BookImport() {
    }

    public static void main(String[] a) throws Exception {
        if (a.length != 4 || !a[0].equals("toc")) {
            System.err.println("usage: BookImport toc <db-file> <book-name> <book-dir>");
            System.exit(2);
        }
        Path dir = Path.of(a[3]).toAbsolutePath().normalize();
        Map<String, String> files = new LinkedHashMap<>();
        try (var s = Files.walk(dir)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) files.put(dir.relativize(p).toString(), Files.readString(p));
        }
        BookOutline.Outline o = BookOutline.read(files);
        try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
            store.saveOutline(a[2], dir.toString(), o);
        }
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (BookOutline.Node n : o.nodes()) byLevel.merge(n.level(), 1, Integer::sum);
        System.out.println("read " + files.size() + " markdown file(s); " + byLevel);
        for (String n : o.notes()) System.out.println("note: " + n);
    }
}

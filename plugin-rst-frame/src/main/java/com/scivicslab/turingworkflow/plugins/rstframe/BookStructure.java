package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a book's structure into the H2 database of the intermediate data ({@code FrameOntology_261021_oo01}, getting
 * the concept hierarchy): its table of contents ({@link BookOutline}), then its text cut into those nodes
 * ({@link BookSections}). No LLM is used. Each call opens the database and closes it again; nothing is kept between calls.
 */
public final class BookStructure {

    /**
     * Reads the table of contents of the book under {@code dir} from the headings of its markdown files and stores it
     * under {@code name}, replacing what the book had.
     *
     * @param db   the H2 database file, without {@code .mv.db}
     * @return what was read, in one line
     */
    public String readToc(String db, String name, String dir) throws Exception {
        Path root = Path.of(dir).toAbsolutePath().toRealPath();
        Map<String, String> files = markdown(root);
        if (files.isEmpty()) throw new IllegalArgumentException("no markdown file under " + root);
        BookOutline.Outline outline = BookOutline.read(files);
        Path dbFile = Path.of(db).toAbsolutePath().normalize();
        if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
        try (BookStore store = new BookStore(url(dbFile))) {
            store.saveOutline(name, root.toString(), outline);
        }
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (BookOutline.Node n : outline.nodes()) byLevel.merge(n.level(), 1, Integer::sum);
        return "read " + files.size() + " markdown file(s) of " + name + ": " + byLevel
                + (outline.notes().isEmpty() ? "" : "; " + String.join("; ", outline.notes()));
    }

    /**
     * Cuts the text of the book stored under {@code name} into the nodes of the table of contents {@link #readToc}
     * stored, and stores the sections, replacing what the book had.
     *
     * @return what was cut, in one line
     */
    public String cutSections(String db, String name) throws Exception {
        try (BookStore store = new BookStore(url(Path.of(db).toAbsolutePath().normalize()))) {
            String dir = store.dirOf(name);
            if (dir == null) throw new IllegalArgumentException("no book " + name + " in " + db + "; read its table of contents first");
            BookSections.Result result = BookSections.split(markdown(Path.of(dir)), store.outline(name));
            store.saveSections(name, result.sections());
            long empty = result.sections().stream().filter(x -> x.text().isEmpty()).count();
            long spanning = result.sections().stream().filter(x -> x.files().size() > 1).count();
            return result.sections().size() + " section(s) of " + name + "; " + empty + " with no text of their own; "
                    + spanning + " spanning more than one file" + (result.notes().isEmpty() ? "" : "; " + String.join("; ", result.notes()));
        }
    }

    /** @return every markdown file under {@code root}, path under it to text, in path order */
    static Map<String, String> markdown(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".md")).sorted().toList()) files.put(root.relativize(p).toString(), Files.readString(p));
        }
        return files;
    }

    private static String url(Path dbFile) {
        return "jdbc:h2:" + dbFile + ";AUTO_SERVER=TRUE";
    }
}

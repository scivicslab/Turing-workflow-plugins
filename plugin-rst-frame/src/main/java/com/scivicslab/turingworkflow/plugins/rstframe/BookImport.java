package com.scivicslab.turingworkflow.plugins.rstframe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the steps that read a book's structure into the H2 database ({@code FrameOntology_261021_oo01}). No LLM is used.
 *
 * <pre>java ... BookImport toc &lt;db-file&gt; &lt;book-name&gt; &lt;book-dir&gt;
 * java ... BookImport sections &lt;db-file&gt; &lt;book-name&gt;
 * java ... BookImport rules &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport concepts &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport judge &lt;db-file&gt; &lt;book-name&gt;
 * java ... BookImport relations &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport review &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport relations-of-concepts &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport properties &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport causes &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport groups &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport patterns &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport items &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport identify|merge|generalize &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [id...]
 * java ... BookImport collect &lt;db-file&gt; &lt;book-name&gt; &lt;chat-url&gt; &lt;model&gt; &lt;parallel&gt; [section-number...]
 * java ... BookImport prune &lt;db-file&gt; &lt;book-name&gt; &lt;min-rules&gt;</pre>
 * {@code toc}: reads the table of contents from the headings of every markdown file under the book's directory, in path
 * order, and replaces the book's rows of {@code toc_node}. {@code sections}: cuts the book's text into the nodes
 * {@code toc} stored, and replaces the book's rows of {@code section_text}. {@code rules}: makes each section without a
 * rule file (or each section named) into an rst-on-lisp rule file, asking the model at the chat endpoint. {@code concepts}:
 * lists the concepts each section without a list (or each section named) explains, one question per section.
 * {@code judge}: judges which listed concepts to keep, by each section's rst-on-lisp structure ({@link ConceptImportance}).
 * {@code relations}: asks how the kept concepts of each section relate: is-a, part-of, feature ({@link SectionRelations}).
 */
public final class BookImport {

    private BookImport() {
    }

    public static void main(String[] a) throws Exception {
        if (a.length == 3 && a[0].equals("sections")) {
            sections(a[1], a[2]);
            return;
        }
        if (a.length == 3 && a[0].equals("judge")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                System.out.println(ConceptImportance.judgeAll(store, a[2]));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("concepts")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionConcepts.listAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 400), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("relations")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionRelations.relateAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 600), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("collect")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(FrameMaterial.collectAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 800), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length == 4 && a[0].equals("prune")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                System.out.println(FrameMaterial.pruneAll(store, a[2], Integer.parseInt(a[3])));
            }
            return;
        }
        if (a.length >= 6 && (a[0].equals("place") || a[0].equals("relate") || a[0].equals("frame-items") || a[0].equals("frame-names"))) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                java.util.function.Consumer<String> log = line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line);
                int parallel = Integer.parseInt(a[5]);
                System.out.println(switch (a[0]) {
                    case "place" -> FrameRegistry.placeAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 300), 1, log);
                    case "relate" -> FrameRegistry.relateAll(store, a[2], only, null, 1, log);
                    case "frame-names" -> FrameRegistry.nameAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 100), parallel, log);
                    default -> FrameRegistry.itemsAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2500), parallel, log);
                });
            }
            return;
        }
        if (a.length >= 6 && (a[0].equals("identify") || a[0].equals("merge") || a[0].equals("generalize"))) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                java.util.function.Consumer<String> log = line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line);
                int parallel = Integer.parseInt(a[5]);
                System.out.println(switch (a[0]) {
                    case "identify" -> GeneralFrames.identifyAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 400), parallel, log);
                    case "merge" -> GeneralFrames.mergeAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2500), parallel, log);
                    default -> GeneralFrames.generalizeAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2500), parallel, log);
                });
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("items")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(FrameItems.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 1000), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("relations-of-concepts")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(ConceptRelations.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2000), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("properties")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(ConceptProperties.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 1200), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("causes")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(ConceptCauses.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 1200), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("groups")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionGroups.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 800), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("patterns")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                // worked examples of the cases that are easy to get wrong: -Drstframe.patternExamples=<file>
                String ex = System.getProperty("rstframe.patternExamples", "");
                System.out.println(SectionPattern.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 600), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line),
                        ex.isBlank() ? "" : Files.readString(Path.of(ex))));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("review")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionReview.reviewAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 400), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("rules")) {
            try (BookStore store = new BookStore("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionTagging.tagAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2000), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length != 4 || !a[0].equals("toc")) {
            System.err.println("usage: BookImport toc <db-file> <book-name> <book-dir>  |  BookImport sections <db-file> <book-name>");
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

    private static void sections(String dbFile, String book) throws Exception {
        try (BookStore store = new BookStore("jdbc:h2:" + Path.of(dbFile).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
            String d = store.dirOf(book);
            if (d == null) throw new IllegalArgumentException("no book " + book + "; run toc first");
            Path dir = Path.of(d);
            Map<String, String> files = new LinkedHashMap<>();
            try (var s = Files.walk(dir)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) files.put(dir.relativize(p).toString(), Files.readString(p));
            }
            BookSections.Result r = BookSections.split(files, store.outline(book));
            store.saveSections(book, r.sections());
            long empty = r.sections().stream().filter(x -> x.text().isEmpty()).count();
            long spanning = r.sections().stream().filter(x -> x.files().size() > 1).count();
            System.out.println(r.sections().size() + " section(s); " + empty + " with no text of their own; " + spanning + " spanning more than one file");
            for (String n : r.notes()) System.out.println("note: " + n);
        }
    }
}

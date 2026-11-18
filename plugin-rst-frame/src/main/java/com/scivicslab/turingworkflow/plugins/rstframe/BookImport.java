package com.scivicslab.turingworkflow.plugins.rstframe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Populates an H2 database with the structural and conceptual data extracted from a book's markdown files.
 *
 * <ul>
 * <li>{@code toc} reads the table of contents from the headings of every markdown file in the book's directory and
 * replaces the rows of {@code toc_node}.</li>
 * <li>{@code toc-llm} reads the table of contents using a local LLM and replaces the rows of {@code toc_node}.</li>
 * <li>{@code sections} cuts the book's text into the nodes stored by {@code toc} and replaces the rows of {@code
 * section_text}.</li>
 * <li>{@code rules} generates rst-on-lisp rule files for each section using a local LLM.</li>
 * <li>{@code concepts} identifies the concepts explained in each section using a local LLM.</li>
 * <li>{@code judge} selects which identified concepts to keep based on the section's rst-on-lisp structure.</li>
 * <li>{@code relations} identifies how the kept concepts relate to one another (e.g., is-a, part-of) using a local
 * LLM.</li>
 * <li>{@code similar} finds concepts in other sections that are similar to the target concepts using embeddings and a
 * local LLM.</li>
 * <li>{@code relations-of-concepts} extracts the relationships between concepts using a local LLM.</li>
 * <li>{@code properties} extracts the properties (what a concept is, has, or does) of each concept using a local
 * LLM.</li>
 * <li>{@code causes} extracts the causal and motivational relationships between concepts using a local LLM.</li>
 * <li>{@code groups} extracts the way concepts are grouped into units of explanation using a local LLM.</li>
 * <li>{@code patterns} identifies the patterns of explanation used in the text using a local LLM.</li>
 * <li>{@code items} extracts the specific components of a concept (e.g., elements of a set, parts of a structure)
 * using a local LLM.</li>
 * <li>{@code identify}, {@code merge}, or {@code generalize} processes the identified concepts into generalized frames
 * using a local LLM.</li>
 * <li>{@code collect} gathers the materials required for the generalized frames using a local LLM.</li>
 * <li>{@code prune} removes concepts from the database that do not meet a minimum rule count.</li>
 * <li>{@code migrate} converts a database from before the schema versions to the current one, after backing it up
 * ({@link BookMigration}).</li>
 * </ul>
 *
 * <pre>{@code
 * BookImport migrate <db-file>
 * BookImport toc <db-file> <book-name> <book-dir>
 * BookImport rules <db-file> <book-name> <chat-url> <model> <parallel> [section-number...]
 * BookImport similar <db-file> <chat-url> <model> <parallel> <embed-url> <embed-model> [section-number...]
 * }</pre>
 */
public final class BookImport {

    private BookImport() {
    }

    public static void main(String[] a) throws Exception {
        if (a.length == 2 && a[0].equals("migrate")) {
            // a database from before the schema versions: backed up, converted, checked, compacted (BookMigration)
            BookMigration.migrate(Path.of(a[1]).toAbsolutePath(), System.out);
            return;
        }
        if (a.length == 3 && a[0].equals("sections")) {
            sections(a[1], a[2]);
            return;
        }
        if (a.length == 3 && a[0].equals("judge")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                System.out.println(ConceptImportance.judgeAll(store, a[2]));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("concepts")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionConcepts.listAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 400), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("relations")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionRelations.relateAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 600), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("collect")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(FrameMaterial.collectAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 800), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length == 4 && a[0].equals("prune")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                System.out.println(FrameMaterial.pruneAll(store, a[2], Integer.parseInt(a[3])));
            }
            return;
        }
        if (a.length >= 6 && (a[0].equals("place") || a[0].equals("relate") || a[0].equals("frame-items") || a[0].equals("frame-names"))) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
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
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
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
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(FrameItems.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 1000), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 7 && a[0].equals("similar")) {
            // similar <db> <chat-url> <model> <parallel> <embed-url> <embed-model> [section-number...]
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(7, a.length);
                System.out.println(SimilarConcepts.askAll(store, a[5], a[6], only, new ChatEndpoint(a[2], a[3], 400), Integer.parseInt(a[4]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("relations-of-concepts")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(ConceptRelations.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2000), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("properties")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(ConceptProperties.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 1200), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("causes")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(ConceptCauses.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 1200), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("groups")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionGroups.askAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 800), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("patterns")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
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
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionReview.reviewAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 400), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length >= 6 && a[0].equals("rules")) {
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                java.util.List<String> only = java.util.Arrays.asList(a).subList(6, a.length);
                System.out.println(SectionTagging.tagAll(store, a[2], only, new ChatEndpoint(a[3], a[4], 2000), Integer.parseInt(a[5]),
                        line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            }
            return;
        }
        if (a.length == 6 && a[0].equals("toc-llm")) {
            // toc-llm <db> <book> <book-dir> <chat-url> <model>: the table of contents read by the model
            Path dir = Path.of(a[3]).toAbsolutePath().normalize();
            Map<String, String> files = new LinkedHashMap<>();
            try (var s = Files.walk(dir)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) files.put(dir.relativize(p).toString(), Files.readString(p));
            }
            BookOutline.Outline o = BookOutline.withDirectoryChapters(files, OutlineByModel.read(a[2], files, new ChatEndpoint(a[4], a[5], 1500),
                    line -> System.out.println(java.time.LocalTime.now().withNano(0) + " " + line)));
            try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
                store.saveOutline(a[2], dir.toString(), o);
            }
            Map<String, Integer> byLevel = new LinkedHashMap<>();
            for (BookOutline.Node n : o.nodes()) byLevel.merge(n.level(), 1, Integer::sum);
            System.out.println("read " + files.size() + " markdown file(s) by the model; " + byLevel);
            for (String n : o.notes().stream().limit(10).toList()) System.out.println("note: " + n);
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
        BookOutline.Outline o = BookOutline.withDirectoryChapters(files, BookOutline.read(files));
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(a[1]).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
            store.saveOutline(a[2], dir.toString(), o);
        }
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (BookOutline.Node n : o.nodes()) byLevel.merge(n.level(), 1, Integer::sum);
        System.out.println("read " + files.size() + " markdown file(s); " + byLevel);
        for (String n : o.notes()) System.out.println("note: " + n);
    }

    private static void sections(String dbFile, String book) throws Exception {
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + Path.of(dbFile).toAbsolutePath() + ";AUTO_SERVER=TRUE")) {
            String d = store.dirOf(book);
            if (d == null) throw new IllegalArgumentException("no book " + book + "; run toc first");
            Path dir = Path.of(d);
            Map<String, String> files = new LinkedHashMap<>();
            try (var s = Files.walk(dir)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".md")).sorted().toList()) files.put(dir.relativize(p).toString(), Files.readString(p));
            }
            BookSections.Result r = BookStructure.cutAndStore(store, book, d, files);
            long empty = r.sections().stream().filter(x -> x.text().isEmpty()).count();
            long spanning = r.sections().stream().filter(x -> x.files().size() > 1).count();
            System.out.println(r.sections().size() + " section(s); " + empty + " with no text of their own; " + spanning + " spanning more than one file");
            for (String n : r.notes()) System.out.println("note: " + n);
        }
    }
}

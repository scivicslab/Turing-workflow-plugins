package com.scivicslab.turingworkflow.plugins.rstframe;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The intermediate data of the steps that make frames from books, in one H2 database
 * ({@code FrameOntology_261021_oo01}): the steps leave many kinds of intermediate data, too many to keep as files.
 *
 * <p>{@code book}: a book's name and directory. {@code toc_node}: its table of contents, one row per part, chapter,
 * section or subsection, in the book's order. {@code section_text}: the book's text cut into those nodes, each with
 * the markdown files it was read from. {@code section_rules}: each section's text as an rst-on-lisp rule file, with
 * {@code section_code} the files its code blocks name. {@code section_concept}: the concepts each section explains.</p>
 */
public final class BookStore implements AutoCloseable {

    private final Connection db;

    /** @param url a JDBC URL, e.g. {@code jdbc:h2:/home/u/works/rst-on-lisp/db/books;AUTO_SERVER=TRUE} */
    public BookStore(String url) throws SQLException {
        this.db = DriverManager.getConnection(url, "sa", "");
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS book (name VARCHAR(200) PRIMARY KEY, dir VARCHAR(2000) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS toc_node (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, level VARCHAR(20) NOT NULL, title VARCHAR(2000) NOT NULL, parent VARCHAR(40), "
                    + "first_file VARCHAR(2000) NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_text (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "text CLOB NOT NULL, files CLOB NOT NULL, chars INT NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_rules (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "rules CLOB NOT NULL, questions INT NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_code (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "name VARCHAR(200) NOT NULL, text CLOB NOT NULL, PRIMARY KEY (book, number, name))");
            s.execute("CREATE TABLE IF NOT EXISTS section_concept (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, concept VARCHAR(1000) NOT NULL, PRIMARY KEY (book, number, ordinal))");
            // step 5: whether a concept is kept, and why; null until judged
            s.execute("ALTER TABLE section_concept ADD COLUMN IF NOT EXISTS kept BOOLEAN");
            s.execute("ALTER TABLE section_concept ADD COLUMN IF NOT EXISTS reason VARCHAR(1000)");
            // step 4: the numbers of the sentences that explain the concept, comma separated; null when listed without them
            s.execute("ALTER TABLE section_concept ADD COLUMN IF NOT EXISTS sentences VARCHAR(1000)");
            // step 6: how the concepts of one section relate: is-a, part-of, feature
            s.execute("CREATE TABLE IF NOT EXISTS section_relation (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, kind VARCHAR(20) NOT NULL, child VARCHAR(1000) NOT NULL, parent VARCHAR(1000) NOT NULL, "
                    + "sentences VARCHAR(1000), PRIMARY KEY (book, number, ordinal))");
            // step 7: what the review made of each relation: keep, is-a, part-of, feature, reverse or drop; null until reviewed
            s.execute("ALTER TABLE section_relation ADD COLUMN IF NOT EXISTS review VARCHAR(20)");
            // the material of a section's concrete frame: the rules that say a concept's properties and behaviors
            s.execute("CREATE TABLE IF NOT EXISTS frame_concept (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, concept VARCHAR(1000) NOT NULL, kept BOOLEAN, PRIMARY KEY (book, number, ordinal))");
            // the features and parts of a concept folded into it: their rules are collected as the concept's own
            s.execute("ALTER TABLE frame_concept ADD COLUMN IF NOT EXISTS folded VARCHAR(4000)");
            s.execute("CREATE TABLE IF NOT EXISTS frame_rule (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "concept VARCHAR(1000) NOT NULL, ordinal INT NOT NULL, kind VARCHAR(20) NOT NULL, rule VARCHAR(400) NOT NULL, "
                    + "text CLOB NOT NULL, PRIMARY KEY (book, number, concept, ordinal))");
            // how a section explains its main concept: sequence, structure, partition or aspects; the blocks that are the concept's
            // steps, parts, kinds or aspects, one "block<TAB>relation<TAB>name" line each
            // the items of a concept's concrete frame: the questions its explanation answers, each with the rules that
            // answer it, comma separated
            s.execute("CREATE TABLE IF NOT EXISTS frame_item (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "concept VARCHAR(1000) NOT NULL, ordinal INT NOT NULL, question VARCHAR(2000) NOT NULL, rules VARCHAR(4000) NOT NULL, "
                    + "PRIMARY KEY (book, number, concept, ordinal))");
            // the concepts of the book (one per concept, recognized across sections) and the generalized concepts above
            // them; members are "number<TAB>concept" lines, children the ids of a generalized concept's kinds
            s.execute("CREATE TABLE IF NOT EXISTS concept_entity (book VARCHAR(200) NOT NULL, id VARCHAR(1000) NOT NULL, "
                    + "name VARCHAR(1000) NOT NULL, level VARCHAR(20) NOT NULL, members CLOB, children CLOB, PRIMARY KEY (book, id))");
            // the items of a concept of the book: the questions, each with the sentences ("number:rule") that answer it
            s.execute("CREATE TABLE IF NOT EXISTS concept_entity_item (book VARCHAR(200) NOT NULL, id VARCHAR(1000) NOT NULL, "
                    + "ordinal INT NOT NULL, question VARCHAR(2000) NOT NULL, rules CLOB NOT NULL, PRIMARY KEY (book, id, ordinal))");
            // the registry of generalized frames, kept across books and runs: each with an id that is not a name, the frame
            // above it in the concept tree, the concrete frames put into it, and its items
            s.execute("CREATE TABLE IF NOT EXISTS frame_registry (id VARCHAR(40) PRIMARY KEY, name VARCHAR(1000) NOT NULL, "
                    + "parent VARCHAR(40), seq INT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS frame_registry_member (id VARCHAR(40) NOT NULL, book VARCHAR(200) NOT NULL, "
                    + "number VARCHAR(40) NOT NULL, concept VARCHAR(1000) NOT NULL, seq INT NOT NULL, PRIMARY KEY (book, number, concept))");
            // the relations between generalized frames of the registry besides the tree's is-a: part-of, feature,
            // operation, other, and the is-a the sections said, each with the section that said it
            s.execute("CREATE TABLE IF NOT EXISTS frame_relation (from_id VARCHAR(40) NOT NULL, kind VARCHAR(20) NOT NULL, "
                    + "to_id VARCHAR(40) NOT NULL, book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "PRIMARY KEY (from_id, kind, to_id, book, number))");
            // the lookups the viewer makes of one frame: its concrete frames, its kinds, the relations that point to it
            s.execute("CREATE INDEX IF NOT EXISTS frame_registry_member_id ON frame_registry_member (id)");
            s.execute("CREATE INDEX IF NOT EXISTS frame_registry_parent ON frame_registry (parent)");
            s.execute("CREATE INDEX IF NOT EXISTS frame_relation_to ON frame_relation (to_id)");
            s.execute("CREATE TABLE IF NOT EXISTS frame_registry_item (id VARCHAR(40) NOT NULL, ordinal INT NOT NULL, "
                    + "question VARCHAR(2000) NOT NULL, rules CLOB NOT NULL, PRIMARY KEY (id, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS section_pattern (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "concept VARCHAR(1000) NOT NULL, pattern VARCHAR(20) NOT NULL, members CLOB, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_group (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, type VARCHAR(20) NOT NULL, concept VARCHAR(1000), members CLOB NOT NULL, "
                    + "PRIMARY KEY (book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_cause (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, group_ordinal INT NOT NULL, relation VARCHAR(60) NOT NULL, kind VARCHAR(20) NOT NULL, "
                    + "from_concept VARCHAR(1000) NOT NULL, to_concept VARCHAR(1000) NOT NULL, nucleus_rule VARCHAR(200) NOT NULL, "
                    + "satellite_rule VARCHAR(200) NOT NULL, PRIMARY KEY (book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_property (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, group_ordinal INT NOT NULL, concept VARCHAR(1000), kind VARCHAR(20) NOT NULL, "
                    + "property VARCHAR(2000) NOT NULL, rule VARCHAR(200) NOT NULL, PRIMARY KEY (book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_property_asked (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_cause_asked (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "PRIMARY KEY (book, number))");
        }
    }

    /** Replaces the book's table of contents with {@code outline}. */
    public void saveOutline(String book, String dir, BookOutline.Outline outline) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM toc_node WHERE book = ?")) {
                d.setString(1, book);
                d.executeUpdate();
            }
            try (PreparedStatement m = db.prepareStatement("MERGE INTO book (name, dir) KEY (name) VALUES (?, ?)")) {
                m.setString(1, book);
                m.setString(2, dir);
                m.executeUpdate();
            }
            try (PreparedStatement i = db.prepareStatement(
                    "INSERT INTO toc_node (book, number, ordinal, level, title, parent, first_file) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                int n = 0;
                for (BookOutline.Node node : outline.nodes()) {
                    i.setString(1, book);
                    i.setString(2, node.number());
                    i.setInt(3, n++);
                    i.setString(4, node.level());
                    i.setString(5, node.title());
                    i.setString(6, node.parent());
                    i.setString(7, node.firstFile());
                    i.addBatch();
                }
                i.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /**
     * Replaces the book's section texts with {@code sections}. What was made from the old texts (rule files, code files,
     * concepts) is removed with them, since it no longer matches.
     */
    public synchronized void saveSections(String book, List<BookSections.Section> sections) throws SQLException {
        db.setAutoCommit(false);
        try {
            for (String table : List.of("section_text", "section_rules", "section_code", "section_concept", "section_relation", "frame_concept", "frame_rule")) {
                try (PreparedStatement d = db.prepareStatement("DELETE FROM " + table + " WHERE book = ?")) {
                    d.setString(1, book);
                    d.executeUpdate();
                }
            }
            try (PreparedStatement i = db.prepareStatement("INSERT INTO section_text (book, number, text, files, chars) VALUES (?, ?, ?, ?, ?)")) {
                for (BookSections.Section s : sections) {
                    i.setString(1, book);
                    i.setString(2, s.number());
                    i.setString(3, s.text());
                    i.setString(4, String.join("\n", s.files()));
                    i.setInt(5, s.text().length());
                    i.addBatch();
                }
                i.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the book's section texts, in the order of its table of contents */
    public List<BookSections.Section> sections(String book) throws SQLException {
        List<BookSections.Section> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT s.number, s.text, s.files FROM section_text s JOIN toc_node n "
                + "ON n.book = s.book AND n.number = s.number WHERE s.book = ? ORDER BY n.ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    String files = r.getString(3);
                    out.add(new BookSections.Section(r.getString(1), r.getString(2), files.isEmpty() ? List.of() : List.of(files.split("\n"))));
                }
            }
        }
        return out;
    }

    /** One section's rule file and the files its code blocks name. */
    public record SectionRules(String number, String rules, java.util.Map<String, String> code, int questions) {
    }

    /** Replaces one section's rule file and code files. */
    public synchronized void saveSectionRules(String book, SectionRules r) throws SQLException {
        db.setAutoCommit(false);
        try {
            for (String table : List.of("section_rules", "section_code")) {
                try (PreparedStatement d = db.prepareStatement("DELETE FROM " + table + " WHERE book = ? AND number = ?")) {
                    d.setString(1, book);
                    d.setString(2, r.number());
                    d.executeUpdate();
                }
            }
            try (PreparedStatement i = db.prepareStatement("INSERT INTO section_rules (book, number, rules, questions) VALUES (?, ?, ?, ?)")) {
                i.setString(1, book);
                i.setString(2, r.number());
                i.setString(3, r.rules());
                i.setInt(4, r.questions());
                i.executeUpdate();
            }
            try (PreparedStatement i = db.prepareStatement("INSERT INTO section_code (book, number, name, text) VALUES (?, ?, ?, ?)")) {
                for (var e : r.code().entrySet()) {
                    i.setString(1, book);
                    i.setString(2, r.number());
                    i.setString(3, e.getKey());
                    i.setString(4, e.getValue());
                    i.addBatch();
                }
                i.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return one section's rule file and code files, or {@code null} when it has none yet */
    public synchronized SectionRules sectionRules(String book, String number) throws SQLException {
        String rules;
        int questions;
        try (PreparedStatement p = db.prepareStatement("SELECT rules, questions FROM section_rules WHERE book = ? AND number = ?")) {
            p.setString(1, book);
            p.setString(2, number);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                rules = r.getString(1);
                questions = r.getInt(2);
            }
        }
        java.util.Map<String, String> code = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT name, text FROM section_code WHERE book = ? AND number = ? ORDER BY name")) {
            p.setString(1, book);
            p.setString(2, number);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) code.put(r.getString(1), r.getString(2));
            }
        }
        return new SectionRules(number, rules, code, questions);
    }

    /** @return the numbers of the book's sections that have a rule file */
    public synchronized java.util.Set<String> sectionsWithRules(String book) throws SQLException {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number FROM section_rules WHERE book = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(r.getString(1));
            }
        }
        return out;
    }

    /** One concept a section explains, and the numbers of its sentences that explain it ({@link ConceptImportance#units}). */
    public record ListedConcept(String concept, List<Integer> sentences) {
    }

    /** Replaces the concepts one section explains, listed without the sentences that explain them. */
    public void saveConcepts(String book, String number, List<String> concepts) throws SQLException {
        List<ListedConcept> listed = new ArrayList<>();
        for (String c : concepts) listed.add(new ListedConcept(c, null));
        saveListedConcepts(book, number, listed);
    }

    /** Replaces the concepts one section explains, each with the sentences that explain it. */
    public synchronized void saveListedConcepts(String book, String number, List<ListedConcept> concepts) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM section_concept WHERE book = ? AND number = ?")) {
                d.setString(1, book);
                d.setString(2, number);
                d.executeUpdate();
            }
            try (PreparedStatement i = db.prepareStatement("INSERT INTO section_concept (book, number, ordinal, concept, sentences) VALUES (?, ?, ?, ?, ?)")) {
                for (int n = 0; n < concepts.size(); n++) {
                    List<Integer> at = concepts.get(n).sentences();
                    i.setString(1, book);
                    i.setString(2, number);
                    i.setInt(3, n);
                    i.setString(4, concepts.get(n).concept());
                    i.setString(5, at == null ? null : String.join(",", at.stream().map(String::valueOf).toList()));
                    i.addBatch();
                }
                i.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the concepts each of the book's sections explains, by section number; a section not asked yet is absent */
    public synchronized java.util.Map<String, List<String>> concepts(String book) throws SQLException {
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept FROM section_concept WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2));
            }
        }
        return out;
    }

    /** @return each section's concepts with the sentences that explain them ({@code null} when listed without), by section number */
    public synchronized java.util.Map<String, List<ListedConcept>> listedConcepts(String book) throws SQLException {
        java.util.Map<String, List<ListedConcept>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept, sentences FROM section_concept WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    String at = r.getString(3);
                    List<Integer> sentences = null;
                    if (at != null) {
                        sentences = new ArrayList<>();
                        for (String x : at.split(",")) if (!x.isBlank()) sentences.add(Integer.parseInt(x.strip()));
                    }
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new ListedConcept(r.getString(2), sentences));
                }
            }
        }
        return out;
    }

    /**
     * One relation between two concepts of a section (step 6): {@code child} is a kind of {@code parent}
     * ({@link #IS_A}), a part of it ({@link #PART_OF}), a feature of it ({@link #FEATURE}), an operation on it
     * ({@link #OPERATION}), or related to it in another way ({@link #OTHER}), as the sentences numbered say.
     */
    public record Relation(String kind, String child, String parent, List<Integer> sentences, String review) {
        public static final String IS_A = "is-a";
        public static final String PART_OF = "part-of";
        public static final String FEATURE = "feature";
        /** An operation or a method that acts on the other: what a program does with it ({@code containsValue} on a Map). */
        public static final String OPERATION = "operation";
        /** Related, but not as a kind, a part, a feature or an operation: a way of doing the other, a use of it, a rule about it. */
        public static final String OTHER = "other";
        /** Step 7's verdicts besides a kind: the relation stands, it runs the other way, or it is wrong. */
        public static final String KEEP = "keep";
        public static final String REVERSE = "reverse";
        public static final String DROP = "drop";

        /** A relation step 6 found, not reviewed yet. */
        public Relation(String kind, String child, String parent, List<Integer> sentences) {
            this(kind, child, parent, sentences, null);
        }

        /**
         * @return the relation as step 7 left it: the same when kept or not reviewed, with its kind changed, its two
         *         concepts swapped, or {@code null} when dropped
         */
        public Relation reviewed() {
            if (review == null || review.equals(KEEP)) return this;
            if (review.equals(DROP)) return null;
            if (review.equals(REVERSE)) return new Relation(kind, parent, child, sentences, review);
            return new Relation(review, child, parent, sentences, review);
        }
    }

    /** Replaces the relations between one section's concepts; an empty list records that it was asked and none was found. */
    public synchronized void saveRelations(String book, String number, List<Relation> relations) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM section_relation WHERE book = ? AND number = ?")) {
                d.setString(1, book);
                d.setString(2, number);
                d.executeUpdate();
            }
            try (PreparedStatement i = db.prepareStatement(
                    "INSERT INTO section_relation (book, number, ordinal, kind, child, parent, sentences) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                // a section asked with no relation found is kept as ordinal -1, so a run started again does not ask it twice
                List<Relation> rows = relations.isEmpty() ? List.of(new Relation("", "", "", null)) : relations;
                for (int n = 0; n < rows.size(); n++) {
                    Relation r = rows.get(n);
                    i.setString(1, book);
                    i.setString(2, number);
                    i.setInt(3, relations.isEmpty() ? -1 : n);
                    i.setString(4, r.kind());
                    i.setString(5, r.child());
                    i.setString(6, r.parent());
                    i.setString(7, r.sentences() == null ? null : String.join(",", r.sentences().stream().map(String::valueOf).toList()));
                    i.addBatch();
                }
                i.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** One rule of a section's rule file that says a property of a concept or a behavior of it. */
    public record FrameRule(String kind, String rule, String text) {
        public static final String PROPERTY = "property";
        public static final String BEHAVIOR = "behavior";
    }

    /**
     * One concept of a section's concrete frame: the rules collected for it, whether it stays on the list
     * ({@code null} until the list is reviewed), and the features and parts folded into it, whose rules are its own.
     */
    public record FrameConcept(String concept, List<FrameRule> rules, Boolean kept, List<String> folded) {
        public FrameConcept(String concept, List<FrameRule> rules, Boolean kept) {
            this(concept, rules, kept, List.of());
        }
    }

    /** Replaces the concepts of one section's concrete frame and the rules collected for each. */
    public synchronized void saveFrameConcepts(String book, String number, List<FrameConcept> concepts) throws SQLException {
        db.setAutoCommit(false);
        try {
            for (String table : List.of("frame_concept", "frame_rule")) {
                try (PreparedStatement d = db.prepareStatement("DELETE FROM " + table + " WHERE book = ? AND number = ?")) {
                    d.setString(1, book);
                    d.setString(2, number);
                    d.executeUpdate();
                }
            }
            try (PreparedStatement c = db.prepareStatement("INSERT INTO frame_concept (book, number, ordinal, concept, kept, folded) VALUES (?, ?, ?, ?, ?, ?)");
                 PreparedStatement r = db.prepareStatement(
                         "INSERT INTO frame_rule (book, number, concept, ordinal, kind, rule, text) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (int n = 0; n < concepts.size(); n++) {
                    FrameConcept fc = concepts.get(n);
                    c.setString(1, book);
                    c.setString(2, number);
                    c.setInt(3, n);
                    c.setString(4, fc.concept());
                    c.setObject(5, fc.kept());
                    c.setString(6, fc.folded() == null || fc.folded().isEmpty() ? null : String.join("\n", fc.folded()));
                    c.addBatch();
                    for (int k = 0; k < fc.rules().size(); k++) {
                        FrameRule fr = fc.rules().get(k);
                        r.setString(1, book);
                        r.setString(2, number);
                        r.setString(3, fc.concept());
                        r.setInt(4, k);
                        r.setString(5, fr.kind());
                        r.setString(6, fr.rule());
                        r.setString(7, fr.text());
                        r.addBatch();
                    }
                }
                c.executeBatch();
                r.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /**
     * @return one concrete-frame concept of one section with its rules, or {@code null}: read by the keys, for a view of
     *         one frame, not the whole book
     */
    public synchronized FrameConcept frameConcept(String book, String number, String concept) throws SQLException {
        Boolean kept;
        String folded;
        try (PreparedStatement p = db.prepareStatement("SELECT kept, folded FROM frame_concept WHERE book = ? AND number = ? AND concept = ?")) {
            p.setString(1, book);
            p.setString(2, number);
            p.setString(3, concept);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                kept = (Boolean) r.getObject(1);
                folded = r.getString(2);
            }
        }
        List<FrameRule> rules = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT kind, rule, text FROM frame_rule WHERE book = ? AND number = ? AND concept = ? ORDER BY ordinal")) {
            p.setString(1, book);
            p.setString(2, number);
            p.setString(3, concept);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rules.add(new FrameRule(r.getString(1), r.getString(2), r.getString(3)));
            }
        }
        return new FrameConcept(concept, rules, kept, folded == null ? List.of() : List.of(folded.split("\n")));
    }

    /** @return each section's concrete-frame concepts with their rules, by section number, in list order */
    public synchronized java.util.Map<String, List<FrameConcept>> frameConcepts(String book) throws SQLException {
        java.util.Map<String, java.util.Map<String, List<FrameRule>>> rules = new java.util.HashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept, kind, rule, text FROM frame_rule WHERE book = ? ORDER BY number, concept, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    rules.computeIfAbsent(r.getString(1), k -> new java.util.HashMap<>()).computeIfAbsent(r.getString(2), k -> new ArrayList<>())
                         .add(new FrameRule(r.getString(3), r.getString(4), r.getString(5)));
                }
            }
        }
        java.util.Map<String, List<FrameConcept>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept, kept, folded FROM frame_concept WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    String number = r.getString(1);
                    List<FrameRule> rs = rules.getOrDefault(number, java.util.Map.of()).getOrDefault(r.getString(2), List.of());
                    String f = r.getString(4);
                    out.computeIfAbsent(number, k -> new ArrayList<>())
                       .add(new FrameConcept(r.getString(2), rs, (Boolean) r.getObject(3), f == null ? List.of() : List.of(f.split("\n"))));
                }
            }
        }
        return out;
    }

    /** One appearance of a concept: the section and the name it has there. */
    public record Occurrence(String number, String concept) {
    }

    /**
     * A concept of the book: recognized across the sections it appears in ({@link #CONCEPT}), or a generalized concept
     * above its kinds ({@link #GENERAL}).
     *
     * @param members  the appearances whose material it has
     * @param children the ids of a generalized concept's kinds; empty for a concept
     */
    public record Entity(String id, String name, String level, List<Occurrence> members, List<String> children) {
        public static final String CONCEPT = "concept";
        public static final String GENERAL = "general";
    }

    /** Replaces the concepts of the book of one level, and drops the items of those it had. */
    public synchronized void saveEntities(String book, String level, List<Entity> entities) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_entity_item WHERE book = ? AND id IN "
                    + "(SELECT id FROM concept_entity WHERE book = ? AND level = ?)")) {
                d.setString(1, book);
                d.setString(2, book);
                d.setString(3, level);
                d.executeUpdate();
            }
            try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_entity WHERE book = ? AND level = ?")) {
                d.setString(1, book);
                d.setString(2, level);
                d.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("INSERT INTO concept_entity (book, id, name, level, members, children) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (Entity e : entities) {
                    StringBuilder m = new StringBuilder();
                    for (Occurrence o : e.members()) m.append(o.number()).append('\t').append(o.concept()).append('\n');
                    p.setString(1, book);
                    p.setString(2, e.id());
                    p.setString(3, e.name());
                    p.setString(4, level);
                    p.setString(5, m.toString());
                    p.setString(6, String.join("\n", e.children()));
                    p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the concepts of the book, concepts first, in the order stored */
    public synchronized List<Entity> entities(String book) throws SQLException {
        List<Entity> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id, name, level, members, children FROM concept_entity WHERE book = ? ORDER BY level, id")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    List<Occurrence> members = new ArrayList<>();
                    String m = r.getString(4);
                    if (m != null) {
                        for (String line : m.split("\n")) {
                            int tab = line.indexOf('\t');
                            if (tab > 0) members.add(new Occurrence(line.substring(0, tab), line.substring(tab + 1)));
                        }
                    }
                    String c = r.getString(5);
                    out.add(new Entity(r.getString(1), r.getString(2), r.getString(3), members,
                            c == null || c.isBlank() ? List.of() : List.of(c.split("\n"))));
                }
            }
        }
        return out;
    }

    /** Replaces the items of one concept of the book. */
    public synchronized void saveEntityItems(String book, String id, List<FrameItem> items) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_entity_item WHERE book = ? AND id = ?")) {
                d.setString(1, book);
                d.setString(2, id);
                d.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("INSERT INTO concept_entity_item (book, id, ordinal, question, rules) VALUES (?, ?, ?, ?, ?)")) {
                for (int n = 0; n < items.size(); n++) {
                    p.setString(1, book);
                    p.setString(2, id);
                    p.setInt(3, n);
                    p.setString(4, items.get(n).question());
                    p.setString(5, String.join(",", items.get(n).rules()));
                    p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the items of each concept of the book that has them, by id */
    public synchronized java.util.Map<String, List<FrameItem>> entityItems(String book) throws SQLException {
        java.util.Map<String, List<FrameItem>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id, question, rules FROM concept_entity_item WHERE book = ? ORDER BY id, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new FrameItem(r.getString(2), List.of(r.getString(3).split(","))));
            }
        }
        return out;
    }

    /** A concrete frame put into a generalized frame of the registry: the book, the section and the concept it is of. */
    public record RegistryMember(String book, String number, String concept) {
    }

    /** A generalized frame of the registry: its id, its name, the frame above it ({@code null} at the top), its concrete frames. */
    public record RegistryFrame(String id, String name, String parent, List<RegistryMember> members) {
    }

    /** @return the generalized frames of the registry, in the order they were made, each with its concrete frames */
    public synchronized List<RegistryFrame> registry() throws SQLException {
        java.util.Map<String, List<RegistryMember>> members = new java.util.HashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id, book, number, concept FROM frame_registry_member ORDER BY seq")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) members.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new RegistryMember(r.getString(2), r.getString(3), r.getString(4)));
            }
        }
        List<RegistryFrame> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id, name, parent FROM frame_registry ORDER BY seq")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new RegistryFrame(r.getString(1), r.getString(2), r.getString(3), members.getOrDefault(r.getString(1), List.of())));
            }
        }
        return out;
    }

    /** Adds a generalized frame to the registry, with no concrete frame yet. */
    public synchronized void addRegistryFrame(String id, String name, int seq) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO frame_registry (id, name, parent, seq) VALUES (?, ?, NULL, ?)")) {
            p.setString(1, id);
            p.setString(2, name);
            p.setInt(3, seq);
            p.executeUpdate();
        }
    }

    /** Puts a concrete frame into a generalized frame of the registry, taking it out of any it was in. */
    public synchronized void addRegistryMember(String id, RegistryMember m, int seq) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("MERGE INTO frame_registry_member (id, book, number, concept, seq) KEY (book, number, concept) VALUES (?, ?, ?, ?, ?)")) {
            p.setString(1, id);
            p.setString(2, m.book());
            p.setString(3, m.number());
            p.setString(4, m.concept());
            p.setInt(5, seq);
            p.executeUpdate();
        }
    }

    /** Renames a generalized frame of the registry. */
    public synchronized void renameRegistryFrame(String id, String name) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE frame_registry SET name = ? WHERE id = ?")) {
            p.setString(1, name);
            p.setString(2, id);
            p.executeUpdate();
        }
    }

    /** Sets the frame above a generalized frame in the concept tree; {@code null} puts it at the top. */
    public synchronized void setRegistryParent(String id, String parent) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE frame_registry SET parent = ? WHERE id = ?")) {
            p.setString(1, parent);
            p.setString(2, id);
            p.executeUpdate();
        }
    }

    /** A relation between two generalized frames of the registry, as a section of a book said it. */
    public record FrameRelation(String from, String kind, String to, String book, String number) {
    }

    /** Replaces the relations a book's sections gave between generalized frames of the registry. */
    public synchronized void saveFrameRelations(String book, List<FrameRelation> rels) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM frame_relation WHERE book = ?")) {
                d.setString(1, book);
                d.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("MERGE INTO frame_relation (from_id, kind, to_id, book, number) KEY (from_id, kind, to_id, book, number) VALUES (?, ?, ?, ?, ?)")) {
                for (FrameRelation r : rels) {
                    p.setString(1, r.from());
                    p.setString(2, r.kind());
                    p.setString(3, r.to());
                    p.setString(4, r.book());
                    p.setString(5, r.number());
                    p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return every relation between generalized frames of the registry */
    public synchronized List<FrameRelation> frameRelations() throws SQLException {
        List<FrameRelation> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT from_id, kind, to_id, book, number FROM frame_relation ORDER BY from_id, kind, to_id")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new FrameRelation(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
            }
        }
        return out;
    }

    /** Empties the registry: its frames, their concrete frames, their items and their relations. */
    public synchronized void clearRegistry() throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute("DELETE FROM frame_relation");
            st.execute("DELETE FROM frame_registry_item");
            st.execute("DELETE FROM frame_registry_member");
            st.execute("DELETE FROM frame_registry");
        }
    }

    /** Replaces the items of a generalized frame of the registry; a rule is named "book|number:rule". */
    public synchronized void saveRegistryItems(String id, List<FrameItem> items) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM frame_registry_item WHERE id = ?")) {
                d.setString(1, id);
                d.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("INSERT INTO frame_registry_item (id, ordinal, question, rules) VALUES (?, ?, ?, ?)")) {
                for (int n = 0; n < items.size(); n++) {
                    p.setString(1, id);
                    p.setInt(2, n);
                    p.setString(3, items.get(n).question());
                    p.setString(4, String.join(",", items.get(n).rules()));
                    p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the items of each generalized frame of the registry that has them, by id */
    public synchronized java.util.Map<String, List<FrameItem>> registryItems() throws SQLException {
        java.util.Map<String, List<FrameItem>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id, question, rules FROM frame_registry_item ORDER BY id, ordinal")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new FrameItem(r.getString(2), List.of(r.getString(3).split(","))));
            }
        }
        return out;
    }

    /** One item of a concept's concrete frame: a question its explanation answers, and the rules that answer it. */
    public record FrameItem(String question, List<String> rules) {
    }

    /** Replaces the items of one concept of one section. */
    public synchronized void saveFrameItems(String book, String number, String concept, List<FrameItem> items) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM frame_item WHERE book = ? AND number = ? AND concept = ?")) {
                d.setString(1, book);
                d.setString(2, number);
                d.setString(3, concept);
                d.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("INSERT INTO frame_item (book, number, concept, ordinal, question, rules) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int n = 0; n < items.size(); n++) {
                    p.setString(1, book);
                    p.setString(2, number);
                    p.setString(3, concept);
                    p.setInt(4, n);
                    p.setString(5, items.get(n).question());
                    p.setString(6, String.join(",", items.get(n).rules()));
                    p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the items of each concept, by section number and concept, in order */
    public synchronized java.util.Map<String, java.util.Map<String, List<FrameItem>>> frameItems(String book) throws SQLException {
        java.util.Map<String, java.util.Map<String, List<FrameItem>>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept, question, rules FROM frame_item WHERE book = ? ORDER BY number, concept, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getString(1), k -> new java.util.LinkedHashMap<>()).computeIfAbsent(r.getString(2), k -> new ArrayList<>())
                       .add(new FrameItem(r.getString(3), List.of(r.getString(4).split(","))));
                }
            }
        }
        return out;
    }

    /** Replaces the properties of one section's groups' main concepts (step 3 of the frame data workflow), and marks it asked. */
    public synchronized void saveProperties(String book, String number, List<ConceptProperties.Property> props) throws SQLException {
        try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_property WHERE book = ? AND number = ?")) {
            d.setString(1, book);
            d.setString(2, number);
            d.executeUpdate();
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO concept_property (book, number, ordinal, group_ordinal, concept, kind, "
                + "property, rule) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            for (ConceptProperties.Property x : props) {
                p.setString(1, book);
                p.setString(2, number);
                p.setInt(3, ++i);
                p.setInt(4, x.group());
                p.setString(5, x.concept());
                p.setString(6, x.kind());
                p.setString(7, x.property());
                p.setString(8, x.rule());
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = db.prepareStatement("MERGE INTO concept_property_asked (book, number) KEY (book, number) VALUES (?, ?)")) {
            p.setString(1, book);
            p.setString(2, number);
            p.executeUpdate();
        }
    }

    /** @return the sections whose groups' properties have been asked, even those that had none */
    public synchronized java.util.Set<String> propertiesAsked(String book) throws SQLException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number FROM concept_property_asked WHERE book = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(r.getString(1));
            }
        }
        return out;
    }

    /** @return the properties of each section's groups' main concepts, by section number, in the order they were read */
    public synchronized java.util.Map<String, List<ConceptProperties.Property>> properties(String book) throws SQLException {
        java.util.Map<String, List<ConceptProperties.Property>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, group_ordinal, concept, kind, property, rule FROM concept_property "
                + "WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new ConceptProperties.Property(r.getInt(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6)));
                }
            }
        }
        return out;
    }

    /** Replaces the causes and motives of one section's concepts (step 2 of the frame data workflow), and marks it asked. */
    public synchronized void saveCauses(String book, String number, List<ConceptCauses.Link> links) throws SQLException {
        try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_cause WHERE book = ? AND number = ?")) {
            d.setString(1, book);
            d.setString(2, number);
            d.executeUpdate();
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO concept_cause (book, number, ordinal, group_ordinal, relation, kind, "
                + "from_concept, to_concept, nucleus_rule, satellite_rule) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            for (ConceptCauses.Link l : links) {
                p.setString(1, book);
                p.setString(2, number);
                p.setInt(3, ++i);
                p.setInt(4, l.group());
                p.setString(5, l.relation());
                p.setString(6, l.kind());
                p.setString(7, l.from());
                p.setString(8, l.to());
                p.setString(9, l.nucleusRule());
                p.setString(10, l.satelliteRule());
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = db.prepareStatement("MERGE INTO concept_cause_asked (book, number) KEY (book, number) VALUES (?, ?)")) {
            p.setString(1, book);
            p.setString(2, number);
            p.executeUpdate();
        }
    }

    /** @return the sections whose causes and motives have been asked, even those that had none */
    public synchronized java.util.Set<String> causesAsked(String book) throws SQLException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number FROM concept_cause_asked WHERE book = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(r.getString(1));
            }
        }
        return out;
    }

    /** @return the causes and motives of each section's concepts, by section number, in pair order */
    public synchronized java.util.Map<String, List<ConceptCauses.Link>> causes(String book) throws SQLException {
        java.util.Map<String, List<ConceptCauses.Link>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, group_ordinal, relation, kind, from_concept, to_concept, nucleus_rule, "
                + "satellite_rule FROM concept_cause WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new ConceptCauses.Link(r.getInt(2), r.getString(3), r.getString(4),
                            r.getString(5), r.getString(6), r.getString(7), r.getString(8)));
                }
            }
        }
        return out;
    }

    /**
     * Replaces the units of one section's blocks (step 1 of the frame data workflow), and forgets the causes and
     * properties read of the old ones.
     */
    public synchronized void saveGroups(String book, String number, List<SectionGroups.Group> groups) throws SQLException {
        // what was read of the old groups refers to them by ordinal: it goes with them
        for (String t : List.of("section_group", "concept_cause", "concept_cause_asked", "concept_property", "concept_property_asked")) {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM " + t + " WHERE book = ? AND number = ?")) {
                d.setString(1, book);
                d.setString(2, number);
                d.executeUpdate();
            }
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO section_group (book, number, ordinal, type, concept, members) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (SectionGroups.Group g : groups) {
                StringBuilder members = new StringBuilder();
                for (SectionGroups.Member m : g.blocks()) members.append(m.block()).append('\t').append(m.name() == null ? "" : m.name().replace('\n', ' ')).append('\n');
                p.setString(1, book);
                p.setString(2, number);
                p.setInt(3, g.ordinal());
                p.setString(4, g.type());
                p.setString(5, g.concept());
                p.setString(6, members.toString());
                p.executeUpdate();
            }
        }
    }

    /** @return the groups of each section that has been split, by section number, in group order */
    public synchronized java.util.Map<String, List<SectionGroups.Group>> groups(String book) throws SQLException {
        java.util.Map<String, List<SectionGroups.Group>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, ordinal, type, concept, members FROM section_group WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    List<SectionGroups.Member> members = new ArrayList<>();
                    for (String line : r.getString(5).split("\n")) {
                        String[] f = line.split("\t", 2);
                        if (!f[0].isEmpty()) members.add(new SectionGroups.Member(f[0], f.length > 1 ? f[1] : ""));
                    }
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new SectionGroups.Group(r.getInt(2), r.getString(3), r.getString(4), members));
                }
            }
        }
        return out;
    }

    /** Replaces how one section explains its main concept. */
    public synchronized void savePattern(String book, String number, SectionPattern.Layout layout) throws SQLException {
        StringBuilder members = new StringBuilder();
        for (SectionPattern.Member m : layout.members()) members.append(m.block()).append('\t').append(m.relation()).append('\t').append(m.name().replace('\n', ' ')).append('\n');
        try (PreparedStatement p = db.prepareStatement("MERGE INTO section_pattern (book, number, concept, pattern, members) KEY (book, number) "
                + "VALUES (?, ?, ?, ?, ?)")) {
            p.setString(1, book);
            p.setString(2, number);
            p.setString(3, layout.concept());
            p.setString(4, layout.pattern());
            p.setString(5, members.toString());
            p.executeUpdate();
        }
    }

    /** @return how each section that has been asked explains its main concept, by section number */
    public synchronized java.util.Map<String, SectionPattern.Layout> patterns(String book) throws SQLException {
        java.util.Map<String, SectionPattern.Layout> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept, pattern, members FROM section_pattern WHERE book = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    List<SectionPattern.Member> members = new ArrayList<>();
                    String text = r.getString(4);
                    if (text != null) {
                        for (String line : text.split("\n")) {
                            String[] f = line.split("\t", 3);
                            if (f.length == 3) members.add(new SectionPattern.Member(f[0], f[1], f[2]));
                        }
                    }
                    out.put(r.getString(1), new SectionPattern.Layout(r.getString(2), r.getString(3), members));
                }
            }
        }
        return out;
    }

    /** Records step 7's verdict on each of one section's relations, in the order {@link #relations} gives them. */
    public synchronized void saveReview(String book, String number, List<String> verdicts) throws SQLException {
        db.setAutoCommit(false);
        try (PreparedStatement u = db.prepareStatement("UPDATE section_relation SET review = ? WHERE book = ? AND number = ? AND ordinal = ?")) {
            for (int n = 0; n < verdicts.size(); n++) {
                u.setString(1, verdicts.get(n));
                u.setString(2, book);
                u.setString(3, number);
                u.setInt(4, n);
                u.addBatch();
            }
            u.executeBatch();
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return the relations between each section's concepts, by section number; a section asked with none found has an empty list */
    public synchronized java.util.Map<String, List<Relation>> relations(String book) throws SQLException {
        java.util.Map<String, List<Relation>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement(
                "SELECT number, ordinal, kind, child, parent, sentences, review FROM section_relation WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    List<Relation> list = out.computeIfAbsent(r.getString(1), k -> new ArrayList<>());
                    if (r.getInt(2) < 0) continue;
                    String at = r.getString(6);
                    List<Integer> sentences = new ArrayList<>();
                    if (at != null) for (String x : at.split(",")) if (!x.isBlank()) sentences.add(Integer.parseInt(x.strip()));
                    list.add(new Relation(r.getString(3), r.getString(4), r.getString(5), sentences, r.getString(7)));
                }
            }
        }
        return out;
    }

    /** One concept of a section, whether step 5 kept it ({@code null} until judged), and why. */
    public record JudgedConcept(String concept, Boolean kept, String reason) {
    }

    /** Records step 5's judgement of one section's concepts, in the order they were listed. */
    public synchronized void saveJudgement(String book, String number, List<JudgedConcept> judged) throws SQLException {
        db.setAutoCommit(false);
        try (PreparedStatement u = db.prepareStatement("UPDATE section_concept SET kept = ?, reason = ? WHERE book = ? AND number = ? AND ordinal = ?")) {
            for (int n = 0; n < judged.size(); n++) {
                u.setObject(1, judged.get(n).kept());
                u.setString(2, judged.get(n).reason());
                u.setString(3, book);
                u.setString(4, number);
                u.setInt(5, n);
                u.addBatch();
            }
            u.executeBatch();
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** @return each section's concepts with step 5's judgement, by section number */
    public synchronized java.util.Map<String, List<JudgedConcept>> judgedConcepts(String book) throws SQLException {
        java.util.Map<String, List<JudgedConcept>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, concept, kept, reason FROM section_concept WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>())
                       .add(new JudgedConcept(r.getString(2), (Boolean) r.getObject(3), r.getString(4)));
                }
            }
        }
        return out;
    }

    /** @return the book's directory, or {@code null} when the book is not in the database */
    public String dirOf(String book) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT dir FROM book WHERE name = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    /** @return the books, by name */
    public List<String> books() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT name FROM book ORDER BY name")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    /** @return the book's table of contents, in the book's order */
    public List<BookOutline.Node> outline(String book) throws SQLException {
        List<BookOutline.Node> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement(
                "SELECT number, level, title, parent, first_file FROM toc_node WHERE book = ? ORDER BY ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new BookOutline.Node(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}

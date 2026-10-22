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
            for (String table : List.of("section_text", "section_rules", "section_code", "section_concept")) {
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

    /** Replaces the concepts one section explains. */
    public synchronized void saveConcepts(String book, String number, List<String> concepts) throws SQLException {
        db.setAutoCommit(false);
        try {
            try (PreparedStatement d = db.prepareStatement("DELETE FROM section_concept WHERE book = ? AND number = ?")) {
                d.setString(1, book);
                d.setString(2, number);
                d.executeUpdate();
            }
            try (PreparedStatement i = db.prepareStatement("INSERT INTO section_concept (book, number, ordinal, concept) VALUES (?, ?, ?, ?)")) {
                for (int n = 0; n < concepts.size(); n++) {
                    i.setString(1, book);
                    i.setString(2, number);
                    i.setInt(3, n);
                    i.setString(4, concepts.get(n));
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

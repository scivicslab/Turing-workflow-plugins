package com.scivicslab.turingworkflow.plugins.rstframe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Stores and retrieves the intermediate data produced by the steps of the frame data workflow in an H2 database.
 *
 * <ul><li>{@code saveOutline} replaces a book's table of contents with a new hierarchy of nodes.</li><li>{@code
 * saveSections} replaces a book's section texts and their associated rule and code files.</li><li>{@code sections}
 * returns the book's section texts in the order of its table of contents.</li><li>{@code saveSectionRules} replaces
 * one section's rule file and its associated code blocks.</li><li>{@code sectionRules} returns one section's rule file
 * and code files, or {@code null} if they do not exist.</li><li>{@code sectionsWithRules} returns the numbers of the
 * book's sections that have a rule file.</li><li>{@code saveConcepts} replaces the concepts one section explains,
 * without the sentences that explain them.</li><li>{@code saveListedConcepts} replaces the concepts one section
 * explains, including the sentences that explain each.</li><li>{@code concepts} returns a map of the concepts each of
 * the book's sections explains, by section number.</li><li>{@code listedConcepts} returns a map of each section's
 * concepts with the sentences that explain them, by section number.</li><li>{@code saveRelations} replaces the
 * relations between one section's concepts.</li><li>{@code relations} returns the relations between each section's
 * concepts, by section number.</li><li>{@code sectionRules} returns one section's rule file and code files, or {@code
 * null} if they do not exist.</li><li>{@code saveFrameConcepts} replaces the concepts of one section's concrete frame
 * and the rules collected for each.</li><li>{@code frameConcept} returns one concrete-frame concept of one section
 * with its rules, or {@code null} if it does not exist.</li><li>{@code frameConcepts} returns each section's
 * concrete-frame concepts with their rules, by section number.</li><li>{@code saveEntities} replaces the concepts of
 * the book at one level and removes their associated items.</li><li>{@code entities} returns the concepts of the book,
 * ordered by level and ID.</li><li>{@code saveEntityItems} replaces the items of one concept of the
 * book.</li><li>{@code entityItems} returns the items of each concept of the book that has them, by ID.</li><li>{@code
 * registry} returns the generalized frames of the registry, in the order they were created.</li><li>{@code
 * addRegistryFrame} adds a new generalized frame to the registry.</li><li>{@code addRegistryMember} puts a concrete
 * frame into a generalized frame, removing it from any previous one.</li><li>{@code renameRegistryFrame} renames a
 * generalized frame.</li><li>{@code setRegistryParent} sets the frame above a generalized frame in the concept tree,
 * or {@code null} to place it at the top.</li><li>{@code saveFrameRelations} replaces the relations between
 * generalized frames of the registry.</li><li>{@code frameRelations} returns every relation between generalized frames
 * of the registry.</li><li>{@code clearRegistry} empties the registry, including frames, members, items, and
 * relations.</li><li>{@code saveRegistryItems} replaces the items of a generalized frame of the
 * registry.</li><li>{@code registryItems} returns the items of each generalized frame of the registry, by
 * ID.</li><li>{@code saveFrameItems} replaces the items of one concept of one section.</li><li>{@code frameItems}
 * returns the items of each concept, by section number and concept.</li><li>{@code saveRelationsOfConcepts} replaces
 * the relations of one section's concepts to other concepts and marks the section as having been asked.</li><li>{@code
 * relationsAsked} returns the set of sections whose concepts' relations have been read.</li><li>{@code
 * relationsOfConcepts} returns the relations of each section's concepts to other concepts, by section
 * number.</li><li>{@code saveUnitEmbedding} saves a unit's embedding and its main concept.</li><li>{@code
 * unitEmbeddings} returns the embeddings made by a specific model, keyed by the unit's identity.</li><li>{@code
 * saveSimilar} replaces the similarity judgments for a unit under a specific criterion.</li><li>{@code similarJudged}
 * returns the set of units judged under a specific criterion.</li><li>{@code similarities} returns every similarity
 * judgment in the registry.</li><li>{@code saveProperties} replaces the properties of one section's groups' main
 * concepts and marks the section as having been asked.</li><li>{@code propertiesAsked} returns the set of sections
 * whose groups' properties have been asked.</li><li>{@code properties} returns the properties of each section's
 * groups' main concepts, by section number.</li><li>{@code saveCauses} replaces the causes and motives of one
 * section's concepts and marks the section as having been asked.</li><li>{@code causesAsked} returns the set of
 * sections whose causes and motives have been asked.</li><li>{@code causes} returns the causes and motives of each
 * section's concepts, by section number.</li><li>{@code saveGroups} replaces the units of one section's blocks and
 * removes their associated causes, properties, and relations.</li><li>{@code groups} returns the groups of each
 * section that has been split, by section number.</li><li>{@code savePattern} replaces how one section explains its
 * main concept.</li><li>{@code patterns} returns how each section that has been asked explains its main concept, by
 * section number.</li><li>{@code saveReview} records the verdict for each of one section's relations.</li><li>{@code
 * relations} returns the relations between each section's concepts, by section number.</li><li>{@code saveJudgement}
 * records the judgement of one section's concepts.</li><li>{@code judgedConcepts} returns each section's concepts with
 * their step 5 judgement, by section number.</li><li>{@code dirOf} returns the directory of a book, or {@code null} if
 * the book is not in the database.</li><li>{@code books} returns the names of all books in the
 * database.</li><li>{@code outline} returns the book's table of contents.</li></ul>
 *
 * <p>One instance of {@code BookStore} may serve several workflows or threads at once.</p>
 */
public final class BookStore implements AutoCloseable {

    private final Connection db;

    /**
     * Opens an H2 connection through the H2 driver class this class's own class loader sees.
     *
     * <p>{@code DriverManager} hands a connection only to drivers visible to the caller's class loader, so it finds
     * no driver when H2 was loaded by another class loader. Calling the driver directly works when the workflow loads
     * H2 with {@code loader.loadJar} besides this plugin, and on a plain classpath (the {@code BookImport} CLI); listing
     * H2 in {@code plugins.yaml} alone does not make it visible to this plugin.</p>
     *
     * @param url an H2 JDBC URL
     * @return the open connection, as user {@code sa} with an empty password
     * @throws SQLException if the URL is not an H2 URL or the database cannot be opened
     */
    static Connection openH2(String url) throws SQLException {
        Properties credentials = new Properties();
        credentials.setProperty("user", "sa");
        credentials.setProperty("password", "");
        Connection connection = new org.h2.Driver().connect(url, credentials);
        if (connection == null) {
            throw new SQLException("Not an H2 JDBC URL: " + url);
        }
        return connection;
    }

    /**
     * Initializes a connection to an H2 database that stores the intermediate data of the steps that make frames from
     * books.
     *
     * @param url a JDBC URL, e.g. {@code jdbc:h2:/home/u/works/rst-on-lisp/db/books;AUTO_SERVER=TRUE}
     * @throws SQLException if a database access error occurs or the connection cannot be established
     */
    public BookStore(String url) throws SQLException {
        this.db = openH2(url);
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
            // no query looks a frame up by these columns: the viewer reads the registry whole, so the indexes an older
            // version made only slowed the writes
            s.execute("DROP INDEX IF EXISTS frame_registry_member_id");
            s.execute("DROP INDEX IF EXISTS frame_registry_parent");
            s.execute("DROP INDEX IF EXISTS frame_relation_to");
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
            s.execute("CREATE TABLE IF NOT EXISTS concept_relation (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, group_ordinal INT NOT NULL, source VARCHAR(60) NOT NULL, link VARCHAR(60) NOT NULL, "
                    + "from_concept VARCHAR(1000) NOT NULL, to_concept VARCHAR(1000) NOT NULL, rule VARCHAR(200) NOT NULL, "
                    + "PRIMARY KEY (book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_relation_asked (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "PRIMARY KEY (book, number))");
            // similarity between units is kept apart from the sections' tables, keyed by the criterion it was judged by
            s.execute("CREATE TABLE IF NOT EXISTS unit_embedding (model VARCHAR(200) NOT NULL, book VARCHAR(200) NOT NULL, "
                    + "number VARCHAR(40) NOT NULL, ordinal INT NOT NULL, text CLOB NOT NULL, vector VARBINARY(16384) NOT NULL, "
                    + "PRIMARY KEY (model, book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_similarity (criterion VARCHAR(200) NOT NULL, book VARCHAR(200) NOT NULL, "
                    + "number VARCHAR(40) NOT NULL, ordinal INT NOT NULL, concept VARCHAR(1000) NOT NULL, other_book VARCHAR(200) NOT NULL, "
                    + "other_number VARCHAR(40) NOT NULL, other_ordinal INT NOT NULL, other_concept VARCHAR(1000) NOT NULL, "
                    + "common VARCHAR(1000) NOT NULL, PRIMARY KEY (criterion, book, number, ordinal, other_book, other_number, other_ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_similarity_judged (criterion VARCHAR(200) NOT NULL, book VARCHAR(200) NOT NULL, "
                    + "number VARCHAR(40) NOT NULL, ordinal INT NOT NULL, concept VARCHAR(1000) NOT NULL, "
                    + "PRIMARY KEY (criterion, book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_property (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, group_ordinal INT NOT NULL, concept VARCHAR(1000), kind VARCHAR(20) NOT NULL, "
                    + "property VARCHAR(2000) NOT NULL, rule VARCHAR(200) NOT NULL, PRIMARY KEY (book, number, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_property_asked (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS concept_cause_asked (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "PRIMARY KEY (book, number))");
        }
    }

    /**
     * Replaces the book's table of contents and directory with {@code outline}.
     *
     * <p>If the operation succeeds, the existing table of contents for the specified {@code book} is deleted and
     * replaced by the nodes in {@code outline}, and the book's directory is updated to {@code dir}. If a {@code
     * SQLException} occurs, the database is rolled back to its previous state and the exception is thrown.
     *
     * @param book the name of the book
     * @param dir the directory of the book
     * @param outline the new table of contents structure
     * @throws SQLException if a database access error occurs
     */
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
     * Replaces all text, rules, code blocks, and concepts associated with a book with the provided {@code sections}.
     *
     * <p>The existing data for the specified {@code book} in the tables for section text, rules, code, concepts,
     * relations, frame concepts, and frame rules is deleted before the new data is inserted. If the operation fails,
     * the database state is rolled back to before the call.</p>
     *
     * @param book the name of the book
     * @param sections the new section data to store
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

    /**
     * Returns the book's section texts, in the order of its table of contents.
     *
     * @param book the name of the book
     * @return a list of {@link BookSections.Section} objects, each containing the section's number, text, and a list
     * of the markdown files it was read from, ordered by the book's table of contents
     */
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

    /**
     * Holds a section's rst-on-lisp rule file, the names and text of its code blocks, and the number of questions it
     * contains.
     *
     * @param number the section number
     * @param rules the text of the rule file
     * @param code a mapping from code block names to their text
     * @param questions the number of questions in the section
     */
    public record SectionRules(String number, String rules, java.util.Map<String, String> code, int questions) {
    }

    /**
     * Replaces one section's rule file and the associated code files in the database.
     *
     * <p>One instance of this actor may serve several workflows or threads at once, but actions run one at a time. If
     * the operation fails, a {@link SQLException} is thrown.</p>
     *
     * @param book the name of the book
     * @param r the rules and code files to store
     */
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

    /**
     * Returns a {@link SectionRules} object containing one section's rule file and the files its code blocks name, or
     * {@code null} if no rules exist for that section.
     *
     * @param book the name of the book
     * @param number the section number
     * @return a {@link SectionRules} containing the rules text, the number of questions, and a map of code block names
     * to their text, or {@code null} if the section has no rules recorded
     */
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

    /**
     * Returns the numbers of the book's sections that have a rule file.
     *
     * @param book the name of the book
     * @return a set of section numbers that have an associated rule file
     */
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

    /**
     * Represents a concept explained in a section and the specific sentences that provide its explanation.
     *
     * @param concept the name of the concept
     * @param sentences the numbers of the sentences that explain it, or {@code null} if the concept was listed without
     * its explanatory sentences
     */
    public record ListedConcept(String concept, List<Integer> sentences) {
    }

    /**
     * Replaces the concepts one section explains, without the sentences that explain them.
     *
     * @param book the book's name
     * @param number the section's number
     * @param concepts the list of concept names
     */
    public void saveConcepts(String book, String number, List<String> concepts) throws SQLException {
        List<ListedConcept> listed = new ArrayList<>();
        for (String c : concepts) listed.add(new ListedConcept(c, null));
        saveListedConcepts(book, number, listed);
    }

    /**
     * Replaces the concepts one section explains, each with the sentences that explain it.
     *
     * <p>One instance may serve several workflows or threads at once.
     *
     * @param book the name of the book
     * @param number the section number
     * @param concepts the list of concepts and their explaining sentences
     * @throws SQLException if a database error occurs
     */
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

    /**
     * Returns a mapping from section numbers to the list of concepts each section explains.
     *
     * <p>The map contains only the sections that have already been processed; sections that have not yet been asked
     * for concepts are absent from the result.</p>
     *
     * @param book the name of the book to query
     * @return a map where each key is a section number and the value is a list of the concepts explained in that
     * section
     */
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

    /**
     * Returns a mapping from section numbers to the concepts each section explains, including the lists of sentence
     * numbers that explain each concept.
     *
     * <p>If a concept is listed without its explanatory sentences, the list of sentences is {@code null}.
     *
     * @param book the name of the book
     * @return a map where each key is a section number and each value is a list of {@link ListedConcept} objects for
     * that section</p>
     */
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
     * Represents a single relation between two concepts within a section, describing how the {@code child} concept
     * relates to the {@code parent} concept.
     *
     * <p>The relation is characterized by:
     * <ul>
     * <li>{@code kind}: the type of relation, such as {@link #IS_A}, {@link #PART_OF}, {@link #FEATURE}, {@link
     * #OPERATION}, or {@link #OTHER}.</li>
     * <li>{@code child}: the name of the subordinate concept.</li>
     * <li>{@code parent}: the name of the primary concept.</li>
     * <li>{@code sentences}: the numbers of the sentences that establish the relation.</li>
     * <li>{@code review}: a verdict from step 7, such as {@link #KEEP}, {@link #REVERSE}, or {@link #DROP}.</li>
     * </ul>
     *
     * <p>Returns a new {@code Relation} instance with the properties adjusted according to the review: if the review
     * is {@link #KEEP} or {@code null}, the relation is unchanged; if {@link #REVERSE}, the {@code child} and {@code
     * parent} are swapped; if {@link #DROP}, the method returns {@code null}.
     *
     * @param kind the type of relation
     * @param child the name of the subordinate concept
     * @param parent the name of the primary concept
     * @param sentences the numbers of the sentences that establish the relation
     * @param review the verdict from step 7
     */
    public record Relation(String kind, String child, String parent, List<Integer> sentences, String review) {
        public static final String IS_A = "is-a";
        public static final String PART_OF = "part-of";
        public static final String FEATURE = "feature";
        /**
         * Represents a relation where one concept is an operation or method acting on the other, such as {@code
         * containsValue} on a Map.
         */
        public static final String OPERATION = "operation";
        /**
         * Related, but not as a kind, a part, a feature or an operation: a way of doing the other, a use of it, a rule
         * about it.
         */
        public static final String OTHER = "other";
        /** Indicates that a relation is valid and should be preserved in the final frame data. */
        public static final String KEEP = "keep";
        public static final String REVERSE = "reverse";
        public static final String DROP = "drop";

        /** Creates a relation that has not yet been reviewed. */
        public Relation(String kind, String child, String parent, List<Integer> sentences) {
            this(kind, child, parent, sentences, null);
        }

        /**
         * Returns a version of this relation that reflects the verdict of step 7.
         *
         * <p>If the review is {@code KEEP} or has not been set, returns this relation unchanged. If the review is
         * {@code DROP}, returns {@code null}. If the review is {@code REVERSE}, returns a new relation with the child
         * and parent concepts swapped. If the review is any other value, returns a new relation with the kind updated
         * to that value.</p>
         *
         * @return the relation as it stands after step 7's verdict is applied, or {@code null} if the relation was
         * dropped
         */
        public Relation reviewed() {
            if (review == null || review.equals(KEEP)) return this;
            if (review.equals(DROP)) return null;
            if (review.equals(REVERSE)) return new Relation(kind, parent, child, sentences, review);
            return new Relation(review, child, parent, sentences, review);
        }
    }

    /**
     * Replaces the relations between one section's concepts; an empty list records that the section was queried and no
     * relations were found.
     *
     * @param book the name of the book
     * @param number the section number
     * @param relations the list of relations to store; if empty, a single dummy relation is stored with ordinal -1 to
     * mark the section as queried
     */
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

    /**
     * One rule of a section's rule file that says a property of a concept or a behavior of it.
     *
     * @param kind whether it is a {@link #PROPERTY} or a {@link #BEHAVIOR}
     * @param rule the text of the rule itself
     * @param text the text of the sentence the rule describes
     */
    public record FrameRule(String kind, String rule, String text) {
        public static final String PROPERTY = "property";
        public static final String BEHAVIOR = "behavior";
    }

    /**
     * Represents a concept within a section's concrete frame, containing the rules that define its properties or
     * behaviors, its retention status, and any features or parts folded into it.
     *
     * @param concept the name of the concept
     * @param rules the list of {@link FrameRule}s that describe the concept
     * @param kept whether the concept is kept in the registry, or {@code null} until reviewed
     * @param folded the names of features or parts folded into this concept, whose rules are also included in the
     * concept's own rules
     */
    public record FrameConcept(String concept, List<FrameRule> rules, Boolean kept, List<String> folded) {
        public FrameConcept(String concept, List<FrameRule> rules, Boolean kept) {
            this(concept, rules, kept, List.of());
        }
    }

    /**
     * Replaces the concepts of one section's concrete frame and the rules collected for each.
     *
     * <p>One instance may serve several workflows or threads at once, but the method is synchronized.</p>
     *
     * @param book the name of the book
     * @param number the section number
     * @param concepts the list of concepts, each with its rules, whether it is kept, and any folded features or parts
     */
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
     * Returns a {@link FrameConcept} containing the rules, kept status, and folded features for a specific concept
     * within a section, or {@code null} if the concept is not found.
     *
     * @param book the name of the book
     * @param number the section number
     * @param concept the name of the concept
     * @return a {@link FrameConcept} representing the concept's material, or {@code null} if no such concept exists in
     * the specified section
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

    /**
     * Returns a map of each section's concrete-frame concepts and their associated rules, keyed by section number.
     *
     * <p>The map's values are lists of {@link FrameConcept} objects, ordered by the section's internal ordinal. Each
     * {@link FrameConcept} contains the rules that define the concept's properties or behaviors, the decision on
     * whether to keep the concept, and any folded features or parts.</p>
     *
     * @param book the name of the book to query
     */
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

    /**
     * Records a single appearance of a concept within a section.
     *
     * @param number the section number
     * @param concept the name of the concept
     */
    public record Occurrence(String number, String concept) {
    }

    /**
     * Represents a concept recognized in the book, either as a specific concept appearing in sections ({@link
     * #CONCEPT}) or as a generalized concept ({@link #GENERAL}) that sits above its kinds in the hierarchy.
     *
     * @param id the unique identifier for the concept or generalized concept
     * @param name the name of the concept
     * @param level the level of the concept (e.g., concept or general)
     * @param members the list of occurrences in sections that provide the material for this concept
     * @param children the list of IDs for the kinds of this generalized concept
     */
    public record Entity(String id, String name, String level, List<Occurrence> members, List<String> children) {
        public static final String CONCEPT = "concept";
        public static final String GENERAL = "general";
    }

    /**
     * Replaces the concepts of the book at the specified level, removing any existing items associated with those
     * concepts.
     *
     * <p>The operation deletes all entries in {@code concept_entity_item} that belong to the concepts being replaced,
     * then deletes the existing entries in {@code concept_entity} for the given book and level, and finally inserts
     * the new list of {@code entities}.</p>
     *
     * @param book the name of the book
     * @param level the level of the concepts to replace (e.g., "concept" or "general")
     * @param entities the new list of entities to store
     * @throws SQLException if a database access error occurs
     */
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

    /**
     * Returns the concepts of a book, ordered by level and then by ID.
     *
     * <p>The list contains {@link Entity} objects, where each entity represents a concept (either a specific concept
     * or a generalized concept) and includes its name, level, the appearances of that concept in the book (as {@link
     * Occurrence}s), and the IDs of its children in the concept tree.</p>
     *
     * @param book the name of the book
     * @return a list of the book's concepts, ordered by level and then by ID
     */
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

    /**
     * Replaces the items of one concept of the book.
     *
     * <p>One instance may serve several workflows or threads at once.</p>
     */
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

    /**
     * Returns a mapping from each generalized frame's ID to its list of items, where each item consists of a question
     * and the rules that answer it.
     *
     * @param book the name of the book to search in
     * @return a map where each key is a frame ID and the value is a list of {@link FrameItem}s belonging to that frame
     */
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

    /**
     * A concrete frame put into a generalized frame of the registry: the book, the section and the concept it is of.
     *
     * @param book the name of the book
     * @param number the section number
     * @param concept the name of the concept
     */
    public record RegistryMember(String book, String number, String concept) {
    }

    /**
     * Represents a generalized frame in the registry, containing its identity, name, position in the concept tree, and
     * the concrete frames it contains.
     *
     * @param id the unique identifier for the generalized frame
     * @param name the name of the generalized frame
     * @param parent the identifier of the generalized frame above this one in the tree, or {@code null} if this is a
     * top-level frame
     *
     * @param members the list of concrete frames (as {@link RegistryMember}s) that belong to this generalized frame
     */
    public record RegistryFrame(String id, String name, String parent, List<RegistryMember> members) {
    }

    /**
     * Returns the generalized frames of the registry, in the order they were created, each with its concrete frames.
     *
     * <p>Each {@link RegistryFrame} in the list contains its ID, name, the ID of the frame above it in the concept
     * tree, and a list of {@link RegistryMember}s representing the concrete frames from books that belong to it.</p>
     */
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

    /**
     * Adds a new generalized frame to the registry with no concrete frame members yet.
     *
     * @param id the unique identifier for the frame
     * @param name the name of the frame
     * @param seq the sequence number for ordering frames in the registry
     * @throws SQLException if a database access error occurs
     */
    public synchronized void addRegistryFrame(String id, String name, int seq) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO frame_registry (id, name, parent, seq) VALUES (?, ?, NULL, ?)")) {
            p.setString(1, id);
            p.setString(2, name);
            p.setInt(3, seq);
            p.executeUpdate();
        }
    }

    /**
     * Associates a concrete frame with a generalized frame in the registry, replacing any previous association for
     * that specific frame.
     *
     * @param id the ID of the generalized frame to receive the member
     * @param m the concrete frame to add, specified by its book, section number, and concept name
     * @param seq the sequence number for the new member in the registry frame's list
     * @throws SQLException if a database access error occurs
     */
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

    /**
     * Updates the name of a generalized frame in the registry.
     *
     * @param id the identifier of the frame to rename
     * @param name the new name for the frame
     * @throws SQLException if a database access error occurs
     */
    public synchronized void renameRegistryFrame(String id, String name) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE frame_registry SET name = ? WHERE id = ?")) {
            p.setString(1, name);
            p.setString(2, id);
            p.executeUpdate();
        }
    }

    /**
     * Sets the parent of a generalized frame in the concept tree.
     *
     * If {@code parent} is {@code null}, the frame is moved to the top of the tree.
     *
     * @param id the identifier of the generalized frame to move
     * @param parent the identifier of the new parent frame, or {@code null} to move it to the top
     * @throws SQLException if a database access error occurs
     */
    public synchronized void setRegistryParent(String id, String parent) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("UPDATE frame_registry SET parent = ? WHERE id = ?")) {
            p.setString(1, parent);
            p.setString(2, id);
            p.executeUpdate();
        }
    }

    /**
     * A relation between two generalized frames of the registry as identified in a section of a book.
     *
     * @param from the ID of the first generalized frame
     * @param kind the type of relation, such as {@link Relation#IS_A}, {@link Relation#PART_OF}, or {@link
     * Relation#FEATURE}
     *
     * @param to the ID of the second generalized frame
     * @param book the name of the book
     * @param number the section number
     */
    public record FrameRelation(String from, String kind, String to, String book, String number) {
    }

    /**
     * Replaces the relations a book's sections gave between generalized frames of the registry.
     *
     * @param book the name of the book
     * @param rels the list of relations to store, each containing the source and target frame IDs, the kind of
     * relation, and the book and section where it was found
     *
     * @throws SQLException if a database access error occurs
     */
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

    /**
     * Returns a list of every relation between generalized frames of the registry, ordered by the starting frame ID,
     * the relation kind, and the target frame ID.
     */
    public synchronized List<FrameRelation> frameRelations() throws SQLException {
        List<FrameRelation> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT from_id, kind, to_id, book, number FROM frame_relation ORDER BY from_id, kind, to_id")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new FrameRelation(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
            }
        }
        return out;
    }

    /**
     * Removes all data associated with the generalized frames registry, including the frames themselves, their
     * concrete members, their items, and all relations between them.
     *
     * <p>A caller gets a clean registry state where no generalized frames or their associated data exist.</p>
     */
    public synchronized void clearRegistry() throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute("DELETE FROM frame_relation");
            st.execute("DELETE FROM frame_registry_item");
            st.execute("DELETE FROM frame_registry_member");
            st.execute("DELETE FROM frame_registry");
        }
    }

    /**
     * Replaces the questions and their answering rules for a specific generalized frame in the registry.
     *
     * @param id the identifier of the generalized frame
     * @param items the list of questions and the rules that answer them
     */
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

    /**
     * Returns a mapping of each generalized frame's ID to its list of items, where each item contains a question and
     * the rules that answer it.
     *
     * <p>The map is ordered by the frame ID, and the list of items for each frame is ordered by their ordinal value.
     * If a frame has no items, it is not present in the map.</p>
     *
     * @return a map where each key is a frame ID and each value is a list of {@link FrameItem} objects belonging to
     * that frame
     */
    public synchronized java.util.Map<String, List<FrameItem>> registryItems() throws SQLException {
        java.util.Map<String, List<FrameItem>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id, question, rules FROM frame_registry_item ORDER BY id, ordinal")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new FrameItem(r.getString(2), List.of(r.getString(3).split(","))));
            }
        }
        return out;
    }

    /**
     * Represents one item of a concept's concrete frame: a question its explanation answers and the rules that answer
     * it.
     *
     * @param question the text of the question
     * @param rules the list of rule names that answer the question
     */
    public record FrameItem(String question, List<String> rules) {
    }

    /**
     * Replaces the items of one concept of one section.
     *
     * @param book the name of the book
     * @param number the section number
     * @param concept the name of the concept
     * @param items the list of items to store, each containing a question and the rules that answer it
     */
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

    /**
     * Returns the questions and the rules that answer them for every concept in a book, organized by section number
     * and then by concept name.
     *
     * <p>The result is a nested map where the first key is the section number, the second key is the concept name, and
     * the value is a list of {@link FrameItem} objects containing the questions and their corresponding rule names,
     * ordered by section and then by the order in which they were recorded.</p>
     *
     * @param book the name of the book
     * @return a nested map of section numbers to maps of concept names to lists of {@link FrameItem}s
     */
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

    /**
     * Replaces the relations between concepts in a specific section of a book and records that the section has been
     * processed.
     *
     * @param book the name of the book
     * @param number the section number
     * @param rels the list of relations to store, where each relation contains the source and target concepts and the
     * rule that connects them
     */
    public synchronized void saveRelationsOfConcepts(String book, String number, List<ConceptRelations.Relation> rels) throws SQLException {
        try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_relation WHERE book = ? AND number = ?")) {
            d.setString(1, book);
            d.setString(2, number);
            d.executeUpdate();
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO concept_relation (book, number, ordinal, group_ordinal, source, link, "
                + "from_concept, to_concept, rule) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            for (ConceptRelations.Relation x : rels) {
                p.setString(1, book);
                p.setString(2, number);
                p.setInt(3, ++i);
                p.setInt(4, x.group());
                p.setString(5, x.source());
                p.setString(6, x.link());
                p.setString(7, x.from());
                p.setString(8, x.to());
                p.setString(9, x.rule());
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = db.prepareStatement("MERGE INTO concept_relation_asked (book, number) KEY (book, number) VALUES (?, ?)")) {
            p.setString(1, book);
            p.setString(2, number);
            p.executeUpdate();
        }
    }

    /**
     * Returns a set of section numbers for which the relations between concepts have been queried, including sections
     * where no relations were found.
     *
     * @param book the name of the book
     * @return a set of section numbers that have been asked for relations
     */
    public synchronized java.util.Set<String> relationsAsked(String book) throws SQLException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number FROM concept_relation_asked WHERE book = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(r.getString(1));
            }
        }
        return out;
    }

    /**
     * Returns a mapping of section numbers to the relations between the concepts explained in those sections.
     *
     * <p>The map's keys are the section numbers, and the values are lists of {@link ConceptRelations.Relation}
     * objects, ordered by the order they were read from the database. If a section was queried but no relations were
     * found, the list is empty.</p>
     *
     * @param book the name of the book to query
     * @return a map where each key is a section number and each value is a list of relations found in that section, in
     * the order they were read
     */
    public synchronized java.util.Map<String, List<ConceptRelations.Relation>> relationsOfConcepts(String book) throws SQLException {
        java.util.Map<String, List<ConceptRelations.Relation>> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT number, group_ordinal, source, link, from_concept, to_concept, rule FROM concept_relation "
                + "WHERE book = ? ORDER BY number, ordinal")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new ConceptRelations.Relation(r.getInt(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7)));
                }
            }
        }
        return out;
    }

    /**
     * Stores a unit's embedding vector by the model that produced it, using the unit's main concept as the text.
     *
     * @param model the name of the model used to create the embedding
     * @param u the unit to associate with the embedding, providing its book, section number, and ordinal
     * @param v the embedding vector as an array of floats
     * @throws SQLException if a database access error occurs
     */
    public synchronized void saveUnitEmbedding(String model, SimilarConcepts.Unit u, float[] v) throws SQLException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(v.length * 4);
        for (float f : v) b.putFloat(f);
        try (PreparedStatement p = db.prepareStatement("MERGE INTO unit_embedding (model, book, number, ordinal, text, vector) KEY (model, book, number, ordinal) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setString(1, model);
            p.setString(2, u.book());
            p.setString(3, u.number());
            p.setInt(4, u.ordinal());
            p.setString(5, u.concept());
            p.setBytes(6, b.array());
            p.executeUpdate();
        }
    }

    /**
     * Returns a map of embeddings produced by the specified model, where each key is a string in the format {@code
     * book|number|ordinal\ttext} and each value is the corresponding vector. A unit whose text has changed since the
     * last embedding is not included in the result.
     *
     * @param model the name of the model that produced the embeddings
     */
    public synchronized java.util.Map<String, float[]> unitEmbeddings(String model) throws SQLException {
        java.util.Map<String, float[]> out = new java.util.HashMap<>();
        try (PreparedStatement p = db.prepareStatement("SELECT book, number, ordinal, text, vector FROM unit_embedding WHERE model = ?")) {
            p.setString(1, model);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(r.getBytes(5));
                    float[] v = new float[b.capacity() / 4];
                    for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
                    out.put(r.getString(1) + "|" + r.getString(2) + "|" + r.getInt(3) + "\t" + r.getString(4), v);
                }
            }
        }
        return out;
    }

    /**
     * Replaces the similarities found for a unit under a specific criterion and marks the unit as judged.
     *
     * @param criterion the name of the criterion used to judge similarity
     * @param u the unit being judged, identified by its book, number, and ordinal
     * @param similar the list of similar units, each containing the other unit's book, number, ordinal, and concept,
     * and the common concept they share
     */
    public synchronized void saveSimilar(String criterion, SimilarConcepts.Unit u, List<SimilarConcepts.Similar> similar) throws SQLException {
        try (PreparedStatement d = db.prepareStatement("DELETE FROM concept_similarity WHERE criterion = ? AND book = ? AND number = ? AND ordinal = ?")) {
            d.setString(1, criterion);
            d.setString(2, u.book());
            d.setString(3, u.number());
            d.setInt(4, u.ordinal());
            d.executeUpdate();
        }
        try (PreparedStatement p = db.prepareStatement("MERGE INTO concept_similarity (criterion, book, number, ordinal, concept, other_book, other_number, "
                + "other_ordinal, other_concept, common) KEY (criterion, book, number, ordinal, other_book, other_number, other_ordinal) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (SimilarConcepts.Similar x : similar) {
                p.setString(1, criterion);
                p.setString(2, u.book());
                p.setString(3, u.number());
                p.setInt(4, u.ordinal());
                p.setString(5, u.concept());
                p.setString(6, x.other().book());
                p.setString(7, x.other().number());
                p.setInt(8, x.other().ordinal());
                p.setString(9, x.other().concept());
                p.setString(10, x.common());
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = db.prepareStatement("MERGE INTO concept_similarity_judged (criterion, book, number, ordinal, concept) "
                + "KEY (criterion, book, number, ordinal) VALUES (?, ?, ?, ?, ?)")) {
            p.setString(1, criterion);
            p.setString(2, u.book());
            p.setString(3, u.number());
            p.setInt(4, u.ordinal());
            p.setString(5, u.concept());
            p.executeUpdate();
        }
    }

    /**
     * Returns a set of strings representing the units that have been judged under the specified criterion, where each
     * string is formatted as "book|number|ordinal\tconcept". A unit is not included if it has been renamed since it
     * was judged.
     *
     * @param criterion the name of the criterion used for judging similarity
     * @return a set of strings, each being a unit identifier and its concept in the format
     * "book|number|ordinal\tconcept"
     *
     * @throws SQLException if a database access error occurs
     */
    public synchronized java.util.Set<String> similarJudged(String criterion) throws SQLException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement p = db.prepareStatement("SELECT book, number, ordinal, concept FROM concept_similarity_judged WHERE criterion = ?")) {
            p.setString(1, criterion);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getInt(3) + "\t" + r.getString(4));
            }
        }
        return out;
    }

    /**
     * A single similarity between two units as recorded by a specific criterion: the criterion used, the location and
     * concept of the first unit, the location and concept of the second unit, and the concept they share.
     *
     * @param criterion the name of the criterion used to judge the similarity
     * @param book the name of the first book
     * @param number the section number of the first unit
     * @param ordinal the unit's ordinal within the section
     * @param concept the name of the concept in the first unit
     * @param otherBook the name of the second book
     * @param otherNumber the section number of the second unit
     * @param otherOrdinal the unit's ordinal within the section
     * @param otherConcept the name of the concept in the second unit
     * @param common the name of the more general concept shared by both
     */
    public record SimilarRow(String criterion, String book, String number, int ordinal, String concept, String otherBook, String otherNumber,
            int otherOrdinal, String otherConcept, String common) {}

    /**
     * Lists every similarity judged under any criterion, including the two units involved, their concepts, and the
     * commonality they share.
     *
     * @return a list of {@link SimilarRow} objects, each containing the criterion, the two units (book, number,
     * ordinal), their concepts, and the common concept.
     *
     * @throws SQLException if a database access error occurs.
     */
    public synchronized List<SimilarRow> similarities() throws SQLException {
        List<SimilarRow> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT criterion, book, number, ordinal, concept, other_book, other_number, other_ordinal, "
                + "other_concept, common FROM concept_similarity ORDER BY book, number, ordinal")) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    out.add(new SimilarRow(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getInt(8), r.getString(9), r.getString(10)));
                }
            }
        }
        return out;
    }

    /**
     * Replaces the properties of one section's groups' main concepts and marks the section as having been asked.
     *
     * @param book the name of the book
     * @param number the section number
     * @param props the list of properties to store, each containing a group ordinal, a concept name, a kind, the
     * property text, and the rule name
     */
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

    /**
     * Returns a set of section numbers whose groups' main concepts have had their properties extracted, including
     * sections where no properties were found.
     *
     * @param book the name of the book
     * @return a set of section numbers that have been processed for properties
     */
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

    /**
     * Returns the properties of the main concepts within each section's groups for the specified book, organized by
     * section number.
     *
     * @param book the name of the book
     * @return a mapping from section numbers to lists of {@link ConceptProperties.Property} objects, ordered by the
     * section number and then by the order in which the properties were read
     */
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

    /**
     * Replaces the causes and motives of one section's concepts and marks the section as having been processed.
     *
     * @param book the name of the book
     * @param number the section number
     * @param links the list of causal or motivational links to store
     */
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

    /**
     * Returns the numbers of the sections in the specified book for which the causes and motives of their concepts
     * have already been extracted.
     *
     * @param book the name of the book
     * @return a set of section numbers that have been asked, including those that had no causes or motives to report
     */
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

    /**
     * Returns the causal and motivational links between concepts for each section of a book, organized by section
     * number.
     *
     * @param book the name of the book
     * @return a map where each key is a section number and the value is a list of links, ordered by the order they
     * were read from the database
     */
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
     * Replaces the units of one section's blocks, with all previously recorded causes, properties, and relations for
     * those units being removed.
     *
     * @param book the name of the book
     * @param number the section number
     * @param groups the list of units to record, each containing a type, a main concept, and its constituent blocks
     */
    public synchronized void saveGroups(String book, String number, List<SectionGroups.Group> groups) throws SQLException {
        // what was read of the old groups refers to them by ordinal: it goes with them
        for (String t : List.of("section_group", "concept_cause", "concept_cause_asked", "concept_property", "concept_property_asked",
                "concept_relation", "concept_relation_asked")) {
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

    /**
     * Returns the groups of explanation units for each section of a book, organized by section number and then by the
     * order of the groups.
     *
     * @param book the name of the book
     * @return a mapping from section numbers to lists of {@link SectionGroups.Group}, where each list contains the
     * units of that section in their original order
     */
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

    /**
     * Replaces how one section explains its main concept.
     *
     * @param book the book's name
     * @param number the section's number
     * @param layout the layout containing the main concept, the pattern, and the members (each a block, a relation,
     * and a name)
     *
     * @throws SQLException if a database access error occurs
     */
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

    /**
     * Returns a mapping of section numbers to the layout of how each section explains its main concept, for all
     * sections in the specified book that have been processed.
     *
     * @param book the name of the book
     * @return a map where each key is a section number and each value is a {@link SectionPattern.Layout} describing
     * the pattern of blocks, their relations, and their names used to explain the section's main concept
     */
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

    /**
     * Updates the verdicts for each relation in one section of a book.
     *
     * @param book the name of the book
     * @param number the section number
     * @param verdicts the list of verdicts (such as keep, reverse, or drop) to be applied to the relations, in the
     * same order as they are returned by {@link #relations(String)}
     */
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

    /**
     * Returns a mapping from each section number to a list of the relations between the concepts explained in that
     * section.
     *
     * @param book the name of the book
     * @return a map where each key is a section number and the value is a list of {@link Relation} objects
     * representing the connections between concepts; a section that was queried but found no relations returns an
     * empty list
     */
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

    /**
     * Represents the verdict of step 5 on a concept, stating whether it is kept and the reason for that decision.
     *
     * @param concept the name of the concept
     * @param kept whether the concept is kept ({@code null} until judged)
     * @param reason the explanation for the decision
     */
    public record JudgedConcept(String concept, Boolean kept, String reason) {
    }

    /**
     * Updates the decision for each concept in a section, specifying whether it is kept and the reason for that
     * decision.
     *
     * @param book the name of the book
     * @param number the section number
     * @param judged the list of judgements to record, in the order the concepts were originally listed
     * @throws SQLException if a database error occurs
     */
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

    /**
     * Returns a map of each section's concepts along with their step 5 judgement, grouped by section number.
     *
     * @param book the name of the book
     * @return a map where each key is a section number and the value is a list of {@link JudgedConcept}s for that
     * section, ordered by their original appearance in the section
     */
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

    /**
     * Returns the directory path of the specified book, or {@code null} if the book is not found in the database.
     *
     * @param book the name of the book
     */
    public String dirOf(String book) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT dir FROM book WHERE name = ?")) {
            p.setString(1, book);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    /**
     * Returns a list of the names of all books currently in the database, sorted alphabetically.
     *
     * @return a list of book names
     */
    public List<String> books() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT name FROM book ORDER BY name")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    /**
     * Returns a list of the book's table of contents nodes, ordered by their position in the book.
     *
     * @param book the name of the book
     */
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

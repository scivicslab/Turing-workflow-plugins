package com.scivicslab.turingworkflow.plugins.rstframe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of the books database and its version, kept in the table {@code schema_version}. Only a writer makes or
 * changes the shape ({@link BookStore#forWriting}); a reader ({@link BookStore#forReading}) checks the version and runs
 * no DDL, so that no reader changes the shape of a database other processes are using.
 *
 * <p>The versions:</p>
 * <ul><li>none: a database from before the versions. Its tables were made by statements every {@code BookStore} ran when
 * it opened, and its text columns are CLOBs. {@code BookImport migrate} brings it to the current version, after backing
 * it up; a writer does not, since the conversion rewrites every text and needs the backup.</li>
 * <li>2: every text column is a {@code VARCHAR(1000000)}, and the table {@code data_version} counts the changes. H2 copies
 * a CLOB it reads into a temporary large object in the database file, so reading CLOBs wrote and grew the file;
 * reading a {@code VARCHAR} writes nothing.</li></ul>
 */
public final class BookSchema {

    /** The version this code writes and reads. */
    public static final int VERSION = 2;

    /** The longest text a column holds: the longest section rule file in the books was 181,647 characters. */
    public static final int TEXT = 1_000_000;

    private BookSchema() {
    }

    /** What a database holds, as far as its shape goes. */
    public enum State {
        /** no table at all: a new database */
        EMPTY,
        /** tables, but no {@code schema_version}: a database from before the versions */
        UNVERSIONED,
        /** a {@code schema_version} table, whose number {@link #version} reads */
        VERSIONED
    }

    /** Whether the database is new, from before the versions, or versioned. Runs no DDL. */
    public static State state(Connection db) throws SQLException {
        boolean any = false, versioned = false;
        try (Statement s = db.createStatement();
                ResultSet r = s.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (r.next()) {
                any = true;
                if (r.getString(1).equalsIgnoreCase("schema_version")) versioned = true;
            }
        }
        return versioned ? State.VERSIONED : any ? State.UNVERSIONED : State.EMPTY;
    }

    /** The version a versioned database is at. Runs no DDL. */
    public static int version(Connection db) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT version FROM schema_version")) {
            if (!r.next()) throw new SQLException("schema_version has no row");
            return r.getInt(1);
        }
    }

    /**
     * Makes sure a writer may write: makes the tables of a new database, and leaves a database at {@link #VERSION} as it
     * is, without any DDL. Refuses a database from before the versions, which {@code BookImport migrate} converts after
     * backing it up, and one newer than this code.
     */
    static void prepareForWriting(Connection db, String where) throws SQLException {
        switch (state(db)) {
            case EMPTY -> {
                tables(db);
                versionTables(db);
            }
            case UNVERSIONED -> throw new SQLException(where + " is from before the schema versions (its text is in CLOB columns); "
                    + "run BookImport migrate on it first, which backs it up and converts it to version " + VERSION);
            case VERSIONED -> {
                int v = version(db);
                if (v != VERSION) throw new SQLException(where + " is at schema version " + v + ", this writer writes version " + VERSION);
            }
        }
    }

    /** Makes sure a reader may read: the database is at {@link #VERSION}. Runs no DDL. */
    static void checkForReading(Connection db, String where) throws SQLException {
        State st = state(db);
        if (st != State.VERSIONED) {
            throw new SQLException(where + (st == State.EMPTY ? " has no tables yet" : " is from before the schema versions (its text is in CLOB columns); "
                    + "run BookImport migrate on it") + "; this reader reads schema version " + VERSION);
        }
        int v = version(db);
        if (v != VERSION) throw new SQLException(where + " is at schema version " + v + ", this reader reads version " + VERSION);
    }

    /**
     * Converts a database from before the versions: adds the tables and columns it lacks, turns every CLOB column into a
     * {@code VARCHAR(1000000)}, and adds the version tables. The caller has backed the database up.
     *
     * @return the columns converted, as {@code table.column}
     */
    static List<String> migrateUnversioned(Connection db) throws SQLException {
        if (state(db) != State.UNVERSIONED) throw new SQLException("not a database from before the schema versions: " + state(db));
        tables(db);
        List<String> converted = new ArrayList<>();
        for (String[] c : clobColumns(db)) {
            try (Statement s = db.createStatement()) {
                s.execute("ALTER TABLE \"" + c[0] + "\" ALTER COLUMN \"" + c[1] + "\" SET DATA TYPE VARCHAR(" + TEXT + ")");
            }
            converted.add(c[0] + "." + c[1]);
        }
        versionTables(db);
        return converted;
    }

    /** The CLOB columns of the database's tables, as table and column. */
    static List<String[]> clobColumns(Connection db) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE = 'CHARACTER LARGE OBJECT' ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            while (r.next()) out.add(new String[] {r.getString(1), r.getString(2)});
        }
        return out;
    }

    /** The tables {@code schema_version} and {@code data_version}, at {@link #VERSION} and no change counted. */
    private static void versionTables(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            // the number of changes written: every write of BookStore adds one in its own transaction, and a reader that
            // keeps what it read checks this number instead of the file, which H2 writes for other reasons too
            s.execute("CREATE TABLE IF NOT EXISTS data_version (id INT PRIMARY KEY, version BIGINT NOT NULL)");
            s.execute("MERGE INTO data_version (id, version) KEY (id) VALUES (1, 0)");
            s.execute("CREATE TABLE schema_version (version INT NOT NULL)");
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
            p.setInt(1, VERSION);
            p.executeUpdate();
        }
    }

    /**
     * The tables of the books database, made when they are missing, and the columns later steps added, added when they
     * are missing. On a database from before the versions these are the statements every {@code BookStore} used to run when
     * it opened; the text columns of a table made here are {@code VARCHAR}s from the start.
     */
    private static void tables(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS book (name VARCHAR(200) PRIMARY KEY, dir VARCHAR(2000) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS toc_node (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, level VARCHAR(20) NOT NULL, title VARCHAR(2000) NOT NULL, parent VARCHAR(40), "
                    + "first_file VARCHAR(2000) NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_text (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "text VARCHAR(1000000) NOT NULL, files VARCHAR(1000000) NOT NULL, chars INT NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_rules (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "rules VARCHAR(1000000) NOT NULL, questions INT NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_code (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "name VARCHAR(200) NOT NULL, text VARCHAR(1000000) NOT NULL, PRIMARY KEY (book, number, name))");
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
                    + "text VARCHAR(1000000) NOT NULL, PRIMARY KEY (book, number, concept, ordinal))");
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
                    + "name VARCHAR(1000) NOT NULL, level VARCHAR(20) NOT NULL, members VARCHAR(1000000), children VARCHAR(1000000), PRIMARY KEY (book, id))");
            // the items of a concept of the book: the questions, each with the sentences ("number:rule") that answer it
            s.execute("CREATE TABLE IF NOT EXISTS concept_entity_item (book VARCHAR(200) NOT NULL, id VARCHAR(1000) NOT NULL, "
                    + "ordinal INT NOT NULL, question VARCHAR(2000) NOT NULL, rules VARCHAR(1000000) NOT NULL, PRIMARY KEY (book, id, ordinal))");
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
                    + "question VARCHAR(2000) NOT NULL, rules VARCHAR(1000000) NOT NULL, PRIMARY KEY (id, ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS section_pattern (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "concept VARCHAR(1000) NOT NULL, pattern VARCHAR(20) NOT NULL, members VARCHAR(1000000), PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE IF NOT EXISTS section_group (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, "
                    + "ordinal INT NOT NULL, type VARCHAR(20) NOT NULL, concept VARCHAR(1000), members VARCHAR(1000000) NOT NULL, "
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
                    + "number VARCHAR(40) NOT NULL, ordinal INT NOT NULL, text VARCHAR(1000000) NOT NULL, vector VARBINARY(16384) NOT NULL, "
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
}

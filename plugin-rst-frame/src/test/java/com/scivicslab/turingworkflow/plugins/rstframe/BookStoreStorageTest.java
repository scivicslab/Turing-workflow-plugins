package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the books database is stored: reading it writes nothing to its file, every write is counted in {@code data_version},
 * a reader runs no DDL, and {@code BookImport migrate} converts a database from before the schema versions without
 * changing what it holds. Each test uses its own H2 file in a temporary directory.
 */
class BookStoreStorageTest {

    @TempDir
    Path dir;

    static final int SECTIONS = 100;

    /** A text of {@code n} characters that differs from section to section. */
    static String text(int section, int n) {
        StringBuilder sb = new StringBuilder(n);
        while (sb.length() < n) sb.append("Section ").append(section).append(" explains a class. ");
        return sb.substring(0, n);
    }

    /** A book of {@link #SECTIONS} sections of 30,000 characters each, each with a rule file of 20,000. */
    void writeBook(String url) throws SQLException {
        try (BookStore store = BookStore.forWriting(url)) {
            List<BookOutline.Node> nodes = new ArrayList<>();
            List<BookSections.Section> sections = new ArrayList<>();
            for (int i = 1; i <= SECTIONS; i++) {
                nodes.add(new BookOutline.Node(String.valueOf(i), "chapter", "Chapter " + i, null, "c" + i + ".md"));
                sections.add(new BookSections.Section(String.valueOf(i), text(i, 30_000), List.of("c" + i + ".md")));
            }
            store.saveOutline("core", "/w/books/core", new BookOutline.Outline(nodes, List.of()));
            store.saveSections("core", sections);
            for (int i = 1; i <= SECTIONS; i++) {
                store.saveSectionRules("core", new BookStore.SectionRules(String.valueOf(i), "(defrule b1 -> \"" + text(i, 20_000) + "\")", Map.of(), 1));
            }
        }
    }

    /** The number of writes H2 has made to the open database's file. */
    static long fileWrites(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'info.FILE_WRITE'")) {
            r.next();
            return Long.parseLong(r.getString(1));
        }
    }

    /** Lets H2 finish what it writes on its own after a database is opened, so that it is not counted as the reads'. */
    static void settle() throws InterruptedException {
        Thread.sleep(2000);
    }

    @Test
    void readingTheBooksWritesNothingToTheFile() throws Exception {
        String url = "jdbc:h2:" + dir.resolve("books");
        writeBook(url);
        Path file = dir.resolve("books.mv.db");
        try (BookStore store = BookStore.forReading(url); Connection counter = BookStore.openH2(url)) {
            settle();
            long writes = fileWrites(counter), size = Files.size(file);
            long chars = 0;
            for (int rep = 0; rep < 20; rep++) {
                for (BookSections.Section s : store.sections("core")) chars += s.text().length();
                for (int i = 1; i <= SECTIONS; i++) chars += store.sectionRules("core", String.valueOf(i)).rules().length();
            }
            settle();
            assertTrue(chars > 90_000_000, "the reads read the text: " + chars);
            assertEquals(writes, fileWrites(counter), "H2 wrote the file while the books were only read");
            assertEquals(size, Files.size(file));
        }
    }

    /** The same reads of the same text in a CLOB column write the file: what the VARCHAR columns are for, and what the test above would see. */
    @Test
    void readingTheSameTextFromAClobColumnWritesTheFile() throws Exception {
        String url = "jdbc:h2:" + dir.resolve("clob");
        try (Connection c = BookStore.openH2(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE section_text (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, text CLOB NOT NULL, PRIMARY KEY (book, number))");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO section_text VALUES ('core', ?, ?)")) {
                for (int i = 1; i <= SECTIONS; i++) {
                    p.setString(1, String.valueOf(i));
                    p.setString(2, text(i, 30_000));
                    p.executeUpdate();
                }
            }
        }
        try (Connection c = BookStore.openH2(url)) {
            settle();
            long writes = fileWrites(c);
            for (int rep = 0; rep < 20; rep++) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT text FROM section_text")) {
                    while (r.next()) r.getString(1);
                }
            }
            settle();
            assertTrue(fileWrites(c) > writes, "reading CLOBs is expected to write the file: " + writes + " -> " + fileWrites(c));
        }
    }

    @Test
    void everyWriteIsCountedInDataVersionAndAReadIsNot() throws Exception {
        String url = "jdbc:h2:" + dir.resolve("books");
        try (BookStore store = BookStore.forWriting(url)) {
            assertEquals(0, store.dataVersion(), "a new database");
            store.saveOutline("core", "/w/books/core", new BookOutline.Outline(List.of(new BookOutline.Node("1", "chapter", "One", null, "a.md")), List.of()));
            assertEquals(1, store.dataVersion(), "a write in a transaction of its own");
            store.addRegistryFrame("F0001", "class", 1);
            assertEquals(2, store.dataVersion(), "a write that used to run without a transaction");
            store.sections("core");
            store.outline("core");
            store.registry();
            assertEquals(2, store.dataVersion(), "reads are not counted");
            // a text longer than a column holds is refused, and the failed write leaves no trace
            SQLException e = assertThrows(SQLException.class,
                    () -> store.saveSections("core", List.of(new BookSections.Section("1", "x".repeat(BookSchema.TEXT + 1), List.of("a.md")))));
            assertTrue(e.getMessage().contains("too long"), e.getMessage());
            assertEquals(2, store.dataVersion(), "a failed write is not counted");
            assertEquals(List.of(), store.sections("core"));
        }
        try (BookStore reader = BookStore.forReading(url)) {
            assertEquals(2, reader.dataVersion(), "another connection reads the same count");
        }
    }

    /** The tables of the database, by name. */
    static List<String> tables(String url) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = BookStore.openH2(url); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    @Test
    void aReaderRunsNoDdlAndStopsAtADatabaseItCannotRead() throws Exception {
        String empty = "jdbc:h2:" + dir.resolve("empty");
        BookStore.openH2(empty).close();
        SQLException e = assertThrows(SQLException.class, () -> BookStore.forReading(empty));
        assertTrue(e.getMessage().contains("has no tables yet"), e.getMessage());
        assertEquals(List.of(), tables(empty), "the reader made no table");

        String old = "jdbc:h2:" + dir.resolve("old");
        try (Connection c = BookStore.openH2(old); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE book (name VARCHAR(200) PRIMARY KEY, dir VARCHAR(2000) NOT NULL)");
        }
        e = assertThrows(SQLException.class, () -> BookStore.forReading(old));
        assertTrue(e.getMessage().contains("run BookImport migrate"), e.getMessage());
        e = assertThrows(SQLException.class, () -> BookStore.forWriting(old));
        assertTrue(e.getMessage().contains("run BookImport migrate"), "a writer does not convert it either: " + e.getMessage());
        assertEquals(List.of("BOOK"), tables(old), "neither the reader nor the writer changed it");

        String other = "jdbc:h2:" + dir.resolve("other");
        BookStore.forWriting(other).close();
        try (Connection c = BookStore.openH2(other); Statement s = c.createStatement()) {
            s.execute("UPDATE schema_version SET version = 1");
        }
        e = assertThrows(SQLException.class, () -> BookStore.forReading(other));
        assertTrue(e.getMessage().contains("is at schema version 1, this reader reads version " + BookSchema.VERSION), e.getMessage());
    }

    @Test
    void migrateConvertsADatabaseFromBeforeTheVersionsAndKeepsWhatItHolds() throws Exception {
        Path base = dir.resolve("db").resolve("books");
        Files.createDirectories(base.getParent());
        String url = "jdbc:h2:" + base;
        // the shape every BookStore used to make when it opened: text in CLOB columns; and a table an experiment left
        try (Connection c = BookStore.openH2(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE book (name VARCHAR(200) PRIMARY KEY, dir VARCHAR(2000) NOT NULL)");
            s.execute("CREATE TABLE toc_node (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, ordinal INT NOT NULL, level VARCHAR(20) NOT NULL, "
                    + "title VARCHAR(2000) NOT NULL, parent VARCHAR(40), first_file VARCHAR(2000) NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE section_text (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, text CLOB NOT NULL, files CLOB NOT NULL, "
                    + "chars INT NOT NULL, PRIMARY KEY (book, number))");
            s.execute("CREATE TABLE section_concept (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, ordinal INT NOT NULL, "
                    + "concept VARCHAR(1000) NOT NULL, PRIMARY KEY (book, number, ordinal))");
            s.execute("CREATE TABLE section_pattern_ex1 (book VARCHAR(200) NOT NULL, number VARCHAR(40) NOT NULL, members CLOB)");
            s.execute("INSERT INTO book VALUES ('core', '/w/books/core')");
            s.execute("INSERT INTO toc_node VALUES ('core', '1', 0, 'chapter', 'One', NULL, 'a.md'), ('core', '2', 1, 'chapter', 'Two', NULL, 'b.md')");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO section_text VALUES ('core', ?, ?, ?, ?)")) {
                for (int i = 1; i <= 2; i++) {
                    p.setString(1, String.valueOf(i));
                    p.setString(2, text(i, 50_000));
                    p.setString(3, "c" + i + ".md");
                    p.setInt(4, 50_000);
                    p.executeUpdate();
                }
            }
            s.execute("INSERT INTO section_concept VALUES ('core', '1', 0, 'class')");
            s.execute("INSERT INTO section_pattern_ex1 VALUES ('core', '1', 'b1\tpart\ttemplate'), ('core', '2', NULL)");
        }
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        BookMigration.Result r = BookMigration.migrate(base, new PrintStream(log, true));

        assertTrue(Files.isRegularFile(r.backup()), "the backup is kept: " + r.backup());
        assertTrue(r.backup().getParent().equals(base.getParent().resolve("backup")), r.backup().toString());
        assertEquals(r.backupSha256(), BookMigration.sha256(r.backup()));
        assertTrue(Files.readString(Path.of(r.backup() + ".sha256")).startsWith(r.backupSha256()));
        assertEquals(List.of("SECTION_PATTERN_EX1.MEMBERS", "SECTION_TEXT.TEXT", "SECTION_TEXT.FILES"), r.converted());
        assertEquals(2, r.fingerprints().get("SECTION_TEXT").rows());
        assertEquals(2, r.fingerprints().get("SECTION_PATTERN_EX1").rows(), "the experiment's table is kept");
        try (Connection c = BookStore.openH2(url)) {
            assertEquals(List.of(), BookSchema.clobColumns(c), "no CLOB column is left");
            assertEquals(BookSchema.VERSION, BookSchema.version(c));
            // the column a later step added to an old table is there now, empty
            try (Statement s = c.createStatement(); ResultSet q = s.executeQuery("SELECT kept, reason, sentences FROM section_concept")) {
                assertTrue(q.next());
                assertEquals(null, q.getObject(1));
            }
        }
        try (BookStore store = BookStore.forReading(url)) {
            List<BookSections.Section> sections = store.sections("core");
            assertEquals(List.of("1", "2"), sections.stream().map(BookSections.Section::number).toList());
            assertEquals(text(2, 50_000), sections.get(1).text());
            assertEquals(0, store.dataVersion());
        }
        try (BookStore store = BookStore.forWriting(url)) {
            store.saveOutline("core", "/w/books/core", new BookOutline.Outline(List.of(new BookOutline.Node("1", "chapter", "One", null, "a.md")), List.of()));
            assertEquals(1, store.dataVersion(), "a writer writes the migrated database");
        }
        IllegalStateException again = assertThrows(IllegalStateException.class, () -> BookMigration.migrate(base, new PrintStream(new ByteArrayOutputStream())));
        assertTrue(again.getMessage().contains("nothing to migrate"), again.getMessage());
        assertTrue(log.toString().contains("size: "), log.toString());
    }
}

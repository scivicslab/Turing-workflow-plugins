package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code db} argument names a file and nothing else; it cannot add settings to the JDBC URL (chat-86, HIGH 3).
 *
 * <p>The path went into {@code jdbc:h2:<path>;AUTO_SERVER=TRUE} as it was. The {@link FileGate} judged the text as a
 * path and found it under its root, while H2 read {@code ;INIT=...} in it as a setting and ran the SQL: with
 * {@code FILE_READ} and {@code FILE_WRITE} a workflow read and wrote files anywhere the process could, past the
 * gate (chat-86's reproduction read a file outside the root into a table and wrote another).</p>
 */
@Tag("PluginRstFrame_BookDatabasePath_261123_oo01")
@DisplayName("The db argument is a file path, never a JDBC setting")
@Timeout(120)
class BookDatabasePathTest {

    /** A gate that allows only files under one root, as a host that confines workflows would. */
    static class RootGate implements FileGate {
        final Path root;

        RootGate(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        private void check(Path p) {
            if (!p.toAbsolutePath().normalize().startsWith(root)) {
                throw new SecurityException("outside the gate: " + p);
            }
        }

        @Override public String read(Path file) throws IOException { check(file); return Files.readString(file); }
        @Override public void write(Path file, String text) throws IOException { check(file); Files.writeString(file, text); }
        @Override public List<Path> list(Path dir, String suffix, int depth) throws IOException {
            check(dir);
            return FileGate.open().list(dir, suffix, depth);
        }
        @Override public void mayWrite(Path file) { check(file); }
        @Override public boolean exists(Path path) { check(path); return Files.exists(path); }
    }

    private static String injected(Path root, Path secret, Path written) {
        return root.resolve("book") + ";INIT=CREATE TABLE IF NOT EXISTS STOLEN AS SELECT FILE_READ('" + secret
                + "', NULL) AS X" + (char) 92 + ";CALL FILE_WRITE(STRINGTOUTF8('pwned'), '" + written + "')";
    }

    @Test
    @DisplayName("a db argument with ;INIT= is refused before anything is read or written")
    void dbArgumentWithASetting_isRefused_andNothingOutsideTheGateIsTouched(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "outside-the-gate-secret");
        Path written = outside.resolve("written-by-h2.txt");
        Path book = Files.createDirectories(root.resolve("src"));
        Files.writeString(book.resolve("a.md"), "## 1. Title\n\nText.\n");
        BookStructure structure = new BookStructure(new RootGate(root));
        String db = injected(root, secret, written);

        assertThrows(IllegalArgumentException.class, () -> structure.readToc(db, "b", book.toString()));
        assertThrows(IllegalArgumentException.class, () -> structure.cutSections(db, "b"));
        assertThrows(IllegalArgumentException.class, () -> structure.judge(db, "b"));
        assertThrows(IllegalArgumentException.class, () -> structure.prune(db, "b", 1));

        assertFalse(Files.exists(written), "H2 must not have run the setting: it wrote " + written);
        try (var files = Files.list(root)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("book")),
                    "no database file may have been created for the refused argument");
        }
    }

    @Test
    @DisplayName("other characters H2 reads in a URL are refused too")
    void controlCharactersAndSemicolons_areRefused() {
        for (String bad : new String[] {"/tmp/book;AUTO_SERVER=FALSE", "/tmp/book\n;INIT=x", "/tmp/book;", "/tmp/bo\u0000ok"}) {
            assertThrows(IllegalArgumentException.class, () -> H2Url.of(Path.of(bad)), bad);
        }
    }

    @Test
    @DisplayName("an ordinary path still opens a database under the gate")
    void anOrdinaryPath_worksUnderTheGate(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        Path book = Files.createDirectories(root.resolve("src"));
        Files.writeString(book.resolve("a.md"), "## 1. Title\n\nText.\n");
        BookStructure structure = new BookStructure(new RootGate(root));
        String db = root.resolve("db/book").toString();

        String summary = structure.readToc(db, "b", book.toString());

        assertTrue(summary.startsWith("read 1 markdown file(s) of b"), summary);
        assertTrue(Files.exists(Path.of(db + ".mv.db")));
        assertEquals("jdbc:h2:" + Path.of(db).toAbsolutePath() + ";AUTO_SERVER=TRUE", H2Url.of(Path.of(db)));
    }
}

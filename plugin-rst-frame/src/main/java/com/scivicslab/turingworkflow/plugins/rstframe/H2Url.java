package com.scivicslab.turingworkflow.plugins.rstframe;

import java.nio.file.Path;

/**
 * Builds the JDBC URL of a book database from a file path, and refuses a path that is more than a file name.
 *
 * <p>H2 reads {@code jdbc:h2:<path>;<setting>;<setting>} and runs {@code INIT=} as SQL when the database is opened,
 * and SQL can read and write any file the process can ({@code FILE_READ}, {@code FILE_WRITE}). A {@code db}
 * argument that went into the URL as text could therefore carry settings: the {@link FileGate} judged the text as
 * a path under its root, and H2 read the same text as a URL (chat-86, HIGH 3). The path is now checked before
 * the gate is asked and before anything is created: a semicolon, which starts a setting, or a control character
 * is refused.</p>
 *
 * <p>The URL is made from the absolute path, which begins with {@code /}, so it cannot name another kind of
 * database ({@code mem:}, {@code tcp:}, {@code zip:}) either.</p>
 */
final class H2Url {

    private H2Url() {
    }

    /**
     * Returns the URL of the file database at {@code dbFile}, to be opened by one program or, with
     * {@code AUTO_SERVER=TRUE}, by several.
     *
     * @param dbFile the database file path, without {@code .mv.db}
     * @throws IllegalArgumentException if the path holds a semicolon or a control character
     */
    static String of(Path dbFile) {
        check(dbFile);
        return "jdbc:h2:" + dbFile.toAbsolutePath() + ";AUTO_SERVER=TRUE";
    }

    /**
     * Refuses a path that is more than a file name.
     *
     * @param dbFile the database file path
     * @throws IllegalArgumentException if the path holds a semicolon or a control character
     */
    static void check(Path dbFile) {
        check(dbFile.toString());
    }

    /**
     * Refuses a path that is more than a file name.
     *
     * @param dbFile the database file path as text
     * @throws IllegalArgumentException if the path holds a semicolon or a control character
     */
    static void check(String dbFile) {
        for (int i = 0; i < dbFile.length(); i++) {
            char c = dbFile.charAt(i);
            if (c == ';') {
                throw new IllegalArgumentException("the database path must not contain ';': it starts a JDBC setting");
            }
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("the database path must not contain a control character (at index "
                        + i + ")");
            }
        }
    }
}

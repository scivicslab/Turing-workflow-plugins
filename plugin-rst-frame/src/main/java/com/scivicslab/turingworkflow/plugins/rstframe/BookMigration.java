package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Converts a books database from before the schema versions to {@link BookSchema#VERSION} ({@code BookImport migrate}),
 * one step after another, each of which can be undone:
 *
 * <ol><li>It opens the file alone, without {@code AUTO_SERVER}, so it stops when another process (Frame Viewer, a
 * workflow) holds it.</li>
 * <li>It copies the file to {@code backup/<name>-<yyMMddHHmm>.mv.db} beside it and writes the copy's SHA-256.</li>
 * <li>It takes each table's fingerprint (rows, characters, a hash of every row), converts the database, and takes the
 * fingerprints again. When they differ, or anything fails, it puts the backup back.</li>
 * <li>It compacts the file ({@code SHUTDOWN COMPACT}), and checks that a reader opens it.</li></ol>
 */
public final class BookMigration {

    /** What a migration did: the backup, the columns converted, the fingerprints, and the file's size before and after. */
    public record Result(Path backup, String backupSha256, List<String> converted, Map<String, Fingerprint> fingerprints, long sizeBefore,
            long sizeAfter) {}

    /**
     * One table's content, independent of the column types and of the order of the rows: its row count, the characters of
     * all its values, and the sum of the SHA-256 of each row.
     */
    public record Fingerprint(long rows, long chars, String hash) {}

    private BookMigration() {
    }

    /**
     * Migrates the database at {@code base} (the path without {@code .mv.db}).
     *
     * @throws IllegalStateException when the database is in use, is already versioned, or did not keep its content; in
     *         the last case the backup has been put back
     */
    public static Result migrate(Path base, PrintStream out) throws SQLException, IOException {
        Path file = Path.of(base + ".mv.db");
        if (!Files.isRegularFile(file)) throw new IllegalStateException("no database " + file);
        H2Url.check(base);
        String url = "jdbc:h2:" + base.toAbsolutePath();
        // alone: without AUTO_SERVER, H2 refuses a file another process holds
        try (Connection db = BookStore.openH2(url)) {
            BookSchema.State st = BookSchema.state(db);
            if (st != BookSchema.State.UNVERSIONED) {
                throw new IllegalStateException(file + " is not from before the schema versions (" + st
                        + (st == BookSchema.State.VERSIONED ? ", version " + BookSchema.version(db) : "") + "); nothing to migrate");
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("already in use")) {
                throw new IllegalStateException(file + " is open in another process (Frame Viewer, a workflow); stop it first", e);
            }
            throw e;
        }
        long before = Files.size(file);
        Path backupDir = file.getParent().resolve("backup");
        Files.createDirectories(backupDir);
        String name = file.getFileName().toString().replace(".mv.db", "");
        Path backup = backupDir.resolve(name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmm")) + ".mv.db");
        if (Files.exists(backup)) throw new IllegalStateException("the backup " + backup + " already exists");
        Files.copy(file, backup);
        String sha = sha256(backup);
        Files.writeString(Path.of(backup + ".sha256"), sha + "  " + backup.getFileName() + "\n");
        out.println("backup: " + backup + " (" + Files.size(backup) + " bytes, sha256 " + sha + ")");
        List<String> converted;
        Map<String, Fingerprint> after;
        try {
            try (Connection db = BookStore.openH2(url)) {
                // the columns there were before: a column the migration adds to an old table is not part of what was kept
                Map<String, List<String>> columns = columns(db);
                Map<String, Fingerprint> was = fingerprints(db, columns);
                out.println("before: " + was.size() + " tables");
                converted = BookSchema.migrateUnversioned(db);
                out.println("converted " + converted.size() + " columns to VARCHAR(" + BookSchema.TEXT + ")");
                after = fingerprints(db, columns);
                List<String> differ = new ArrayList<>();
                for (var e : was.entrySet()) if (!e.getValue().equals(after.get(e.getKey()))) differ.add(e.getKey() + ": " + e.getValue() + " -> " + after.get(e.getKey()));
                if (!differ.isEmpty()) throw new IllegalStateException("the content changed: " + differ);
                for (var e : after.entrySet()) out.println("  " + e.getKey() + " " + e.getValue());
                try (Statement s = db.createStatement()) {
                    s.execute("SHUTDOWN COMPACT");
                }
            }
            try (BookStore check = BookStore.forReading(url)) {
                out.println("a reader opens it at schema version " + BookSchema.VERSION + ", data version " + check.dataVersion());
            }
        } catch (SQLException | RuntimeException e) {
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            out.println("put the backup back: " + e.getMessage());
            throw e;
        }
        long size = Files.size(file);
        out.println("size: " + before + " -> " + size + " bytes");
        return new Result(backup, sha, converted, after, before, size);
    }

    /** The columns of each table, by table name, in their order. */
    static Map<String, List<String>> columns(Connection db) throws SQLException {
        Map<String, List<String>> out = new TreeMap<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            while (r.next()) out.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2));
        }
        return out;
    }

    /** The fingerprint of each table named, over the columns named, by table name. */
    static Map<String, Fingerprint> fingerprints(Connection db, Map<String, List<String>> columns) throws SQLException {
        Map<String, Fingerprint> out = new TreeMap<>();
        for (var tc : columns.entrySet()) {
            String t = tc.getKey();
            String select = String.join(", ", tc.getValue().stream().map(c -> "\"" + c + "\"").toList());
            long rows = 0, chars = 0;
            BigInteger sum = BigInteger.ZERO;
            MessageDigest md = digest();
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT " + select + " FROM \"" + t + "\"")) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    rows++;
                    md.reset();
                    for (int i = 1; i <= n; i++) {
                        String v = r.getString(i);
                        if (v == null) {
                            md.update((byte) 0);
                        } else {
                            chars += v.length();
                            md.update((byte) 1);
                            md.update(v.getBytes(StandardCharsets.UTF_8));
                        }
                        md.update((byte) 0x1f);
                    }
                    // a sum does not depend on the order the rows come in
                    sum = sum.add(new BigInteger(1, md.digest()));
                }
            }
            out.put(t, new Fingerprint(rows, chars, sum.toString(16)));
        }
        return out;
    }

    static String sha256(Path p) throws IOException {
        MessageDigest md = digest();
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

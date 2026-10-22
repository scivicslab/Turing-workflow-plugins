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
 * section or subsection, in the book's order.</p>
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

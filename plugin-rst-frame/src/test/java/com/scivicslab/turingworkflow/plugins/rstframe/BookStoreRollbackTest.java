package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A write of the books database that fails with an unchecked exception leaves the database as it was before the
 * write: the work done before the failure is rolled back, not committed by {@code setAutoCommit(true)}. Each test uses
 * its own H2 file in a temporary directory.
 */
@Tag("PluginRstFrame_BookStoreRollback_261026_oo01")
@DisplayName("BookStore - a write that throws an unchecked exception is rolled back")
class BookStoreRollbackTest {

    @TempDir
    Path dir;

    @Test
    void saveSections_runtimeExceptionAfterDeletes_keepsEarlierRowsAndVersion() throws Exception {
        String url = "jdbc:h2:" + dir.resolve("books").toAbsolutePath();
        try (BookStore store = BookStore.forWriting(url)) {
            store.saveOutline("core", "/w/books/core", new BookOutline.Outline(List.of(
                    new BookOutline.Node("1", "chapter", "Chapter 1", null, "c1.md"),
                    new BookOutline.Node("2", "chapter", "Chapter 2", null, "c2.md")), List.of()));
            List<BookSections.Section> before = List.of(
                    new BookSections.Section("1", "Text of section 1.", List.of("c1.md")),
                    new BookSections.Section("2", "Text of section 2.", List.of("c2.md")));
            store.saveSections("core", before);
            assertEquals(before, store.sections("core"), "the first write must be readable");
            long version = store.dataVersion();

            // saveSections deletes the book's rows first; a section without text then fails with
            // a NullPointerException while the inserts are being built.
            List<BookSections.Section> broken = List.of(
                    new BookSections.Section("1", "New text of section 1.", List.of("c1.md")),
                    new BookSections.Section("2", null, List.of("c2.md")));
            assertThrows(NullPointerException.class, () -> store.saveSections("core", broken));

            assertEquals(before, store.sections("core"), "the rows from before the failed write must still be there");
            assertEquals(version, store.dataVersion(), "a failed write must not count as a change");
        }

        // What another connection sees is what was committed.
        try (BookStore reopened = BookStore.forReading(url)) {
            assertEquals(2, reopened.sections("core").size(), "the failed write's deletes must not be committed");
        }
    }
}

package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The book's structure read into H2, by the POJO and through the actor's actions. */
class BookStructureTest {

    private static Path book(Path dir) throws Exception {
        Path b = dir.resolve("book");
        Files.createDirectories(b.resolve("Chap04/p001"));
        Files.createDirectories(b.resolve("Chap04/p006"));
        Files.writeString(b.resolve("Chap04/p001/p001.md"), "## Chapter 4 ▪ Objects and Classes\n\nIn this chapter.\n\n## 4.1. Introduction\n\n## 4.1.1. Classes\n\nA class is a template.\n");
        Files.writeString(b.resolve("Chap04/p006/p006.md"), "It runs on.\n\n## 4.2. Using Predefined Classes\n\nText of 4.2.\n");
        return b;
    }

    @Test
    void theTableOfContentsThenTheSectionsAreStored(@TempDir Path dir) throws Exception {
        BookStructure s = new BookStructure();
        String db = dir.resolve("db/books").toString();
        assertEquals("read 2 markdown file(s) of core: {chapter=1, section=2, subsection=1}", s.readToc(db, "core", book(dir).toString()));
        assertEquals("4 section(s) of core; 1 with no text of their own; 1 spanning more than one file", s.cutSections(db, "core"));
        try (BookStore store = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            assertEquals("A class is a template.\n\nIt runs on.", store.sections("core").get(2).text());
        }
    }

    @Test
    void theActionsSayWhatWasDoneOrWhyNot(@TempDir Path dir) throws Exception {
        IIActorSystem system = new IIActorSystem("book-structure-test");
        try {
            BookStructureIIAR books = new BookStructureIIAR("books", system);
            system.addIIActor(books);
            String db = dir.resolve("db/books").toString();
            ActionResult none = books.callByActionName("sections", new JSONObject().put("db", db).put("name", "core").toString());
            assertFalse(none.isSuccess());
            assertTrue(none.getResult().contains("no book core"), none.getResult());
            ActionResult toc = books.callByActionName("toc",
                    new JSONObject().put("db", db).put("name", "core").put("book", book(dir).toString()).toString());
            assertTrue(toc.isSuccess(), toc.getResult());
            assertTrue(books.callByActionName("sections", new JSONObject().put("db", db).put("name", "core").toString()).isSuccess());
        } finally {
            system.terminate();
        }
    }
}

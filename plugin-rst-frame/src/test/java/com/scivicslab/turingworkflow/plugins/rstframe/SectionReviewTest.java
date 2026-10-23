package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Step 7: each relation of step 6 kept, given another kind, reversed or dropped, by one question per section. */
class SectionReviewTest {

    @Test
    void eachVerdictIsReadByItsNumberAndARelationNotNamedIsKept() {
        assertEquals(List.of("is-a", "drop", "keep", "reverse", "keep"),
                SectionReview.read("R1 | is-a\nR2 | drop\nR4: reverse\nR5 | unsure\nR9 | drop\n", 5),
                "a verdict not one of the six, and a number past the relations, leave the relation kept");
        assertEquals(List.of("part-of"), SectionReview.read("- R1 | part of", 1));
    }

    @Test
    void aVerdictMakesTheRelationTheReviewLeft() {
        BookStore.Relation r = new BookStore.Relation("part-of", "multithreading", "concurrent programming", List.of(1));
        assertEquals(r, r.reviewed(), "not reviewed: as step 6 found it");
        assertEquals("is-a", new BookStore.Relation("part-of", "multithreading", "concurrent programming", List.of(1), "is-a").reviewed().kind());
        BookStore.Relation rev = new BookStore.Relation("part-of", "class", "Object class", List.of(1), "reverse").reviewed();
        assertEquals(List.of("Object class", "class"), List.of(rev.child(), rev.parent()));
        assertNull(new BookStore.Relation("feature", "Java", "concurrent programming", List.of(5), "drop").reviewed());
    }

    @Test
    void theRelationsOfASectionAreReviewedOnceAndTheVerdictsStored(@TempDir Path dir) throws Exception {
        Path b = dir.resolve("book/Chap10/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 10 ▪ Concurrency\n\n## 10.1. Running Threads\n\n"
                + "## 10.1.1. Threads\n\nMultithreading is one way to program concurrently.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        try (BookStore store = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            store.saveSectionRules("core", new BookStore.SectionRules("10.1.1", """
                    (defrule s1 -> p1 :heading "## 10.1.1. Threads")
                    (defrule p1 -> :nucleus p1/1)
                    (defrule p1/1 -> "Multithreading is one way to program concurrently.")
                    """, Map.of(), 1));
            store.saveRelations("core", "10.1.1", List.of(
                    new BookStore.Relation("part-of", "multithreading", "concurrent programming", List.of(1)),
                    new BookStore.Relation("feature", "Java", "concurrent programming", List.of(1))));
            List<String> asked = new java.util.ArrayList<>();
            String summary = SectionReview.reviewAll(store, "core", List.of(), q -> { asked.add(q); return "R1 | is-a\nR2 | drop"; }, 2, line -> { });
            assertEquals("1 section(s) of core reviewed: {drop=1, is-a=1}, 0 failed", summary);
            assertTrue(asked.get(0).contains("R1: multithreading | part-of | concurrent programming   (sentences 1)"), asked.get(0));
            List<BookStore.Relation> after = store.relations("core").get("10.1.1");
            assertEquals(List.of("is-a", "drop"), after.stream().map(BookStore.Relation::review).toList());
            assertEquals("is-a", after.get(0).reviewed().kind());
            SectionReview.reviewAll(store, "core", List.of(), q -> { asked.add(q); return ""; }, 2, line -> { });
            assertEquals(1, asked.size(), "a section reviewed once is not reviewed again");
        }
    }
}

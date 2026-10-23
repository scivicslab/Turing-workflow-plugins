package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Step 6: one question per section; the reply read into is-a, part-of and feature relations, stored in H2. */
class SectionRelationsTest {

    private static final List<String> CONCEPTS = List.of("String", "Unicode character", "String literal", "UTF-8 encoding", "Escape sequence");

    @Test
    void theReplyIsReadIntoRelationsBetweenTheListedConceptsOnly() {
        String reply = """
                String literal | is-a | String | 5, 8
                - Unicode character | part-of | string | 1, 2, 3
                Escape sequence | part of | String | 11, 99
                UTF-8 encoding | feature | String | 9, 10
                Character set | is-a | String | 4
                String | is-a | String | 1
                String literal | is-a | String | 9
                String literal | inherits | String | 9
                Escape sequence | other | UTF-8 encoding | 12
                String literal | operation | Unicode character | 3
                """;
        assertEquals(List.of(
                new BookStore.Relation("is-a", "String literal", "String", List.of(5, 8)),
                new BookStore.Relation("part-of", "Unicode character", "String", List.of(1, 2, 3)),
                new BookStore.Relation("part-of", "Escape sequence", "String", List.of(11)),
                new BookStore.Relation("feature", "UTF-8 encoding", "String", List.of(9, 10)),
                new BookStore.Relation("other", "Escape sequence", "UTF-8 encoding", List.of(12)),
                new BookStore.Relation("operation", "String literal", "Unicode character", List.of(3))),
                SectionRelations.read(reply, CONCEPTS, 12),
                "a concept not listed, a concept related to itself, a repeat and a relation not one of the five are dropped");
    }

    @Test
    void eachSectionWithTwoConceptsKeptIsAskedOnceAndItsRelationsAreStored(@TempDir Path dir) throws Exception {
        Path b = dir.resolve("book/Chap03/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 3 ▪ Fundamental Programming Structures\n\n## 3.6. Strings\n\n"
                + "## 3.6.1. Strings\n\nA string is a sequence of Unicode characters.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        try (BookStore store = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            store.saveSectionRules("core", new BookStore.SectionRules("3.6.1", """
                    (defrule s1 -> p1 :heading "## 3.6.1. Strings")
                    (defrule p1 -> :nucleus p1/1 :elaboration p1/2)
                    (defrule p1/1 -> "A string is a sequence of Unicode characters.")
                    (defrule p1/2 -> "A string literal is written in quotes.")
                    """, Map.of(), 2));
            store.saveListedConcepts("core", "3.6.1", List.of(new BookStore.ListedConcept("String", List.of(1)),
                    new BookStore.ListedConcept("Unicode character", List.of(1)), new BookStore.ListedConcept("String literal", List.of(2))));
            store.saveJudgement("core", "3.6.1", List.of(new BookStore.JudgedConcept("String", true, "in the title"),
                    new BookStore.JudgedConcept("Unicode character", true, "in a nucleus"),
                    new BookStore.JudgedConcept("String literal", false, "only in example")));
            List<String> asked = new java.util.ArrayList<>();
            String summary = SectionRelations.relateAll(store, "core", List.of(), q -> { asked.add(q); return "Unicode character | part-of | String | 1"; },
                    2, line -> { });
            assertEquals("1 section(s) of core asked, 1 relation(s) found, 0 failed", summary);
            assertTrue(asked.get(0).contains("[1] A string is a sequence of Unicode characters."), asked.get(0));
            assertTrue(asked.get(0).contains("- String\n- Unicode character\n") && !asked.get(0).contains("- String literal"),
                    "only the concepts step 5 kept are listed: " + asked.get(0));
            assertEquals(List.of(new BookStore.Relation("part-of", "Unicode character", "String", List.of(1))), store.relations("core").get("3.6.1"));
            SectionRelations.relateAll(store, "core", List.of(), q -> { asked.add(q); return ""; }, 2, line -> { });
            assertEquals(1, asked.size(), "a section asked once is not asked again");
        }
    }
}

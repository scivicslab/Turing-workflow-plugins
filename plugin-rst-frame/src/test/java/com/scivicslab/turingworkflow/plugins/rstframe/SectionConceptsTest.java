package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One question per section, its sentences numbered; the answer read line by line into concepts and their sentences, stored in H2. */
class SectionConceptsTest {

    @Test
    void theAnswerIsReadIntoConceptsWithoutBulletsNumberingOrRepeats() {
        assertEquals(List.of("class", "instance", "Encapsulation"),
                SectionConcepts.read("Here they are:\n- **class**\n2. instance\n* Encapsulation.\n- Class\n\n"));
    }

    @Test
    void eachTermComesWithTheSentencesAfterItsBarAndARemarkInBracketsIsTakenOff() {
        assertEquals(List.of(
                new BookStore.ListedConcept("integer literal", List.of(3, 4)),
                new BookStore.ListedConcept("Lambda expressions", List.of(2)),
                new BookStore.ListedConcept("opens keyword", List.of()),
                new BookStore.ListedConcept("Shadowing", null)),
                SectionConcepts.readListed("- integer literal | 3, 4\n2. Lambda expressions (implicit in the code) | 2, 9\n"
                        + "opens` keyword | none\nShadowing\n", 5));
    }

    @Test
    void eachSectionIsAskedOnceAndItsConceptsAreStored(@TempDir Path dir) throws Exception {
        Path b = dir.resolve("book/Chap04/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 4 ▪ Objects and Classes\n\n## 4.1. Introduction\n\n## 4.1.1. Classes\n\n"
                + "A class specifies how objects are made.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        try (BookStore store = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            store.saveSectionRules("core", new BookStore.SectionRules("4.1.1", """
                    (defrule s1 -> p1 :heading "## 4.1.1. Classes")
                    (defrule p1 -> :nucleus p1/1 :example p1/2)
                    (defrule p1/1 -> "A class specifies how objects are made.")
                    (defrule p1/2 -> "A cookie cutter makes cookies.")
                    """, java.util.Map.of(), 2));
            store.saveSectionRules("core", new BookStore.SectionRules("4.1", "(defrule s1 -> :heading \"## 4.1\")", java.util.Map.of(), 0));
            List<String> asked = new java.util.ArrayList<>();
            String summary = SectionConcepts.listAll(store, "core", List.of(), q -> { asked.add(q); return "class | 1\ncookie cutter | 2"; }, 2, line -> { });
            assertEquals("2 section(s) of core listed, 0 failed", summary, "a section without a rule file is not asked");
            assertEquals(1, asked.size(), "a rule file with no sentence is not asked: " + asked);
            assertTrue(asked.get(0).startsWith("Below is one section of a textbook, \"4.1.1. Classes\", its sentences numbered."), asked.get(0));
            assertTrue(asked.get(0).contains("[1] A class specifies how objects are made.\n[2] A cookie cutter makes cookies.\n"), asked.get(0));
            assertEquals(List.of(new BookStore.ListedConcept("class", List.of(1)), new BookStore.ListedConcept("cookie cutter", List.of(2))),
                    store.listedConcepts("core").get("4.1.1"));
List<String> again = new java.util.ArrayList<>();
            SectionConcepts.listAll(store, "core", List.of(), q -> { again.add(q); return "x | 1"; }, 2, line -> { });
            assertEquals(0, again.size(), "a section listed with its sentences is not asked again");
        }
    }
}

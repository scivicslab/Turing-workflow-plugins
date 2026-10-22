package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One question per section; the answer read line by line into concepts, stored in H2. */
class SectionConceptsTest {

    @Test
    void theAnswerIsReadIntoConceptsWithoutBulletsNumberingOrRepeats() {
        assertEquals(List.of("class", "instance", "Encapsulation"),
                SectionConcepts.read("Here they are:\n- **class**\n2. instance\n* Encapsulation.\n- Class\n\n"));
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
            List<String> asked = new java.util.ArrayList<>();
            String summary = SectionConcepts.listAll(store, "core", List.of(), q -> { asked.add(q); return "class\nobject"; }, 2, line -> { });
            assertEquals("3 section(s) of core listed, 0 failed", summary);
            assertEquals(1, asked.size(), "a section with no text of its own is not asked: " + asked);
            assertTrue(asked.get(0).startsWith("Below is one section of a textbook, \"4.1.1. Classes\"."), asked.get(0));
            assertEquals(List.of("class", "object"), store.concepts("core").get("4.1.1"));
            assertEquals(List.of(), store.concepts("core").getOrDefault("4.1", List.of()));
        }
    }
}

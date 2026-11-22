package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reply with no line in the form the question asked for fails the item (nothing is saved, the next run asks
 * again); where nothing may honestly apply, only the single token NONE saves an empty result.
 */
@DisplayName("rst-frame replies - no well-formed line fails the item, NONE saves an empty result")
class ReplyRulesTest {

    private static final String PROSE = "I have read the section carefully, and I think it is about strings in general.";

    @Test
    void isNone_acceptsTheTokenAloneOnly() {
        assertTrue(Replies.isNone("NONE"));
        assertTrue(Replies.isNone(" none.\n"));
        assertTrue(Replies.isNone("**None**"));
        assertFalse(Replies.isNone("None of them is of the same kind."));
        assertFalse(Replies.isNone(""));
        assertFalse(Replies.isNone(null));
    }

    private static BookStore strings(Path dir) throws Exception {
        Path b = dir.resolve("book/Chap03/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 3 ▪ Fundamental Programming Structures\n\n## 3.6. Strings\n\n"
                + "## 3.6.1. Strings\n\nA string is a sequence of Unicode characters.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        BookStore store = BookStore.forWriting("jdbc:h2:" + db + ";AUTO_SERVER=TRUE");
        store.saveSectionRules("core", new BookStore.SectionRules("3.6.1", """
                (defrule s1 -> p1 :heading "## 3.6.1. Strings")
                (defrule p1 -> :nucleus p1/1 :elaboration p1/2)
                (defrule p1/1 -> "A string is a sequence of Unicode characters.")
                (defrule p1/2 -> "A string literal is written in quotes.")
                """, Map.of(), 2));
        return store;
    }

    private static void judged(BookStore store) throws Exception {
        store.saveListedConcepts("core", "3.6.1", List.of(new BookStore.ListedConcept("String", List.of(1)),
                new BookStore.ListedConcept("Unicode character", List.of(1))));
        store.saveJudgement("core", "3.6.1", List.of(new BookStore.JudgedConcept("String", true, "in the title"),
                new BookStore.JudgedConcept("Unicode character", true, "in a nucleus")));
    }

    @Test
    void relateAll_proseReply_failsTheSection(@TempDir Path dir) throws Exception {
        try (BookStore store = strings(dir)) {
            judged(store);

            Exception failure = assertThrows(Exception.class,
                    () -> SectionRelations.relateAll(store, "core", List.of(), q -> PROSE, 2, line -> { }));

            assertTrue(failure.getMessage().startsWith("1 of 1 failed: 3.6.1: "), failure.getMessage());
            assertFalse(store.relations("core").containsKey("3.6.1"), "nothing is saved, so the next run asks again");
        }
    }

    @Test
    void relateAll_none_savesNoRelationAndIsNotAskedAgain(@TempDir Path dir) throws Exception {
        try (BookStore store = strings(dir)) {
            judged(store);
            List<String> asked = new ArrayList<>();

            String summary = SectionRelations.relateAll(store, "core", List.of(), q -> { asked.add(q); return "NONE"; }, 2, line -> { });

            assertEquals("1 section(s) of core asked, 0 relation(s) found, 0 failed", summary);
            assertTrue(asked.get(0).contains("write NONE"), "the question says how to answer nothing: " + asked.get(0));
            assertEquals(List.of(), store.relations("core").get("3.6.1"));
            SectionRelations.relateAll(store, "core", List.of(), q -> { asked.add(q); return "NONE"; }, 2, line -> { });
            assertEquals(1, asked.size());
        }
    }

    @Test
    void reviewAll_proseReply_failsTheSectionInsteadOfKeepingEverything(@TempDir Path dir) throws Exception {
        try (BookStore store = strings(dir)) {
            store.saveRelations("core", "3.6.1", List.of(new BookStore.Relation("part-of", "Unicode character", "String", List.of(1))));

            Exception failure = assertThrows(Exception.class,
                    () -> SectionReview.reviewAll(store, "core", List.of(), q -> PROSE, 2, line -> { }));

            assertTrue(failure.getMessage().startsWith("1 of 1 failed: 3.6.1: "), failure.getMessage());
            assertEquals(null, store.relations("core").get("3.6.1").get(0).review(), "no verdict is saved");
        }
    }

    @Test
    void listAll_proseReply_failsInsteadOfSavingItsLinesAsConcepts(@TempDir Path dir) throws Exception {
        try (BookStore store = strings(dir)) {
            Exception failure = assertThrows(Exception.class,
                    () -> SectionConcepts.listAll(store, "core", List.of(), q -> PROSE, 2, line -> { }));

            assertTrue(failure.getMessage().contains("3.6.1: "), failure.getMessage());
            assertFalse(store.listedConcepts("core").containsKey("3.6.1"));
        }
    }

    @Test
    void listAll_none_savesNoConcept(@TempDir Path dir) throws Exception {
        try (BookStore store = strings(dir)) {
            List<String> asked = new ArrayList<>();

            String summary = SectionConcepts.listAll(store, "core", List.of(), q -> { asked.add(q); return "NONE"; }, 2, line -> { });

            assertTrue(summary.endsWith(", 0 failed"), summary);
            assertTrue(asked.get(0).contains("Write NONE when the section explains no term"), asked.get(0));
            assertFalse(store.listedConcepts("core").containsKey("3.6.1"), "no concept named NONE is saved");
        }
    }

    private static BookStore identifiers(Path dir) throws Exception {
        Path b = dir.resolve("book/Chap03/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 3 ▪ Fundamental Programming Structures\n\n## 3.4. Variables\n\n"
                + "## 3.4.1. Declaring Variables\n\nAn identifier is a sequence of letters and digits.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        BookStore store = BookStore.forWriting("jdbc:h2:" + db + ";AUTO_SERVER=TRUE");
        store.saveSectionRules("core", new BookStore.SectionRules("3.4.1", """
                (defrule s1 -> b4 :heading "## 3.4.1. Declaring Variables")
                (defrule b4 -> :nucleus b4/1 :elaboration-object-attribute b4/2)
                (defrule b4/1 -> "An identifier is a sequence of letters and digits.")
                (defrule b4/2 -> "The first character cannot be a digit.")
                """, Map.of(), 2));
        store.saveGroups("core", "3.4.1", List.of(new SectionGroups.Group(1, "sequence", "identifier",
                List.of(new SectionGroups.Member("b4", "what an identifier is")))));
        return store;
    }

    @Test
    void propertiesAskAll_proseReply_failsTheSection(@TempDir Path dir) throws Exception {
        try (BookStore store = identifiers(dir)) {
            Exception failure = assertThrows(Exception.class,
                    () -> ConceptProperties.askAll(store, "core", List.of(), q -> PROSE, 2, line -> { }));

            assertTrue(failure.getMessage().startsWith("1 of 1 failed: 3.4.1: "), failure.getMessage());
        }
    }

    @Test
    void propertiesAskAll_none_savesNoPropertyAndIsNotAskedAgain(@TempDir Path dir) throws Exception {
        try (BookStore store = identifiers(dir)) {
            List<String> asked = new ArrayList<>();

            String summary = ConceptProperties.askAll(store, "core", List.of(), q -> { asked.add(q); return "NONE"; }, 2, line -> { });

            assertTrue(summary.endsWith(", 0 failed"), summary);
            assertTrue(asked.get(0).contains("Write NONE"), asked.get(0));
            ConceptProperties.askAll(store, "core", List.of(), q -> { asked.add(q); return "NONE"; }, 2, line -> { });
            assertEquals(1, asked.size(), "a section answered NONE counts as asked");
        }
    }

    @Test
    void readGroupsGuard_proseReplyForIdentify_fails() {
        assertThrows(IllegalStateException.class, () -> GeneralFrames.requireGroupLine(PROSE));
        GeneralFrames.requireGroupLine("G: 1, 2");
    }

    @Test
    void similarConcepts_sentenceSayingNone_isNotTheTokenNone() {
        assertThrows(IllegalStateException.class, () -> SimilarConcepts.checkAnswered(List.of(), "None of them is of the same kind, I think."));
        SimilarConcepts.checkAnswered(List.of(), "NONE");
    }
}

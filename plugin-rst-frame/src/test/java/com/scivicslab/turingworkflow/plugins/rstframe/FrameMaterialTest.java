package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A section's concrete frame material: the hypothesis list, the rules collected for it, and the list reviewed by them. */
class FrameMaterialTest {

    private static final String RULES = """
            (defrule s1 -> p1 p2 :heading "## 3.6. Strings")
            (defrule p1 -> :nucleus p1/1 :elaboration p1/2)
            (defrule p1/1 -> "A string is a sequence of Unicode characters.")
            (defrule p1/2 -> "Strings are immutable.")
            (defrule p2 -> :nucleus p2/1 :example p2/2)
            (defrule p2/1 -> "The substring method extracts a part of a string.")
            (defrule p2/2 -> "A string literal is written in quotes.")
            """;

    private static List<BookStore.JudgedConcept> judged() {
        return List.of(new BookStore.JudgedConcept("String", true, "in the title"),
                new BookStore.JudgedConcept("String literal", true, "in a nucleus (sentences 4)"),
                new BookStore.JudgedConcept("UTF-8 encoding", true, "in a nucleus (sentences 1)"),
                new BookStore.JudgedConcept("Moore's law", true, "in a nucleus (sentences 2)"),
                new BookStore.JudgedConcept("cookie", false, "only in example"));
    }

    @Test
    void theListIsTheConceptsOfTheTitleAndOfTheRelationsThatStand() {
        List<BookStore.Relation> rel = List.of(
                new BookStore.Relation("is-a", "String literal", "String", List.of(4)),
                new BookStore.Relation("feature", "UTF-8 encoding", "String literal", List.of(1), "drop"));
        assertEquals(Map.of("String", List.of(), "String literal", List.of()), FrameMaterial.candidates(judged(), rel),
                "a concept only in a dropped relation, one in none, and one step 5 dropped are not listed");
    }

    /** A feature or a part is folded into its owner, up to the first owner listed; one whose owner is not listed is listed itself. */
    @Test
    void aFeatureOrAPartIsFoldedIntoItsOwner() {
        List<BookStore.JudgedConcept> j = List.of(new BookStore.JudgedConcept("String", true, "in the title"),
                new BookStore.JudgedConcept("String.length", true, "in a nucleus"),
                new BookStore.JudgedConcept("String.charAt", true, "in a nucleus"),
                new BookStore.JudgedConcept("code unit", true, "in a nucleus"),
                new BookStore.JudgedConcept("char", true, "in a nucleus"),
                new BookStore.JudgedConcept("index", true, "in a nucleus"),
                new BookStore.JudgedConcept("offset", true, "in a nucleus"));
        List<BookStore.Relation> rel = List.of(
                new BookStore.Relation("feature", "String.length", "String", List.of(1)),
                new BookStore.Relation("feature", "String.charAt", "String", List.of(2)),
                new BookStore.Relation("part-of", "code unit", "char", List.of(3)),
                new BookStore.Relation("part-of", "char", "String", List.of(3)),
                new BookStore.Relation("is-a", "char", "primitive type", List.of(3)),
                new BookStore.Relation("feature", "offset", "index", List.of(4)),
                new BookStore.Relation("part-of", "index", "position", List.of(4), "drop"));
        java.util.Map<String, List<String>> c = FrameMaterial.candidates(j, rel);
        assertEquals(List.of("String", "char", "index"), List.copyOf(c.keySet()),
                "char is listed on its own account (an is-a child and a parent); index is a parent; the rest are folded");
        assertEquals(List.of("String.length", "String.charAt"), c.get("String"));
        assertEquals(List.of("code unit"), c.get("char"));
        assertEquals(List.of("offset"), c.get("index"));
    }

    @Test
    void theReplyNamesSentencesWhichBecomeRulesByTheirNames() {
        List<ConceptImportance.Unit> units = ConceptImportance.units(RULES, Map.of());
        Map<String, List<BookStore.FrameRule>> got = FrameMaterial.read(
                "String | property | 1, 2\n- string | behavior | 3, 3\nString literal | property | 4\nString | example | 4\nOther | property | 1\n",
                List.of("String", "String literal"), units);
        assertEquals(List.of(new BookStore.FrameRule("property", "p1/1", "A string is a sequence of Unicode characters."),
                new BookStore.FrameRule("property", "p1/2", "Strings are immutable."),
                new BookStore.FrameRule("behavior", "p2/1", "The substring method extracts a part of a string.")), got.get("String"));
        assertEquals(List.of(new BookStore.FrameRule("property", "p2/2", "A string literal is written in quotes.")), got.get("String literal"));
    }

    @Test
    void theRulesAreCollectedAndAConceptWithFewIsTakenOffTheList(@TempDir Path dir) throws Exception {
        Path b = dir.resolve("book/Chap03/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 3 ▪ Fundamental Structures\n\n## 3.6. Strings\n\nA string is text.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        try (BookStore store = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            store.saveSectionRules("core", new BookStore.SectionRules("3.6", RULES, Map.of(), 2));
            store.saveListedConcepts("core", "3.6", judged().stream().map(c -> new BookStore.ListedConcept(c.concept(), List.of(1))).toList());
            store.saveJudgement("core", "3.6", judged());
            store.saveRelations("core", "3.6", List.of(new BookStore.Relation("is-a", "String literal", "String", List.of(4))));
            List<String> asked = new java.util.ArrayList<>();
            String summary = FrameMaterial.collectAll(store, "core", List.of(),
                    q -> { asked.add(q); return "String | property | 1, 2\nString | behavior | 3\nString literal | property | 4"; }, 2, line -> { });
            assertEquals("1 section(s) of core asked, 4 rule(s) collected, 0 failed", summary);
            assertTrue(asked.get(0).contains("- String\n- String literal\n"), asked.get(0));
            assertEquals(List.of(), store.frameConcepts("core").get("3.6").get(0).folded());
            assertEquals("1 section(s) of core: 1 concept(s) stay, 1 taken off (fewer than 2 rule(s))", FrameMaterial.pruneAll(store, "core", 2));
            List<BookStore.FrameConcept> list = store.frameConcepts("core").get("3.6");
            assertEquals(List.of("String", "String literal"), list.stream().map(BookStore.FrameConcept::concept).toList());
            assertEquals(List.of(true, false), list.stream().map(BookStore.FrameConcept::kept).toList());
            assertEquals(3, list.get(0).rules().size(), "the rules stay with a concept taken off too");
        }
    }

    /** A concept related another way (a way of doing the parent) is listed on its own, not folded into the parent. */
    @Test
    void aConceptRelatedAnotherWayIsListedOnItsOwn() {
        List<BookStore.JudgedConcept> j = List.of(new BookStore.JudgedConcept("string concatenation", true, "in the title"),
                new BookStore.JudgedConcept("String.join", true, "in a nucleus"),
                new BookStore.JudgedConcept("operator precedence", true, "in a nucleus"),
                new BookStore.JudgedConcept("String.repeat", true, "in a nucleus"));
        List<BookStore.Relation> rel = List.of(
                new BookStore.Relation("other", "String.join", "string concatenation", List.of(21)),
                new BookStore.Relation("feature", "operator precedence", "string concatenation", List.of(13)),
                new BookStore.Relation("operation", "String.repeat", "string concatenation", List.of(23)));
        java.util.Map<String, List<String>> c = FrameMaterial.candidates(j, rel);
        assertEquals(List.of("string concatenation", "String.join"), List.copyOf(c.keySet()));
        assertEquals(List.of("operator precedence", "String.repeat"), c.get("string concatenation"), "an operation is folded like a feature");
    }
}

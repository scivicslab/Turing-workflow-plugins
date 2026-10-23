package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: a concept is kept or dropped by the role of the sentences that name it. */
class ConceptImportanceTest {

    private static final String RULES = """
            (defrule document -> title sections)
            (defrule title -> "4.1.1. Classes")
            (defrule sections -> s1)
            (defrule s1 -> b1 b2 b3 b4 :heading "## 4.1.1. Classes")
            (defrule b1 -> :nucleus b1/1 :analogy b1/2 :definition b1/3)
            (defrule b1/1 -> "A class specifies how objects are made.")
            (defrule b1/2 -> "Think of classes as cookie cutters.")
            (defrule b1/3 -> "Encapsulation is combining data and behavior in one package.")
            (defrule b2 -> :nucleus b2/1 :example b2/2 :dispatch b2/3)
            (defrule b2/1 -> "The state of an object may change.")
            (defrule b2/2 -> "For example, a calendar has a date.")
            (defrule b2/3 -> "See Chapter 5 for more on inheritance.")
            (defrule b3 -> b3/1 b3/2 :kind bullet)
            (defrule b3/1 -> "Instance fields")
            (defrule b3/2 -> "Methods")
            (defrule b4 -> :file "b4.txt" :kind code-block)
            """;

    @Test
    void aConceptIsKeptWhereTheSectionSaysItAndDroppedWhereItOnlyAddsAround() {
        List<BookStore.JudgedConcept> j = ConceptImportance.judge("Classes", RULES, Map.of("b4.txt", "Employee harry = new Employee();"),
                List.of("Class", "Object", "Encapsulation", "Instance field", "Calendar", "Inheritance", "Cookie cutter", "Employee", "Information hiding"));
        assertEquals(List.of(
                new BookStore.JudgedConcept("Class", true, "in the title"),
                new BookStore.JudgedConcept("Object", true, "in a nucleus"),
                new BookStore.JudgedConcept("Encapsulation", true, "in a definition"),
                new BookStore.JudgedConcept("Instance field", true, "in a list item"),
                new BookStore.JudgedConcept("Calendar", false, "only in example"),
                new BookStore.JudgedConcept("Inheritance", false, "only in dispatch"),
                new BookStore.JudgedConcept("Cookie cutter", false, "only in analogy"),
                new BookStore.JudgedConcept("Employee", false, "only in code"),
                new BookStore.JudgedConcept("Information hiding", true, "not found in the text: kept as the writer named it")), j);
    }

    /** Step 4 says which sentences explain each concept; their roles decide, whatever words they use. */
    @Test
    void aConceptIsJudgedByTheSentencesStep4PointedTo() {
        List<ConceptImportance.Unit> units = ConceptImportance.units(RULES, Map.of("b4.txt", "Employee harry = new Employee();"));
        assertEquals(List.of("nucleus", "analogy", "definition", "nucleus", "example", "dispatch", "list item", "list item", "code"),
                units.stream().map(ConceptImportance.Unit::role).toList(), "numbered in the file's order");
        List<BookStore.JudgedConcept> j = ConceptImportance.judgeListed("Classes", RULES, Map.of("b4.txt", "Employee harry = new Employee();"), List.of(
                new BookStore.ListedConcept("Information hiding", List.of(3)),
                new BookStore.ListedConcept("Mental model of a class", List.of(2)),
                new BookStore.ListedConcept("Object construction", List.of(5, 9)),
                new BookStore.ListedConcept("Lost", List.of(42)),
                new BookStore.ListedConcept("Object", null)));
        assertEquals(List.of(
                new BookStore.JudgedConcept("Information hiding", true, "in a definition (sentences 3)"),
                new BookStore.JudgedConcept("Mental model of a class", false, "only in analogy (sentences 2)"),
                new BookStore.JudgedConcept("Object construction", false, "only in example, code (sentences 5, 9)"),
                new BookStore.JudgedConcept("Lost", true, "its sentences are not in the rule file: kept"),
                new BookStore.JudgedConcept("Object", true, "in a nucleus")), j);
    }
}

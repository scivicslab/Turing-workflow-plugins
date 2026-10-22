package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: a textbook's paragraphs go to the table-of-contents node their heading's number names. */
class TextbookConceptTreeTest {

    private static final String SUBJECTS = """
            (in-package :rst)
            (defsubjects core :book "/b")
            (subject "4" "Objects and Classes" :file "x")
            (subject "4.1" "Introduction to Object-Oriented Programming" :file "x")
            (subject "4.1.1" "Classes" :file "x")
            """;
    private static final String P1 = """
            (defrule document -> title sections)
            (defrule title -> "Chapter 4 ▪ Objects and Classes")
            (defrule sections -> s1 s2 s3)
            (defrule s1 -> b1 :heading "## Chapter 4 ▪ Objects and Classes")
            (defrule b1 -> :nucleus b1/1)
            (defrule b1/1 -> "This chapter introduces objects.")
            (defrule s2 -> b2 :heading "## In this chapter, I")
            (defrule b2 -> :nucleus b2/1)
            (defrule b2/1 -> "Show you how to write classes.")
            (defrule s3 -> b3 :heading "## 4.1. Introduction to Object-Oriented Programming")
            (defrule b3 -> :nucleus b3/1)
            (defrule b3/1 -> "OOP is the dominant paradigm.")
            """;
    private static final String P2 = """
            (defrule document -> title sections)
            (defrule title -> "4.1. Introduction to Object-Oriented Programming (continued)")
            (defrule sections -> s1 s2)
            (defrule s1 -> b1 :heading "## 4.1. Introduction to Object-Oriented Programming (continued)")
            (defrule b1 -> :nucleus b1/1)
            (defrule b1/1 -> "Objects have state.")
            (defrule s2 -> b2 :heading "## 4.1.1. Classes")
            (defrule b2 -> :nucleus b2/1)
            (defrule b2/1 -> "A class is the template from which objects are made.")
            """;

    private static Map<String, String> files() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("/b/Chap04/Ch 4 p006-010/Ch 4 p006-010.lisp", P2);
        f.put("/b/Chap04/Ch 4 p001-005/Ch 4 p001-005.lisp", P1);
        return f;
    }

    @Test
    void eachParagraphGoesToTheNodeItsHeadingNumbers() {
        String tree = TextbookConceptTree.build("core", SUBJECTS, files());
        assertTrue(tree.contains("(subject \"4\" \"Objects and Classes\" :paragraphs \"Ch 4 p001-005.lisp / b1; Ch 4 p001-005.lisp / b2;\")"), tree);
        assertTrue(tree.contains("(subject \"4.1\" \"Introduction to Object-Oriented Programming\" :paragraphs "
                + "\"Ch 4 p001-005.lisp / b3; Ch 4 p006-010.lisp / b1;\")"), tree);
        assertTrue(tree.contains("(subject \"4.1.1\" \"Classes\" :paragraphs \"Ch 4 p006-010.lisp / b2;\")"), tree);
    }

    @Test
    void stepThreeReadsTheTreeWithItsSpacedFileNames() {
        String tree = TextbookConceptTree.build("core", SUBJECTS, files());
        try (ConceptClasses c = new ConceptClasses(Map.of())) {
            SlotAssignment s = new SlotAssignment("core", files(), tree, c);
            assertEquals(java.util.List.of(), s.notes());
            String q = s.nextQuestion();
            assertTrue(q.contains("Objects and Classes"), q);
        }
    }
}

package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: one whole markdown file becomes one rule file, one section per heading. */
class BookFileToRulesTest {

    private static final String BOOK = """
            ---
            title: "x"
            ---

            ## 3.1. A Simple Java Program

            The program declares a method called mian. The term "method" is Java-speak for a function.

            ```
            void main() {}
            ```

            You must have a main method.

            ## 3.2. Comments

            Comments do not show up in the executable program.
            """;

    private static String fair(String q) {
        if (q.contains("misread")) {
            return q.contains("called mian.") ? "The program declares a method called main. The term \"method\" is Java-speak for a function."
                    : q.substring(q.indexOf("The paragraph:\n") + 15, q.indexOf("\n\nParagraph after:"));
        }
        long n = q.lines().filter(l -> l.matches("^\\d+\\. .*")).count();
        StringBuilder sb = new StringBuilder("1: nucleus\n");
        for (int i = 2; i <= n; i++) sb.append(i).append(": definition\n");
        return sb.toString();
    }

    @Test
    void everyHeadingIsASectionAndEveryParagraphIsAskedTwice() {
        BookFileToRules b = new BookFileToRules(BOOK, "/books/core/p001.md", "CoreP001");
        assertEquals(3, b.paragraphs());
        List<String> questions = new ArrayList<>();
        String q;
        while ((q = b.nextQuestion()) != null) {
            questions.add(q);
            b.answer(fair(q));
        }
        assertEquals(6, questions.size(), questions.toString());
        String file = b.ruleFile();
        assertTrue(file.contains("(defdocument CoreP001)"), file);
        assertTrue(file.contains("(defrule title -> \"3.1. A Simple Java Program\")"), file);
        assertTrue(file.contains("(defrule sections -> s1 s2)"), file);
        assertTrue(file.contains("(defrule s1 -> b1 b2 b3 :heading \"## 3.1. A Simple Java Program\" :source \"/books/core/p001.md\")"), file);
        assertTrue(file.contains("(defrule b1 -> :nucleus b1/1 :definition b1/2)"), file);
        assertTrue(file.contains("(defrule b1/1 -> \"The program declares a method called main.\")"), file);
        assertTrue(file.contains("(defrule b2 -> :file \"b2.txt\" :kind code-block)"), file);
        assertTrue(file.contains("(defrule s2 -> b4 :heading \"## 3.2. Comments\""), file);
        assertEquals(Map.of("b2.txt", "void main() {}"), b.codeFiles());
        assertNotNull(RuleFile.parse(file).rule("b4"), "the rule file reads back as rules");
    }
}

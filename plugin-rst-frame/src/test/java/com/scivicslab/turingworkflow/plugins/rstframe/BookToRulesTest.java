package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning one book's files into a section of a use's rule file, one question at a time
 * ({@code FramesAndSamplesFromBooks_261021_oo01}). The writer here is a function of the question.
 */
@DisplayName("本のファイルを、質問 1 つずつで、用途の規則ファイルの節にする")
class BookToRulesTest {

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
            """;

    private final List<String> questions = new ArrayList<>();

    private BookToRules run(BookToRules b, Function<String, String> writer) {
        String q;
        while ((q = b.nextQuestion()) != null) {
            questions.add(q);
            b.answer(writer.apply(q));
        }
        return b;
    }

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
    @DisplayName("段落ごとに読み違いと関係を聞き、コードはファイルにして、見出しの節を用途のファイルに足す")
    void aBookBecomesASection() {
        BookRules rules = new BookRules();
        BookToRules b = run(new BookToRules(List.of(BOOK), List.of("/books/core/p001.md"),
                rules.newUseFile("LanguageFeature_261021_oo01", "Java"), "core-p001"), BookToRulesTest::fair);

        assertEquals(4, questions.size(), "2 paragraphs x (misreadings, relations): " + questions);
        String file = b.useFile();
        assertTrue(file.contains("(defrule sections -> core-p001-s1)"), file);
        assertTrue(file.contains("(defrule core-p001-s1 -> core-p001-b1 core-p001-b2 core-p001-b3 :heading \"## 3.1. A Simple Java Program\" :source \"/books/core/p001.md\")"), file);
        assertTrue(file.contains("(defrule core-p001-b1 -> :nucleus core-p001-b1/1 :definition core-p001-b1/2)"), file);
        assertTrue(file.contains("\"The program declares a method called main.\""), "the misreading is corrected: " + file);
        assertTrue(file.contains("(defrule core-p001-b2 -> :file \"core-p001-b2.txt\" :kind code-block)"), file);
        assertEquals("void main() {}", b.codeFiles().get("core-p001-b2.txt"));
        assertNull(b.nextQuestion());
    }

    @Test
    @DisplayName("段落を書き換えた直しは採らずに印を残し、壊れた関係の答えは 1 回聞き直し、だめなら核だけの段落にして印を残す")
    void aRewriteIsRefusedAndABrokenAnswerIsAskedAgainOnce() {
        BookToRules b = run(new BookToRules(List.of(BOOK), List.of("/books/core/p001.md"),
                new BookRules().newUseFile("X_261021_oo01", "X"), "c"), q -> {
            if (q.contains("misread")) return "Completely different words that rewrite the whole paragraph into something else entirely.";
            return "I think sentence one is the main one.";
        });
        String file = b.useFile();
        assertTrue(file.contains("called mian."), "the rewrite was not taken: " + file);
        assertTrue(file.contains(";; OCR correction not taken"), file);
        assertTrue(file.contains(";; relations not given"), file);
        assertTrue(file.contains("(defrule c-b1 -> :nucleus c-b1/1 :elaboration-additional c-b1/2)"), file);
        assertEquals(2 * 3, questions.size(), "2 paragraphs x (misreadings, relations, relations again): " + questions);
        assertFalse(file.contains("I think"), file);
    }
}

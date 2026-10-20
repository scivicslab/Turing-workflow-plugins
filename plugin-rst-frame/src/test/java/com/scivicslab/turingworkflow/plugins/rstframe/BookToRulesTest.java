package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning one sub-section of a book into one sample, one question at a time
 * ({@code FramesAndSamplesFromBooks_261021_oo01}). The writer here is a function of the question.
 */
@DisplayName("本の小節 1 つを、質問 1 つずつで、お手本の規則ファイル 1 本にする")
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

            ## 3.2. Comments

            Comments do not show up in the executable program.
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
        if (q.contains("In one sentence")) return "Shows a short program whole, then defines what it uses.\n";
        long n = q.lines().filter(l -> l.matches("^\\d+\\. .*")).count();
        StringBuilder sb = new StringBuilder("1: nucleus\n");
        for (int i = 2; i <= n; i++) sb.append(i).append(": definition\n");
        return sb.toString();
    }

    @Test
    @DisplayName("段落ごとに読み違いと関係を聞き、最後に用途を聞き、先頭の 3 行と小節の節を持つ 1 本を作る")
    void aSubsectionBecomesOneSample() {
        BookToRules b = run(new BookToRules(BOOK, "/books/core/p001.md", "3.1. A Simple Java Program",
                "explain-program-by-whole-example"), BookToRulesTest::fair);

        assertEquals(5, questions.size(), "2 paragraphs x (misreadings, relations), then the use: " + questions);
        String file = b.sampleFile();
        assertTrue(file.startsWith("""
                ;;; use: Shows a short program whole, then defines what it uses.
                ;;; source: /books/core/p001.md / 3.1. A Simple Java Program
                ;;; shape: nucleus+definition → code → nucleus
                (in-package :rst)
                (defdocument ExplainProgramByWholeExampleSample)
                """), file);
        assertTrue(file.contains("(defrule s1 -> b1 b2 b3 :heading \"## 3.1. A Simple Java Program\" :source \"/books/core/p001.md\")"), file);
        assertTrue(file.contains("\"The program declares a method called main.\""), "the misreading is corrected: " + file);
        assertTrue(file.contains("(defrule b2 -> :file \"explain-program-by-whole-example.b2.txt\" :kind code-block)"), file);
        assertEquals("void main() {}", b.codeFiles().get("explain-program-by-whole-example.b2.txt"));
        assertFalse(file.contains("Comments do not show up"), "the next sub-section is not read: " + file);
        assertNull(b.nextQuestion());
    }

    @Test
    @DisplayName("段落を書き換えた直しは採らずに印を残し、壊れた関係の答えは 1 回聞き直し、だめなら核だけの段落にして印を残す")
    void aRewriteIsRefusedAndABrokenAnswerIsAskedAgainOnce() {
        BookToRules b = run(new BookToRules(BOOK, "/books/core/p001.md", "3.1. A Simple Java Program", "x"), q -> {
            if (q.contains("misread")) return "Completely different words that rewrite the whole paragraph into something else entirely.";
            if (q.contains("In one sentence")) return "Explains a program.";
            return "I think sentence one is the main one.";
        });
        String file = b.sampleFile();
        assertTrue(file.contains("called mian."), "the rewrite was not taken: " + file);
        assertTrue(file.contains(";; OCR correction not taken"), file);
        assertTrue(file.contains(";; relations not given"), file);
        assertTrue(file.contains("(defrule b1 -> :nucleus b1/1 :elaboration-additional b1/2)"), file);
        assertEquals(2 * 3 + 1, questions.size(), "2 paragraphs x (misreadings, relations, relations again), then the use");
        assertFalse(file.contains("I think"), file);
    }

    @Test
    @DisplayName("名前は小文字の語を - でつないだものだけを受け付ける")
    void aNameIsLowerCaseWordsJoinedByHyphens() {
        assertThrows(IllegalArgumentException.class, () -> new BookToRules(BOOK, "/b.md", "3.1. A Simple Java Program", "Explain Program"));
    }
}

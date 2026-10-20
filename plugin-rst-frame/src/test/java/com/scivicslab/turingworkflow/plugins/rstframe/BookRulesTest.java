package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a book's paragraphs into rules: whether a writer's OCR correction is taken, what it is
 * asked about a paragraph's relations, and the rules its answer becomes
 * ({@code FramesAndSamplesFromBooks_261021_oo01}).
 */
@DisplayName("本の段落を規則にする: 読み違いの直しの採否、関係の質問と答え、規則の組み立て")
class BookRulesTest {

    private final BookRules rules = new BookRules();

    @Test
    @DisplayName("数文字の読み違いの直しは採り、段落を書き換えた答えは採らない")
    void anOcrCorrectionIsTakenOnlyWhenItChangesAFewCharacters() {
        String original = "この手続きを insert(x, y, roct)の形で呼び出せばよい. 手続き insert は, search と同じ方法で木を上から下に進む.";
        assertTrue(rules.correctionAccepted(original,
                "この手続きを insert(x, y, root)の形で呼び出せばよい. 手続き insert は, search と同じ方法で木を上から下に進む."));
        assertFalse(rules.correctionAccepted(original,
                "insert(x, y, root) と呼び出す. この手続きは search と同様に、木を根から葉へ向かって辿る."));
        assertFalse(rules.correctionAccepted(original, "  "));
    }

    @Test
    @DisplayName("読み違いの質問は、段落と前後の段落を見せ、直しだけを求める")
    void theOcrQuestionAsksForCorrectionsOnly() {
        String q = rules.ocrPrompt("木の roct から探す.", "前の段落.", "次の段落.");
        assertTrue(q.contains("木の roct から探す."), q);
        assertTrue(q.contains("前の段落.") && q.contains("次の段落."), q);
        assertTrue(q.contains("Do not rephrase"), q);
    }

    @Test
    @DisplayName("関係の質問は、文に番号を振って見せ、番号と関係の名前だけを求める")
    void theRelationQuestionNumbersTheSentences() {
        String q = rules.relationPrompt(List.of("The program declares a method called main.", "A method is a function."));
        assertTrue(q.contains("1. The program declares a method called main."), q);
        assertTrue(q.contains("2. A method is a function."), q);
        assertTrue(q.contains("definition") && q.contains("domain-meaning"), "the relation names are listed: " + q);
    }

    @Test
    @DisplayName("答えから文ごとの関係を読む。核がちょうど 1 つで、名前が一覧にあるときだけ受け取る")
    void theAnswerIsReadIntoOneRelationPerSentence() {
        assertEquals(List.of("nucleus", "definition", "elaboration-additional"),
                rules.parseRelations("1: nucleus\n2: definition\n3: elaboration-additional\n", 3));
        assertEquals(List.of("nucleus", "example"), rules.parseRelations("Here you are.\n1 - nucleus\n2 - example", 2));
        assertThrows(IllegalArgumentException.class, () -> rules.parseRelations("1: definition\n2: example", 2), "no nucleus");
        assertThrows(IllegalArgumentException.class, () -> rules.parseRelations("1: nucleus\n2: nucleus", 2), "two nuclei");
        assertThrows(IllegalArgumentException.class, () -> rules.parseRelations("1: nucleus\n2: frobnicate", 2), "an unknown name");
        assertThrows(IllegalArgumentException.class, () -> rules.parseRelations("1: nucleus", 2), "a sentence left out");
    }

    @Test
    @DisplayName("段落の規則は、文の順に関係の名前と文の規則の対を並べ、文は文字列の規則になる")
    void aParagraphBecomesASubParagraphRule() {
        String r = rules.paragraphRules("b4", List.of("The program declares a method called main.", "A method is a \"function\"."),
                List.of("nucleus", "definition"));
        assertEquals("""
                (defrule b4 -> :nucleus b4/1 :definition b4/2)
                (defrule b4/1 -> "The program declares a method called main.")
                (defrule b4/2 -> "A method is a \\"function\\".")
                """, r);
    }

    @Test
    @DisplayName("節は見出しと出典を持ち、用途のファイルの sections に足される")
    void aSectionIsAddedToTheFileOfItsUse() {
        String empty = rules.newUseFile("LanguageFeature_261021_oo01", "Java の文法の説明");
        String once = rules.addSection(empty, "s1", "3.1. A Simple Java Program", "/books/core-java/p001-005.md",
                List.of("b1", "b2"), "(defrule b1 -> :nucleus b1/1)\n(defrule b1/1 -> \"Hi.\")\n(defrule b2 -> :file \"b2.txt\" :kind code-block)\n");
        assertTrue(once.contains("(defrule sections -> s1)"), once);
        assertTrue(once.contains("(defrule s1 -> b1 b2 :heading \"## 3.1. A Simple Java Program\" :source \"/books/core-java/p001-005.md\")"), once);
        String twice = rules.addSection(once, "s2", "2.4 2分探索木", "/books/algo/p101-110.md", List.of("b3"),
                "(defrule b3 -> :nucleus b3/1)\n(defrule b3/1 -> \"木.\")\n");
        assertTrue(twice.contains("(defrule sections -> s1 s2)"), twice);
        assertTrue(twice.indexOf("(defrule s1 ") < twice.indexOf("(defrule s2 "), twice);
    }
}

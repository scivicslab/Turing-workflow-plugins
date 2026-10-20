package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Making a frame from a sample, one question per block ({@code FramesAndSamplesFromBooks_261021_oo01}).
 */
@DisplayName("お手本 1 本から、まとまり 1 つにつき質問 1 つで、同じ名前のフレームを作る")
class SampleToFrameTest {

    private static final String SAMPLE = """
            ;;; use: Shows a short program whole, then defines what it uses.
            ;;; source: /books/core/p001.md / 3.1. A Simple Java Program
            ;;; shape: nucleus → code → nucleus+definition
            (in-package :rst)
            (defdocument ShowWholeThenExplainSample)

            (defrule document -> title sections)
            (defrule title -> "3.1. A Simple Java Program")
            (defrule sections -> s1)

            (defrule s1 -> b1 b2 b3 :heading "## 3.1. A Simple Java Program" :source "/books/core/p001.md")
            (defrule b1 -> :nucleus b1/1)
            (defrule b1/1 -> "Let's look at one of the simplest Java programs:")
            (defrule b2 -> :file "show-whole-then-explain.b2.txt" :kind code-block)
            (defrule b3 -> :nucleus b3/1 :definition b3/2)
            (defrule b3/1 -> "The program declares a method called main.")
            (defrule b3/2 -> "The term \\"method\\" is Java-speak for a function.")
            """;

    private final List<String> questions = new ArrayList<>();

    private SampleToFrame run(SampleToFrame f, java.util.function.Function<String, String> writer) {
        String q;
        while ((q = f.nextQuestion()) != null) {
            questions.add(q);
            f.answer(writer.apply(q));
        }
        return f;
    }

    @Test
    @DisplayName("文は :instruction と元の文の :sample を持つ空欄になり、コードはコードの空欄になる")
    void sentencesAndCodeBecomeBlanks() {
        SampleToFrame f = run(new SampleToFrame(SAMPLE, "show-whole-then-explain", java.util.Map.of("show-whole-then-explain.b2.txt", "void main() {}")), q -> {
            if (q.contains("code block")) return "Show the whole program being explained.";
            if (q.contains("main.")) return "1: Say where in the code the item is declared.\n2: Define the item in plain words.";
            return "1: Introduce the program the section is about.";
        });
        assertEquals(3, questions.size(), questions.toString());
        assertTrue(questions.get(1).contains("void main() {}"), "the code is shown: " + questions.get(1));
        String frame = f.frameFile();
        assertTrue(frame.startsWith(";;; use: Shows a short program whole, then defines what it uses.\n"), frame);
        assertTrue(frame.contains(";;; made-from: show-whole-then-explain"), frame);
        assertTrue(frame.contains("(defrule b1/1 -> :instruction \"Introduce the program the section is about.\" :sample \"Let's look at one of the simplest Java programs:\")"), frame);
        assertTrue(frame.contains("(defrule b2 -> :instruction \"Show the whole program being explained.\" :kind code-block)"), frame);
        assertTrue(frame.contains("(defrule b3 -> :nucleus b3/1 :definition b3/2)"), frame);
        assertTrue(frame.contains("(defrule b3/2 -> :instruction \"Define the item in plain words.\" :sample \"The term \\\"method\\\" is Java-speak for a function.\")"), frame);
        assertFalse(frame.contains(":file"), frame);
        assertNull(new FrameFiller().next(frame.replaceAll(":instruction \"[^\"]*\"", "\"x\"")) , "every sentence and the code became a blank");
    }

    @Test
    @DisplayName("答えに無い文には、関係から作った指示を入れる")
    void aSentenceLeftOutGetsAnInstructionFromItsRelation() {
        SampleToFrame f = run(new SampleToFrame(SAMPLE, "x", java.util.Map.of("show-whole-then-explain.b2.txt", "void main() {}")), q -> q.contains("main.") ? "1: Say where it is declared." : "Show it.");
        String frame = f.frameFile();
        assertTrue(frame.contains("(defrule b3/2 -> :instruction \"Write the sentence that stands to the paragraph's nucleus as its definition.\""), frame);
    }

    @Test
    @DisplayName("箇条書きは :kind を持つ 1 つの空欄になり、項目の規則は残らない")
    void aListBecomesOneBlankWithItsKind() {
        String sample = SAMPLE.replace("(defrule s1 -> b1 b2 b3 ", "(defrule s1 -> b1 b2 b3 b4 ")
                + "(defrule b4 -> b4/1 b4/2 :kind bullet)\n(defrule b4/1 -> \"Declare main.\")\n(defrule b4/2 -> \"Use println\")\n";
        SampleToFrame f = run(new SampleToFrame(sample, "x", java.util.Map.of("show-whole-then-explain.b2.txt", "void main() {}")),
                q -> q.contains("list") ? "List the constraints." : "1: Say it.\n2: Define it.");
        String frame = f.frameFile();
        assertTrue(frame.contains("(defrule b4 -> :instruction \"List the constraints.\" :sample \"Declare main. / Use println\" :kind bullet)"), frame);
        assertFalse(frame.contains("(defrule b4/1"), frame);
        FrameFiller.Blank blank = new FrameFiller().next(frame.replaceAll("\\(defrule b[123](/\\d+)? -> :instruction[^\\n]*", "(defrule b0 -> \"x\")"));
        assertEquals("b4", blank.name());
        assertTrue(blank.isList());
    }
}

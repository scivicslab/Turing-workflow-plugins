package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the questions asked per concept and paragraph, the answers read, and the classes grown. */
class SlotAssignmentTest {

    private static final String FILE = "doc_Y/docs/Papers/loop/loop.lisp";
    private static final String TEXT = """
            (defrule document -> title sections)
            (defrule title -> "How the loop works")
            (defrule sections -> s-result s-control)
            (defrule s-result -> p-result p-field :heading "## Handle the result")
            (defrule p-result -> :nucleus p-result/n)
            (defrule p-result/n -> "The ResultMessage marks the end of the loop; its subtype tells how it ended.")
            (defrule p-field -> :nucleus p-field/n)
            (defrule p-field/n -> "The field result is present only on success.")
            (defrule s-control -> p-effort p-model :heading "## Control how the loop runs")
            (defrule p-effort -> :nucleus p-effort/n)
            (defrule p-effort/n -> "The effort level sets how much the model reasons: low, medium or high.")
            (defrule p-model -> :nucleus p-model/n)
            (defrule p-model/n -> "The model option picks which model runs the loop.")
            """;
    private static final String SUBJECTS = """
            (in-package :rst)
            (defsubjects loop-doc :from "x")
            (subject "1" "ResultMessage" :paragraphs "p-result p-field")
            (subject "2" "effort level" :relation setting-of :paragraphs "p-effort")
            (subject "3" "model" :relation setting-of :paragraphs "p-model")
            """;

    private static SlotAssignment work(ConceptClasses classes) {
        return new SlotAssignment("loop-doc", Map.of(FILE, TEXT), SUBJECTS, classes);
    }

    private static ConceptClasses classes() {
        return new ConceptClasses(Map.of("messages.lisp", ConceptClassesTest.MESSAGES));
    }

    @Test
    void aConceptWithAClassOfItsNameIsAskedAboutItsParagraphsAtOnce() {
        SlotAssignment s = work(classes());
        String q = s.nextQuestion();
        assertTrue(q.contains("ResultMessage is a kind of message: Something the agent loop hands to the program."), q);
        assertTrue(q.contains("- when-delivered: When the program is handed this message\n- carried-values: What values it carries\n- how-it-ended: How the loop ended\n"), q);
        assertTrue(q.contains("The ResultMessage marks the end of the loop"), q);
        s.answer("how-it-ended");
        s.answer("`carried-values`");
        assertEquals(Map.of("how-it-ended", List.of("p-result"), "carried-values", List.of("p-field")), s.placed().get(0).slots());
    }

    @Test
    void aNewQuestionIsAddedWhereTheWriterSaysItReachesAndIsInheritedBelow() {
        ConceptClasses c = classes();
        SlotAssignment s = work(c);
        s.nextQuestion();
        s.answer("how-it-ended");
        s.nextQuestion();
        s.answer("NEW: presence: When each value is present");
        String q = s.nextQuestion();
        assertTrue(q.contains("presence: When each value is present"), q);
        assertTrue(q.contains("- result-message: The message that marks the end of the loop\n- message: Something the agent loop hands to the program\n"), q);
        s.answer("message");
        assertTrue(c.whatCanBeSaid("system-message").stream().anyMatch(x -> x.name().equals("presence")));
        assertEquals(List.of("message"), s.grown());
        assertEquals(List.of("p-field"), s.placed().get(0).slots().get("presence"));
    }

    @Test
    void aConceptWithNoClassGetsAGeneralKindAndTheNextOfItsSortFindsIt() {
        ConceptClasses c = classes();
        SlotAssignment s = work(c);
        s.nextQuestion();
        s.answer("how-it-ended");
        s.nextQuestion();
        s.answer("carried-values");
        String q = s.nextQuestion();
        assertTrue(q.startsWith("A document explains effort level."), q);
        assertTrue(q.contains("- message: Something the agent loop hands to the program\n"), q);
        s.answer("NEW: loop option: A value that changes how the agent loop runs");
        q = s.nextQuestion();
        assertTrue(q.contains("effort level is a kind of loop-option: A value that changes how the agent loop runs."), q);
        assertTrue(q.contains("Nothing has been asked of effort level yet."), q);
        s.answer("NEW: values: Which values it takes, and what each does");
        s.answer("loop-option");
        // the model option is then shown the kind made for the effort level, and inherits its question
        q = s.nextQuestion();
        assertTrue(q.contains("- loop-option: A value that changes how the agent loop runs\n"), q);
        s.answer("loop-option");
        q = s.nextQuestion();
        assertTrue(q.contains("The narrower kinds of loop-option"), q);
        s.answer("HERE");
        q = s.nextQuestion();
        assertTrue(q.contains("- values: Which values it takes, and what each does\n"), q);
        s.answer("values");
        assertNull(s.nextQuestion());
        assertEquals(List.of("loop-option", "effort-level", "model"), s.added());
        String out = s.resultFile("x");
        assertTrue(out.contains("(defclass loop-option ()\n  ((values :documentation \"Which values it takes, and what each does\"))"), out);
        assertTrue(out.contains("(defclass model (loop-option) ()"), out);
        assertTrue(out.contains("(assign \"model\" model\n  (values \"p-model\"))"), out);
    }

    @Test
    void anUnreadableAnswerIsAskedAgainOnceThenNoted() {
        SlotAssignment s = work(classes());
        s.nextQuestion();
        s.answer("I think it is about endings in general");
        assertTrue(s.nextQuestion().endsWith("Answer with one line in the form asked, and nothing else."));
        s.answer("still not a name");
        assertEquals(1, s.notes().size());
        assertTrue(s.nextQuestion().contains("The field result is present only on success."));
    }

    @Test
    void anAnswerGivingTheNameWithItsQuestionIsReadAsTheName() {
        SlotAssignment s = work(classes());
        s.nextQuestion();
        s.answer("how-it-ended: How the loop ended");
        assertEquals(Map.of("how-it-ended", List.of("p-result")), s.placed().get(0).slots());
    }

    @Test
    void aNewQuestionNamingTheConceptIsAskedAgainWithItInItsPlace() {
        ConceptClasses c = classes();
        SlotAssignment s = work(c);
        s.nextQuestion();
        s.answer("how-it-ended");
        s.nextQuestion();
        s.answer("NEW: result-message: What is in a ResultMessage");
        assertTrue(s.nextQuestion().contains("Write the question with \"it\" in its place"));
        s.answer("NEW: presence: When is each value present");
        assertTrue(s.nextQuestion().startsWith("A paragraph of a document about ResultMessage answers a question not asked of it before"));
    }

    private static final String TOP = """
            (defrule document -> title harness)
            (defrule title -> :instruction "Name the harness")
            (defrule harness -> :nucleus what :elaboration-part-whole messages-part)
            (defrule what -> :instruction "Define the harness in one sentence" :sample "A harness wraps a model.")
            (defrule messages-part -> :nucleus message-kinds)
            (defrule message-kinds -> :instruction "List the kinds of message the loop hands out" :frame "explain-loop.message-kind.lisp")
            """;
    private static final String SUB = """
            (defrule message-kind -> :nucleus message-kind/carries)
            (defrule message-kind/carries -> :instruction "Say what it carries" :sample "Each carries one content block.")
            """;

    @Test
    void aFramesBlanksBecomeSlotsAndABlankNamingASubFrameIsTypedWithItsClass() {
        ConceptClasses c = classes();
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("frames/explanation/explain-loop.lisp", TOP);
        files.put("frames/explanation/explain-loop.message-kind.lisp", SUB);
        SlotAssignment s = SlotAssignment.fromFrame("explain-loop", files, c);
        // the sub-frame first: asked what it is, then said to be the message class under another name, and that confirmed
        String q = s.nextQuestion();
        assertTrue(q.endsWith("without naming any particular product."), q);
        s.answer("Something the agent loop hands to the program that drives it.");
        q = s.nextQuestion();
        assertTrue(q.startsWith("An explanation frame has blanks for writing about message kind. message kind: Something the agent loop hands"), q);
        assertTrue(q.contains("SAME <name>"), q);
        s.answer("SAME message");
        q = s.nextQuestion();
        assertTrue(q.contains("Are message kind and message one and the same thing, under two names?"), q);
        assertTrue(q.contains("- What values it carries\n"), q);
        s.answer("YES");
        q = s.nextQuestion();
        assertTrue(q.contains("A blank of the frame: what the writer is asked to write in it"), q);
        assertTrue(q.contains("Say what it carries\nan example of what was written: Each carries one content block."), q);
        s.answer("carried-values");
        // then the part of the top frame, whose blank names the sub-frame
        s.nextQuestion();
        s.answer("What a loop hands out while it runs.");
        q = s.nextQuestion();
        assertTrue(q.startsWith("An explanation frame has blanks for writing about messages."), q);
        // a SAME the writer then denies leaves the concept to be placed, and the class is not offered as the same again
        s.answer("SAME message");
        s.nextQuestion();
        s.answer("NO");
        assertTrue(s.nextQuestion().contains("It is not the same thing as message."));
        s.answer("NEW: report stream: What a loop hands out while it runs");
        s.nextQuestion();
        s.answer("NEW: kinds: Which kinds of message it hands out");
        assertTrue(s.nextQuestion().startsWith("A blank of an explanation frame about messages asks a question not asked of it before"));
        s.answer("messages");
        // then the harness itself, last
        s.nextQuestion();
        s.answer("A program that runs a model in a loop.");
        q = s.nextQuestion();
        assertTrue(q.startsWith("An explanation frame has blanks for writing about loop."), q);
        s.answer("NEW: runtime: A program that runs a model");
        s.nextQuestion();
        s.answer("ASIDE");
        s.nextQuestion();
        s.answer("NEW: definition: What is it, in one sentence");
        s.nextQuestion();
        s.answer("runtime");
        assertNull(s.nextQuestion());
        assertTrue(c.source("messages").contains("(kinds :type message :documentation \"Which kinds of message it hands out\")"), c.source("messages"));
        assertEquals(Map.of("carried-values", List.of("message-kind/carries")), s.placed().get(0).slots());
        assertEquals("message", s.placed().get(0).cls());
        assertTrue(s.resultFile("x").contains(";;; frame-file: frames/explanation/explain-loop.lisp"));
    }
}

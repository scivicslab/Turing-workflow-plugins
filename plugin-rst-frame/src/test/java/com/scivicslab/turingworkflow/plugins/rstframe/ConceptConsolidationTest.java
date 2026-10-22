package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: shared slots go up, general questions go up when the writer says so, and classes that say nothing more become values. */
class ConceptConsolidationTest {

    private static final String RESULT = """
            ;;; book-file: /w/loop.lisp
            (in-package :concepts)
            (defclass configuration-setting ()
              ((values :documentation "Which values does it take"))
              (:documentation "A value that changes how the loop runs"))
            (defclass reasoning-effort (configuration-setting) ()
              (:documentation "How much the model reasons"))
            (defclass effort-level (reasoning-effort) ()
              (:documentation "The effort option"))
            (defclass model (configuration-setting)
              ((default-behavior :documentation "What does it do if not set?"))
              (:documentation "Which model runs"))
            (defclass permission-mode (configuration-setting)
              ((default-behavior :documentation "What does it do if not set?")
               (tool-approval :documentation "Which tool calls need approval?"))
              (:documentation "How tools are approved"))
            (assign "effort level" effort-level
              (values "p-effort"))
            (assign "model" model
              (default-behavior "p-model"))
            """;

    @Test
    void theTreeBecomesSmallerAndAssignmentsFollow() {
        try (ConceptClasses c = new ConceptClasses(Map.of("assignments/loop.lisp", RESULT))) {
            ConceptConsolidation k = new ConceptConsolidation(c, RESULT);
            // default-behavior, defined by two kinds of configuration-setting, went up without a question
            assertTrue(c.ownSlots("configuration-setting").stream().anyMatch(s -> s.name().equals("default-behavior")));
            // the one slot left of its own is asked about
            String q = k.nextQuestion();
            assertTrue(q.startsWith("permission-mode is a kind of configuration-setting: A value that changes how the loop runs."), q);
            assertTrue(q.contains("The other kinds of configuration-setting: model, reasoning-effort."), q);
            assertTrue(q.contains("tool-approval: Which tool calls need approval?"), q);
            k.answer("NO");
            assertNull(k.nextQuestion());
            // model said nothing more and became a value; effort-level, then reasoning-effort, too
            assertEquals(List.of("configuration-setting", "permission-mode"), c.names());
            assertEquals(Map.of("effort level", "configuration-setting", "reasoning effort", "configuration-setting", "model", "configuration-setting"),
                    c.instances());
            assertEquals("configuration-setting", c.find("effort level"));
            String out = k.resultFile("assignments/loop.lisp");
            assertTrue(out.contains(";;; standalone\n;;; book-file: /w/loop.lisp\n"), out);
            assertTrue(out.contains("(instance \"effort level\" configuration-setting)"), out);
            assertTrue(out.contains("(assign \"effort level\" configuration-setting\n  (values \"p-effort\"))"), out);
            assertTrue(out.indexOf("(defclass configuration-setting") < out.indexOf("(defclass permission-mode"), out);
        }
    }

    @Test
    void aQuestionTheWriterFindsGeneralGoesUpAndItsClassBecomesAValue() {
        try (ConceptClasses c = new ConceptClasses(Map.of("assignments/loop.lisp", RESULT))) {
            ConceptConsolidation k = new ConceptConsolidation(c, RESULT);
            k.nextQuestion();
            k.answer("Yes.");
            assertNull(k.nextQuestion());
            assertTrue(c.ownSlots("configuration-setting").stream().anyMatch(s -> s.name().equals("tool-approval")));
            assertEquals(List.of("configuration-setting"), c.names());
        }
    }
}

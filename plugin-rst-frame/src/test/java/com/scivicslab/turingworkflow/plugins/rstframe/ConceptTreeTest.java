package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the questions asked per paragraph, the answers read, and the tree written. */
class ConceptTreeTest {

    private static final String FILE = "doc_Y/docs/Papers/loop/loop.lisp";
    private static final String TEXT = """
            (defrule document -> title sections)
            (defrule title -> "How the loop works")
            (defrule sections -> s-turns s-control)
            (defrule s-turns -> p-turn :heading "## Turns and messages")
            (defrule p-turn -> :nucleus p-turn/n :elaboration-additional p-turn/more)
            (defrule p-turn/n -> "A turn is one model call and the tools it asks for.")
            (defrule p-turn/more -> "The loop repeats turns until the model stops.")
            (defrule s-control -> p-max p-mode :heading "## Control how the loop runs")
            (defrule p-max -> :nucleus p-max/n)
            (defrule p-max/n -> "Max turns caps how many turns a run may take.")
            (defrule p-mode -> :nucleus p-mode/n)
            (defrule p-mode/n -> "The permission mode decides whether tools need approval.")
            """;

    private static ConceptTree tree() {
        return new ConceptTree("loop-doc", Map.of(FILE, TEXT), List.of(), true);
    }

    @Test
    void eachParagraphIsOneUnitWithItsNucleusHeadingAndSatellites() {
        List<ConceptTree.Unit> u = tree().units();
        assertEquals(List.of("title", "p-turn", "p-max", "p-mode"), u.stream().map(ConceptTree.Unit::rule).toList());
        assertEquals("Turns and messages", u.get(1).heading());
        assertEquals("A turn is one model call and the tools it asks for.", u.get(1).nucleus());
        assertEquals(List.of("elaboration-additional: The loop repeats turns until the model stops."), u.get(1).satellites());
        assertEquals("Control how the loop runs", u.get(2).heading());
    }

    @Test
    void aParagraphUnderATaskHeadingIsPlacedUnderTheConceptItIsAbout() {
        ConceptTree t = tree();
        t.nextQuestion(); t.answer("NEW: agent loop");          // title
        String q = t.nextQuestion();
        assertTrue(q.contains("Concepts found so far:\n- agent loop\n"), q);
        assertTrue(q.contains("Its central sentence (nucleus): A turn is one model call"), q);
        t.answer("NEW: turn");                                  // p-turn
        String rel = t.nextQuestion();
        assertTrue(rel.startsWith("A new concept was found in a document: turn"), rel);
        t.answer("PART-OF agent loop");
        t.nextQuestion(); t.answer("NEW: max turns");           // p-max, under the task heading
        t.nextQuestion(); t.answer("SETTING-OF agent loop");
        t.nextQuestion(); t.answer("NEW: permission mode");     // p-mode
        t.nextQuestion(); t.answer("SETTING-OF agent loop");
        assertNull(t.nextQuestion());

        String out = t.subjectsFile("the body of loop.lisp");
        assertTrue(out.contains("(subject \"1\" \"agent loop\" :paragraphs \"title\")"), out);
        assertTrue(out.contains("(subject \"1.1\" \"turn\" :relation part-of :paragraphs \"p-turn\")"), out);
        assertTrue(out.contains("(subject \"1.2\" \"max turns\" :relation setting-of :paragraphs \"p-max\")"), out);
        assertTrue(out.contains("(subject \"1.3\" \"permission mode\" :relation setting-of :paragraphs \"p-mode\")"), out);
        assertTrue(!out.contains("Control how the loop runs"), "a task heading is no node");
    }

    @Test
    void aParagraphAboutAKnownConceptJoinsItWhereverItStands() {
        ConceptTree t = tree();
        t.nextQuestion(); t.answer("NEW: turn");
        t.nextQuestion(); t.answer("turn");
        t.nextQuestion(); t.answer("- Turn.");                  // case, a bullet and a full stop are not a new concept
        t.nextQuestion(); t.answer("NEW: permission mode");
        t.nextQuestion(); t.answer("NONE");
        assertNull(t.nextQuestion());
        assertEquals(List.of("loop.lisp / title", "loop.lisp / p-turn", "loop.lisp / p-max"), t.concepts().get(0).paragraphs());
    }

    @Test
    void anAnswerThatCannotBeReadIsAskedOnceMoreThenNoted() {
        ConceptTree t = tree();
        t.nextQuestion(); t.answer("I think it is about the loop in general");
        String again = t.nextQuestion();
        assertTrue(again.contains("Your previous answer could not be read."), again);
        t.answer("still not a name");
        assertTrue(t.notes().get(0).startsWith("doc_Y/docs/Papers/loop/loop.lisp / title: no concept could be read"), t.notes().toString());
        assertTrue(t.nextQuestion().contains("A turn is one model call"), "the next paragraph comes after the note");
    }

    @Test
    void aTextbookGivesItsHeadingsAsConceptsAndTakesNoNewOne() {
        ConceptTree t = new ConceptTree("book", Map.of(FILE, TEXT), List.of("Turns", "Settings"), false);
        String q = t.nextQuestion();
        assertTrue(q.contains("- Turns\n- Settings\n") && !q.contains("NEW:"), q);
        t.answer("NEW: something");
        assertTrue(t.nextQuestion().contains("Your previous answer could not be read."));
    }
}

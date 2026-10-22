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
        t.nextQuestion(); t.answer("NEW: max turns");           // p-max, under the task heading
        t.nextQuestion(); t.answer("NEW: the loop");            // p-mode: a second name for the loop, on purpose
        // after the paragraphs: is each the same as another?
        assertTrue(t.nextQuestion().contains("Is \"agent loop\" the same thing"));  t.answer("DIFFERENT");
        assertTrue(t.nextQuestion().contains("Is \"turn\" the same thing"));        t.answer("DIFFERENT");
        assertTrue(t.nextQuestion().contains("Is \"max turns\" the same thing"));   t.answer("DIFFERENT");
        assertTrue(t.nextQuestion().contains("Is \"the loop\" the same thing"));    t.answer("SAME agent loop");
        // then the root, and every other concept taken down from it
        String rootQ = t.nextQuestion();
        assertTrue(rootQ.startsWith("A document titled: How the loop works\n\nThe concepts found in the document:"), rootQ);
        t.answer("agent loop");
        String how = t.nextQuestion();                          // turn: nothing under the root yet, so straight to how
        assertTrue(how.startsWith("In a document, turn belongs to agent loop."), how);
        t.answer("PART-OF");
        String down = t.nextQuestion();                         // max turns: the root now has turn under it
        assertTrue(down.contains("The narrower concepts already placed under agent loop, each with what the document says of it:\n"
                + "- turn: A turn is one model call and the tools it asks for.\n"), down);
        assertTrue(down.contains("What the document says of max turns:\n- Max turns caps"), down);
        t.answer("HERE");
        t.nextQuestion(); t.answer("setting-of");
        assertNull(t.nextQuestion());

        String out = t.subjectsFile("the body of loop.lisp");
        assertTrue(out.contains("(subject \"1\" \"agent loop\" :paragraphs \"title p-mode\")"), out);
        assertTrue(out.contains("(subject \"1.1\" \"turn\" :relation part-of :paragraphs \"p-turn\")"), out);
        assertTrue(out.contains("(subject \"1.2\" \"max turns\" :relation setting-of :paragraphs \"p-max\")"), out);
        assertTrue(!out.contains("the loop") && !out.contains("Control how the loop runs"), out);
    }

    @Test
    void aParagraphAboutAKnownConceptJoinsItWhereverItStands() {
        ConceptTree t = tree();
        t.nextQuestion(); t.answer("NEW: turn");
        t.nextQuestion(); t.answer("turn");
        t.nextQuestion(); t.answer("- Turn.");                  // case, a bullet and a full stop are not a new concept
        t.nextQuestion(); t.answer("permission mode");          // a name not in the list is a new concept without NEW: too
        assertEquals(List.of("turn", "permission mode"), t.concepts().stream().map(ConceptTree.Concept::name).toList());
        assertEquals(List.of("loop.lisp / title", "loop.lisp / p-turn", "loop.lisp / p-max"), t.concepts().get(0).paragraphs());
    }

    @Test
    void anAnswerThatCannotBeReadIsAskedOnceMoreThenNoted() {
        ConceptTree t = tree();
        t.nextQuestion(); t.answer("I think it is about the loop in general. It covers turns.");
        String again = t.nextQuestion();
        assertTrue(again.contains("Your previous answer could not be read."), again);
        t.answer("It is about the loop. And about turns.");
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

    @Test
    void aParagraphsWholeTextAndACodeBlocksFileAreGiven() {
        String f = TEXT + "(defrule p-code -> :file \"b9.txt\" :kind code-block)\n";
        assertEquals("A turn is one model call and the tools it asks for. The loop repeats turns until the model stops.",
                ConceptTree.paragraphText(f, "p-turn"));
        assertEquals("file:b9.txt", ConceptTree.paragraphText(f, "p-code"));
        assertNull(ConceptTree.paragraphText(f, "no-such-rule"));
    }
}

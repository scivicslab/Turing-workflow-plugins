package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: classes read, looked up, and grown, with inheritance computed by CLOS in ABCL. */
class ConceptClassesTest {

    static final String MESSAGES = """
            (defpackage :concepts (:use :cl))
            (in-package :concepts)
            (defclass message ()
              ((when-delivered :documentation "When the program is handed this message")
               (carried-values :documentation "What values it carries"))
              (:documentation "Something the agent loop hands to the program"))
            (defclass system-message (message) ()
              (:documentation "A message about the session itself"))
            (defclass result-message (message)
              ((how-it-ended :documentation "How the loop ended"))
              (:documentation "The message that marks the end of the loop"))
            """;

    private static ConceptClasses classes() {
        return new ConceptClasses(Map.of("concepts/messages.lisp", MESSAGES));
    }

    @Test
    void aConceptNameFindsItsClass() {
        ConceptClasses c = classes();
        assertEquals("result-message", c.find("ResultMessage"));
        assertEquals("system-message", c.find("system message"));
        assertNull(c.find("effort level"));
    }

    @Test
    void whatCanBeSaidIsTheInheritedSlotsThenTheClassesOwn() {
        assertEquals(List.of(
                new ConceptClasses.Slot("when-delivered", "message", "When the program is handed this message"),
                new ConceptClasses.Slot("carried-values", "message", "What values it carries"),
                new ConceptClasses.Slot("how-it-ended", "result-message", "How the loop ended")),
                classes().whatCanBeSaid("result-message"));
    }

    @Test
    void rootsSubclassesAndChainComeWithTheirDocumentation() {
        ConceptClasses c = classes();
        assertEquals(List.of(new ConceptClasses.Kind("message", "Something the agent loop hands to the program")), c.roots());
        assertEquals(List.of("result-message", "system-message"), c.subclasses("message").stream().map(ConceptClasses.Kind::name).toList());
        assertEquals(List.of("result-message", "message"), c.chain("result-message").stream().map(ConceptClasses.Kind::name).toList());
    }

    @Test
    void aSlotAddedToASuperclassIsSeenByEverySubclass() {
        ConceptClasses c = classes();
        c.addSlot("message", "cautions", "What to watch for when reading it");
        assertTrue(c.whatCanBeSaid("system-message").contains(new ConceptClasses.Slot("cautions", "message", "What to watch for when reading it")));
        assertEquals(3, c.whatCanBeSaid("system-message").size());
        // the subclass keeps its own slot
        assertTrue(c.whatCanBeSaid("result-message").stream().anyMatch(s -> s.name().equals("how-it-ended")));
    }

    @Test
    void aNewClassUnderANewKindIsDefinedAndWrittenBack() {
        ConceptClasses c = classes();
        c.define("loop-option", null, "A value that changes how the agent loop runs");
        c.define("effort-level", "loop-option", "");
        c.addSlot("loop-option", "values", "Which values it takes, and what each does");
        assertEquals("loop-option", c.find("Loop option"));
        assertEquals(List.of("values"), c.whatCanBeSaid("effort-level").stream().map(ConceptClasses.Slot::name).toList());
        assertEquals("""
                (defclass loop-option ()
                  ((values :documentation "Which values it takes, and what each does"))
                  (:documentation "A value that changes how the agent loop runs"))""", c.source("loop-option"));
        assertEquals("(defclass effort-level (loop-option) ())", c.source("effort-level"));
    }

    @Test
    void twoInstancesDoNotSeeEachOthersClasses() {
        ConceptClasses a = classes();
        ConceptClasses b = classes();
        a.define("hook", null, "");
        assertEquals("hook", a.find("Hook"));
        assertNull(b.find("Hook"));
    }

    @Test
    void aResultFileIsReadForItsClassesAndEachClassKnowsItsFile() {
        String result = """
                ;;; book-file: /x/loop.lisp
                (in-package :concepts)
                ;; slots added to a class that was there before
                (defclass message ()
                  ((when-delivered :documentation "When the program is handed this message")
                   (carried-values :documentation "What values it carries")
                   (cautions :documentation "What to watch for"))
                  (:documentation "Something the agent loop hands to the program"))
                (defclass loop-option () () (:documentation "A value that changes how the loop runs"))
                (assign "ResultMessage" result-message
                  (how-it-ended "p-result"))
                """;
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("concepts/messages.lisp", MESSAGES);
        files.put("assignments/loop.lisp", result);
        try (ConceptClasses c = new ConceptClasses(files)) {
            assertEquals(List.of("loop-option", "message", "result-message", "system-message"), c.names());
            assertEquals("concepts/messages.lisp", c.fileOf("message"));
            assertEquals("assignments/loop.lisp", c.fileOf("loop-option"));
            assertEquals(List.of("message"), c.superclasses("result-message"));
            assertTrue(c.whatCanBeSaid("result-message").stream().anyMatch(x -> x.name().equals("cautions")));
        }
    }

    @Test
    void closingRemovesTheClasses() {
        ConceptClasses c = classes();
        c.close();
        ConceptClasses d = classes();
        assertEquals(3, d.names().size());
        d.close();
    }

    @Test
    void aSlotOfAPartIsTypedWithThePartsClassAndKeepsItsTypeWhenTheClassGrows() {
        try (ConceptClasses c = classes()) {
            c.define("agent-loop", null, "The loop around a model");
            c.addSlot("agent-loop", "messages", "Which messages it hands to the program", "message");
            c.addSlot("agent-loop", "ending", "How it ends");
            assertEquals("""
                    (defclass agent-loop ()
                      ((messages :type message :documentation "Which messages it hands to the program")
                       (ending :documentation "How it ends"))
                      (:documentation "The loop around a model"))""", c.source("agent-loop"));
        }
    }

    @Test
    void aLispErrorIsAJavaExceptionAndNotTheDebugger() {
        try (ConceptClasses c = classes()) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> c.addSlot("no-such-class", "x", "y"));
        }
    }

    @Test
    void aConceptNamedLikeALispSymbolIsAClassOfItsOwn() {
        try (ConceptClasses c = classes()) {
            c.define("loop", null, "The agent loop");
            c.addSlot("loop", "values", "Which values it takes");
            assertEquals("loop", c.find("loop"));
            assertEquals(List.of("values"), c.whatCanBeSaid("loop").stream().map(ConceptClasses.Slot::name).toList());
        }
    }
}

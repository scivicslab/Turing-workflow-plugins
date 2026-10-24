package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FrameRegistryTest {

    private static FrameRegistry.Frame frame(String id, String name, String... questions) {
        FrameRegistry.Frame f = new FrameRegistry.Frame(id, name, null);
        f.add(name, List.of(questions));
        return f;
    }

    private static final FrameRegistry.Concrete FLOAT = new FrameRegistry.Concrete(new BookStore.RegistryMember("core", "3.3.2", "floating-point type"),
            List.of("What values does a floating-point type hold?", "How much storage does it take?"));

    @Test
    void theCandidatesAreTheFramesThatShareTheMostWordsAndOnlyThose() {
        var integer = frame("F0001", "integer type", "What values does an integer type hold?", "How much storage does it take?");
        var thread = frame("F0002", "thread", "How is a thread started?");
        var comment = frame("F0003", "comment", "How is a comment written?");
        assertEquals(List.of("F0001"), FrameRegistry.candidates(FLOAT, List.of(thread, integer, comment)).stream().map(f -> f.id).toList());
    }

    @Test
    void aFrameHoldingAConceptOfTheSameNameIsAlwaysACandidate() {
        var java = frame("F0001", "Java", "What is Java?");
        var thread = frame("F0002", "thread", "How is a thread started?");
        var c = new FrameRegistry.Concrete(new BookStore.RegistryMember("core", "1.2.1", "Java"), List.of("Why was it designed simple?"));
        assertEquals("F0001", FrameRegistry.candidates(c, List.of(thread, java)).get(0).id);
    }

    @Test
    void theMatchQuestionShowsTheConcreteFrameAndTheCandidatesWithTheirQuestions() {
        String q = FrameRegistry.matchQuestion(FLOAT, List.of(frame("F0001", "integer type", "What values does an integer type hold?")));
        assertTrue(q.contains("Here is the frame of \"floating-point type\", from section 3.3.2"), q);
        assertTrue(q.contains("F0001 \"integer type\": frames of integer type\n    What values does an integer type hold?"), q);
    }

    @Test
    void aMatchNamesACandidateAndMayRenameItAnIdNotAmongThemIsNoMatch() {
        List<FrameRegistry.Frame> cand = List.of(frame("F0001", "integer type"));
        assertEquals("F0001", FrameRegistry.readMatch("**match:** F0001", cand).id());
        assertEquals(new FrameRegistry.Match(null, null, null), FrameRegistry.readMatch("match: F0099", cand));
        assertEquals(new FrameRegistry.Match(null, null, "F0001"), FrameRegistry.readMatch("kind: F0001", cand), "a kind gets a frame of its own under it");
        assertNull(FrameRegistry.readMatch("new", cand).id());
    }

    @Test
    void theNameQuestionShowsEverySubjectOnce() {
        var f = new BookStore.RegistryFrame("F0001", "integer type", null, List.of(new BookStore.RegistryMember("core", "3.3.1", "integer type"),
                new BookStore.RegistryMember("core", "3.3.2", "floating-point type"), new BookStore.RegistryMember("core", "3.5", "integer type")));
        assertTrue(FrameRegistry.nameQuestion(f).contains("these subjects, from sections of a textbook: integer type; floating-point type.\n"));
    }

    @Test
    void aPlaceKeepsOnlyTheNearFramesAndAParentIsNoChild() {
        List<FrameRegistry.Frame> near = List.of(frame("F0001", "data type"), frame("F0002", "byte"), frame("F0003", "short"));
        assertEquals(new FrameRegistry.Place("F0001", List.of("F0002", "F0003")),
                FrameRegistry.readPlace("parent: F0001\nchildren: F0002, F0003, F0001, F0042", near));
        assertEquals(new FrameRegistry.Place(null, List.of()), FrameRegistry.readPlace("none", near));
    }

    @Test
    void aFrameAboveAnotherCannotBeMadeItsChild() {
        var top = frame("F0001", "data type");
        var mid = frame("F0002", "integer type");
        mid.parent = "F0001";
        Map<String, FrameRegistry.Frame> byId = Map.of("F0001", top, "F0002", mid);
        assertTrue(FrameRegistry.above("F0001", "F0002", byId), "data type stands above integer type");
        assertFalse(FrameRegistry.above("F0002", "F0001", byId));
    }
}

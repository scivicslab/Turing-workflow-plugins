package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GeneralFramesTest {

    @Test
    void namesAreComparedWithoutCasePluralAndRemarks() {
        assertEquals("comment", GeneralFrames.norm("Comments"));
        assertEquals("integer type", GeneralFrames.norm("integer types"));
        assertEquals("class", GeneralFrames.norm("classes"));
        assertEquals("class", GeneralFrames.norm("Class"));
        assertEquals("property", GeneralFrames.norm("properties"));
        assertEquals("lambda expression", GeneralFrames.norm("Lambda expressions (implicit in the code)"));
        assertEquals("status", GeneralFrames.norm("status"));
    }

    @Test
    void theGroupsAreTheReplysLinesAndAnAppearanceLeftOutIsAloneInItsGroup() {
        assertEquals(List.of(List.of(0, 2), List.of(1), List.of(3)), GeneralFrames.readGroups("G: 1, 3\n**G:** 2\nG: 9", 4));
    }

    @Test
    void theQuestionShowsTheAppearancesWithTheirItems() {
        String q = GeneralFrames.identifyQuestion("interface", List.of(new BookStore.Occurrence("6.1.1", "interface"),
                new BookStore.Occurrence("9.1.1", "interfaces")), Map.of("6.1.1\tinterface", List.of(new BookStore.FrameItem("What is an interface?", List.of("b1/1")))));
        assertTrue(q.contains("[1] \"interface\" in section 6.1.1\n    What is an interface?\n[2] \"interfaces\" in section 9.1.1"), q);
    }

    @Test
    void theItemsNameTheirSentencesBySectionAndRule() {
        List<GeneralFrames.Sentence> s = List.of(new GeneralFrames.Sentence("3.3.1", "integer type", "b1/1", "The integer types are for numbers."),
                new GeneralFrames.Sentence("3.3.2", "floating-point type", "b1/1", "Floating-point types denote numbers with fractional parts."));
        String q = GeneralFrames.itemsQuestion("primitive type", List.of("integer type", "floating-point type"), s);
        assertTrue(q.contains("[r2] (floating-point type, 3.3.2) Floating-point types"), q);
        assertTrue(q.contains("any kind of \"primitive type\""), q);
        assertEquals(List.of(new BookStore.FrameItem("What numbers does it hold?", List.of("3.3.1:b1/1", "3.3.2:b1/1"))),
                GeneralFrames.readItems("Q1: What numbers does it hold?\nQ2: Unused\nr1 | Q1\n[r2] | Q1\nr7 | Q1", s));
    }
}

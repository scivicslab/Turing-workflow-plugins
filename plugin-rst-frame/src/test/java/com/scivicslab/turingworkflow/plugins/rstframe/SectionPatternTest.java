package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SectionPatternTest {

    private static final String COMMENTS = """
            (defrule s1 -> b1 b2 b3 b4 :heading "## 3.2. Comments" :source "h2:core-java-14-vol1/3.2")
            (defrule b1 -> :nucleus b1/1 :consequence-n b1/2 :elaboration-additional b1/3 :example b1/4 :elaboration-process-step b1/5)
            (defrule b1/1 -> "Comments in Java, as in most programming languages, do not show up in the executable program.")
            (defrule b1/2 -> "Thus, you can add as many comments as needed without fear of bloating the code.")
            (defrule b1/3 -> "Java has three ways of marking comments.")
            (defrule b1/4 -> "The most common form is a //.")
            (defrule b1/5 -> "Use this for a comment that runs from the // to the end of the line.")
            (defrule b2 -> :file "b2.txt" :kind code-block)
            (defrule b3 -> :nucleus b3/1)
            (defrule b3/1 -> "When longer comments are needed, you can mark each line with a //, or you can use the /* and */ comment delimiters that let you block off a longer comment.")
            (defrule b4 -> :nucleus b4/1 :elaboration-object-attribute b4/2 :example b4/3 :elaboration-additional b4/4)
            (defrule b4/1 -> "Finally, a third kind of comment is used to generate documentation automatically.")
            (defrule b4/2 -> "This comment uses a /** to start and a */ to end.")
            (defrule b4/3 -> "You can see this type of comment in Listing 3.1.")
            (defrule b4/4 -> "For more on this type of comment and on automatic documentation generation, see Chapter 4.")
            """;

    @Test
    void blocksAreTheItemsOfTheHeadingRuleWithTheTextUnderThem() {
        List<SectionPattern.Block> blocks = SectionPattern.blocks(COMMENTS, Map.of("b2.txt", "System.out.println(\"Hi\"); // is this too cute?"));
        assertEquals(List.of("b1", "b2", "b3", "b4"), blocks.stream().map(SectionPattern.Block::name).toList());
        assertTrue(blocks.get(0).text().startsWith("Comments in Java"));
        assertTrue(blocks.get(0).text().endsWith("to the end of the line."));
        assertTrue(blocks.get(1).text().startsWith("(code)\nSystem.out.println"));
        assertTrue(blocks.get(3).text().contains("/** to start"));
    }

    @Test
    void theQuestionShowsEveryBlockByName() {
        String q = SectionPattern.question(new BookOutline.Node("3.2", "section", "Comments", "3", "f.md"), "comment", SectionPattern.blocks(COMMENTS, Map.of()));
        assertTrue(q.contains("[b1] Comments in Java"));
        assertTrue(q.contains("[b4] Finally, a third kind"));
        assertTrue(q.contains("uses to explain \"comment\""));
        assertTrue(q.contains("You must choose one of the three"));
        assertTrue(q.contains("the order is the text's, not the concept's"));
    }

    @Test
    void theReplyGivesThePatternTheWholeAndTheMembersInBlockOrder() {
        List<SectionPattern.Block> blocks = SectionPattern.blocks(COMMENTS, Map.of());
        SectionPattern.Layout l = SectionPattern.read("""
                **pattern:** Partition
                b4 | documentation comment (/** */)
                b1 | end-of-line comment (//)
                [b3] | block comment (/* */)
                b9 | no such block
                """, "comment", blocks);
        assertEquals(SectionPattern.PARTITION, l.pattern());
        assertEquals("comment", l.concept());
        assertEquals(List.of(new SectionPattern.Member("b1", "end-of-line comment (//)"), new SectionPattern.Member("b3", "block comment (/* */)"),
                new SectionPattern.Member("b4", "documentation comment (/** */)")), l.members());
    }

    @Test
    void theMainConceptIsTheTitleConceptWithTheMostRulesElseTheConceptWithTheMostRules() {
        BookStore.FrameRule r = new BookStore.FrameRule(BookStore.FrameRule.PROPERTY, "b1/1", "x");
        List<BookStore.FrameConcept> frame = List.of(new BookStore.FrameConcept("compiler", List.of(r, r, r), true),
                new BookStore.FrameConcept("comment", List.of(r, r), true), new BookStore.FrameConcept("Javadoc", List.of(r), true));
        List<BookStore.JudgedConcept> judged = List.of(new BookStore.JudgedConcept("comment", true, "in the title"),
                new BookStore.JudgedConcept("compiler", true, "nucleus"));
        assertEquals("comment", SectionPattern.mainConcept(judged, frame));
        assertEquals("compiler", SectionPattern.mainConcept(List.of(), frame));
        assertNull(SectionPattern.mainConcept(judged, List.of()));
    }

    @Test
    void aReplyNamingNoneOfTheThreeHasNoPattern() {
        assertNull(SectionPattern.read("pattern: comparison", "comment", SectionPattern.blocks(COMMENTS, Map.of())).pattern());
    }
}

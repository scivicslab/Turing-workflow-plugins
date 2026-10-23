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
        String q = SectionPattern.question(new BookOutline.Node("3.2", "section", "Comments", "3", "f.md"), SectionPattern.blocks(COMMENTS, Map.of()));
        assertTrue(q.contains("[b1] Comments in Java"));
        assertTrue(q.contains("[b4] Finally, a third kind"));
        assertTrue(q.contains("First name the section's main concept C"));
        assertTrue(q.contains("you can say \"X is a C\""));
    }

    @Test
    void thePatternIsTheRelationMostBlocksBearAndTheMembersAreInBlockOrder() {
        List<SectionPattern.Block> blocks = SectionPattern.blocks(COMMENTS, Map.of());
        SectionPattern.Layout l = SectionPattern.read("""
                **concept:** comment
                b4 | kind | documentation comment (/** */)
                b1 | **kind** | end-of-line comment (//)
                [b3] | kind | block comment (/* */)
                b2 | aspect | usage
                b9 | kind | no such block
                """, blocks);
        assertEquals(SectionPattern.PARTITION, l.pattern());
        assertEquals("comment", l.concept());
        assertEquals(List.of(new SectionPattern.Member("b1", "kind", "end-of-line comment (//)"), new SectionPattern.Member("b2", "aspect", "usage"),
                new SectionPattern.Member("b3", "kind", "block comment (/* */)"), new SectionPattern.Member("b4", "kind", "documentation comment (/** */)")),
                l.members());
    }

    @Test
    void aspectsWhenMostBlocksAreAspectsATieGoesToKindAndOneKindIsNoPartition() {
        List<SectionPattern.Block> blocks = SectionPattern.blocks(COMMENTS, Map.of());
        assertEquals(SectionPattern.ASPECTS, SectionPattern.read("concept: constant\nb1 | aspect | definition\nb3 | aspect | usage\nb4 | part | x", blocks).pattern());
        assertEquals(SectionPattern.PARTITION, SectionPattern.read("concept: constant\nb1 | aspect | definition\nb2 | aspect | usage\nb3 | kind | x\nb4 | kind | y", blocks).pattern());
        assertEquals(SectionPattern.ASPECTS, SectionPattern.read("concept: constant\nb1 | aspect | definition\nb3 | kind | x", blocks).pattern(),
                "one kind does not divide a set");
        SectionPattern.Layout table = SectionPattern.read("concept: process\nb1 | kind | Process\nb1 | kind | ProcessBuilder\nb2 | aspect | usage", blocks);
        assertEquals(SectionPattern.PARTITION, table.pattern(), "one block that takes up two kinds");
        assertEquals(List.of("Process", "ProcessBuilder", "usage"), table.members().stream().map(SectionPattern.Member::name).toList());
    }

    @Test
    void aReplyNamingNoneOfTheThreeHasNoPattern() {
        assertNull(SectionPattern.read("concept: comment\nb1 | comparison | x", SectionPattern.blocks(COMMENTS, Map.of())).pattern());
        assertNull(SectionPattern.read("b1 | kind | x", SectionPattern.blocks(COMMENTS, Map.of())).pattern(), "no concept named");
    }
}

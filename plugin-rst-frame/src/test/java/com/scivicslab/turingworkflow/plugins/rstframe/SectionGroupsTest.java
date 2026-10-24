package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: a section's blocks shown with their relations, the groups a reply makes, and the names a reply gives them. */
class SectionGroupsTest {

    private static final String RULES = """
            (defrule s1 -> b1 b2 b3 b4 :heading "## 1.2.5. Secure")
            (defrule b1 -> :nucleus b1/1 :purpose b1/2)
            (defrule b1/1 -> "Java is intended to be used in networked environments.")
            (defrule b1/2 -> "Toward that end, a lot of emphasis has been placed on security.")
            (defrule b2 -> :nucleus b2/1)
            (defrule b2/1 -> "Java was designed to make certain kinds of attacks impossible, among them:")
            (defrule b3 -> b3/1 b3/2 :kind bullet)
            (defrule b3/1 -> "Overrunning the runtime stack")
            (defrule b3/2 -> "Corrupting memory outside its own process space")
            (defrule b4 -> :nucleus b4/1 :cause b4/2)
            (defrule b4/1 -> "Initially, security bugs were fixed quickly.")
            (defrule b4/2 -> "Hackers got quite good at spotting subtle flaws.")
            """;

    @Test
    void aBlockShowsItsSentencesLabelledByTheirRelationAndAListItsItems() {
        List<SectionGroups.Block> b = SectionGroups.blocks(RULES, Map.of());
        assertEquals(List.of("b1", "b2", "b3", "b4"), b.stream().map(SectionGroups.Block::name).toList());
        assertEquals("nucleus: Java is intended to be used in networked environments.\npurpose: Toward that end, a lot of emphasis has been placed on security.", b.get(0).text());
        assertEquals("- Overrunning the runtime stack\n- Corrupting memory outside its own process space", b.get(2).text());
        assertTrue(SectionGroups.groupQuestion(new BookOutline.Node("1.2.5", "subsection", "Secure", null, "f.md"), b).contains("[b4]\nnucleus: Initially"));
    }

    @Test
    void theGroupsAreRangesOfConsecutiveBlocksAndNoneIsLeftOut() {
        List<SectionGroups.Block> b = SectionGroups.blocks(RULES, Map.of());
        List<SectionGroups.Group> g = SectionGroups.readGroups("b1 | sequence\n**b2-b3** | set\nb4 | none\n", b);
        assertEquals(2, g.size());
        assertEquals("set", g.get(1).type());
        assertEquals(List.of("b2", "b3"), g.get(1).blocks().stream().map(SectionGroups.Member::block).toList());
        assertEquals(List.of("b2-b3"), SectionGroups.readGroups("b2-b3 | set\nb1 | sequence\nb3 | structure\n", b).stream().map(x -> x.first() + "-" + x.last()).toList(),
                "a line starting inside a range already read is skipped");
        assertEquals(List.of(), SectionGroups.readGroups("b3-b2 | set\n", b), "a range that runs backwards");
        List<SectionGroups.Group> past = SectionGroups.readGroups("b1 | sequence\nb2-b9 | set\nb11-b12 | sequence\n", b);
        assertEquals(List.of("b2", "b3", "b4"), past.get(1).blocks().stream().map(SectionGroups.Member::block).toList(),
                "a range past the last block stops at it; a line starting at no block is skipped");
        assertEquals(2, past.size());
        assertTrue(SectionGroups.allNone("b1 | none\nb2-b3 | none\nb4 | none\n".replace("b2-b3 | none\n", "b2 | none\nb3 | none\n"), b),
                "a section that only leads into its subsections");
        assertTrue(!SectionGroups.allNone("b1 | none\nb2-b4 | set\n", b));
    }

    @Test
    void theSecondReplyNamesTheConceptsAndTheBlocks() {
        List<SectionGroups.Block> b = SectionGroups.blocks(RULES, Map.of());
        List<SectionGroups.Group> g = SectionGroups.readGroups("b1 | sequence\nb2-b3 | set\nb4 | none\n", b);
        g = SectionGroups.readNames("g1: reason Java puts weight on security\ng2: attacks Java prevents.\nb2 | introduction\n"
                + "b3 | overrunning the runtime stack\nb3 | corrupting memory\n", g);
        assertEquals("reason Java puts weight on security", g.get(0).concept());
        assertEquals("attacks Java prevents", g.get(1).concept());
        assertEquals(List.of(new SectionGroups.Member("b2", "introduction"), new SectionGroups.Member("b3", "overrunning the runtime stack"),
                new SectionGroups.Member("b3", "corrupting memory")), g.get(1).blocks());
        assertEquals("b3", g.get(1).last());
    }
}

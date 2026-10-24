package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the question on a section's groups, and the clusters a reply gives. */
class SectionClustersTest {

    private static final List<SectionGroups.Group> GROUPS = List.of(
            new SectionGroups.Group(1, "sequence", "end-of-line comment", List.of(new SectionGroups.Member("b1", "x"), new SectionGroups.Member("b2", "y"))),
            new SectionGroups.Group(2, "one", "block comment", List.of(new SectionGroups.Member("b3", ""))),
            new SectionGroups.Group(3, "one", "documentation comment", List.of(new SectionGroups.Member("b4", ""))),
            new SectionGroups.Group(4, "one", "nesting of comments", List.of(new SectionGroups.Member("b5", ""))));

    @Test
    void theQuestionShowsEachGroupWithTheMainSentenceOfItsBlocks() {
        String rules = """
                (defrule s1 -> b1 b2 b3 b4 b5 :heading "## 3.2. Comments")
                (defrule b1 -> :nucleus b1/1 :example b1/2)
                (defrule b1/1 -> "The most common form is //.")
                (defrule b1/2 -> "System.out.println(1); // a comment")
                (defrule b2 -> "It runs to the end of the line.")
                (defrule b3 -> "Longer ones use /* and */.")
                (defrule b4 -> "The third kind makes documentation.")
                (defrule b5 -> "They do not nest.")
                """;
        String q = SectionClusters.question(new BookOutline.Node("3.2", "section", "Comments", null, "f.md"), SectionGroups.blocks(rules, Map.of()), GROUPS);
        assertTrue(q.contains("g1: end-of-line comment (sequence, b1-b2)\n    b1: The most common form is //.\n    b2: It runs to the end of the line.\n"), q);
        assertTrue(q.contains("g3: documentation comment (one, b4)\n"), q);
    }

    @Test
    void theClustersAreRunsOfTwoGroupsOrMoreThatDoNotOverlap() {
        assertEquals(List.of(new SectionClusters.Cluster(1, "set", "comment", 1, 3)),
                SectionClusters.read("**g1-g3** | Set | comment.\ng2-g4 | sequence | overlapping\ng4-g4 | set | one group\n", GROUPS));
        assertEquals(List.of(new SectionClusters.Cluster(1, "sequence", "comments", 2, 4)), SectionClusters.read("g2-g9 | sequence | comments\n", GROUPS),
                "a run past the last group stops at it");
        assertEquals(List.of(), SectionClusters.read("none", GROUPS));
    }
}

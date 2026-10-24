package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the pairs of cause and motive in a rule file, the question on them, and the links a reply gives. */
class ConceptCausesTest {

    private static final String RULES = """
            (defrule s1 -> b1 b6 :heading "## 1.2.5. Secure")
            (defrule b1 -> :nucleus b1/1 :purpose b1/2 :elaboration-additional b1/3)
            (defrule b1/1 -> "Java is intended to be used in networked environments.")
            (defrule b1/2 -> "Toward that end, emphasis has been placed on security.")
            (defrule b1/3 -> "Something else.")
            (defrule b6 -> :nucleus b6/1 :cause b6/2 :result b6/3)
            (defrule b6/1 -> "Initially, security bugs were fixed quickly.")
            (defrule b6/2 -> "Hackers got good at spotting flaws.")
            (defrule b6/3 -> "Oracle had a tough time keeping up.")
            """;

    @Test
    void thePairsAreTheSatellitesOfCauseAndMotiveWithTheirNucleus() {
        List<ConceptCauses.Pair> p = ConceptCauses.pairs(RULES, Map.of());
        assertEquals(3, p.size());
        assertEquals(new ConceptCauses.Pair("b1", "purpose", "b1/1", "Java is intended to be used in networked environments.", "b1/2",
                "Toward that end, emphasis has been placed on security."), p.get(0));
        assertEquals("b6", p.get(2).block());
        assertEquals("result", p.get(2).relation());
        String q = ConceptCauses.question(new BookOutline.Node("1.2.5", "subsection", "Secure", null, "f.md"), p, Map.of("b1", "reason for security"));
        assertTrue(q.contains("[b1] about reason for security\n  p1: the purpose sentence (\"Toward that end"), q);
        assertTrue(q.contains("[b6]\n  p2: the cause sentence"), q);
        String withBlocks = ConceptCauses.question(new BookOutline.Node("1.2.5", "subsection", "Secure", null, "f.md"), p, Map.of(),
                Map.of("b6", "nucleus: Initially, security bugs were fixed quickly.\ncause: Hackers got good at spotting flaws."));
        assertTrue(withBlocks.contains("[b6]\nnucleus: Initially, security bugs were fixed quickly.\ncause: Hackers got good at spotting flaws.\n  p2:"), withBlocks);
    }

    @Test
    void theReplyGivesALinkPerPairInPairOrder() {
        List<ConceptCauses.Pair> p = ConceptCauses.pairs(RULES, Map.of());
        List<ConceptCauses.Link> l = ConceptCauses.read("p3 | cause | quick fixes | nothing\n**p1** | Motive | use in networks | emphasis on security.\n"
                + "p2 | none\np9 | cause | a | b\n", p, Map.of("b1", 1, "b6", 3));
        assertEquals(List.of(new ConceptCauses.Link(1, "purpose", "motive", "use in networks", "emphasis on security", "b1/1", "b1/2"),
                new ConceptCauses.Link(3, "result", "cause", "quick fixes", "nothing", "b6/1", "b6/3")), l);
    }
}

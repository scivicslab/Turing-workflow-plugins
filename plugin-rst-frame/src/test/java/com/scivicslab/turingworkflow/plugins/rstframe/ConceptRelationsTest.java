package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the relations a unit's axis gives, the pairs that tie two things, and what a reply makes of them. */
class ConceptRelationsTest {

    @Test
    void aSetUnitsMembersAreKindsOfItsConceptAndAStructuresPartsPartsOfIt() {
        var set = new SectionGroups.Group(1, "set", "comment", List.of(new SectionGroups.Member("b1", "(introduction)"),
                new SectionGroups.Member("b2", "end-of-line comment"), new SectionGroups.Member("b3", "block comment")));
        var structure = new SectionGroups.Group(2, "structure", "class", List.of(new SectionGroups.Member("b4", "fields")));
        var sequence = new SectionGroups.Group(3, "sequence", "loop", List.of(new SectionGroups.Member("b5", "syntax")));
        assertEquals(List.of(new ConceptRelations.Relation(1, "unit", "kind of", "end-of-line comment", "comment", "b2"),
                new ConceptRelations.Relation(1, "unit", "kind of", "block comment", "comment", "b3"),
                new ConceptRelations.Relation(2, "unit", "part of", "fields", "class", "b4")),
                ConceptRelations.fromUnits(List.of(set, structure, sequence)), "an introduction is no member; a sequence's steps are no relation");
    }

    @Test
    void thePairsAreAskedWithWhatToNameAndTheReplyMakesRelationsByTheirName() {
        String rules = """
                (defrule s1 -> b1 :heading "## 3.3.1")
                (defrule b1 -> :nucleus b1/1 :comparison b1/2 :elaboration-general-specific b1/3 :cause b1/4)
                (defrule b1/1 -> "The int type is the most practical.")
                (defrule b1/2 -> "The long type is for larger numbers.")
                (defrule b1/3 -> "It is the most practical integer type.")
                (defrule b1/4 -> "Because of speed.")
                """;
        List<ConceptCauses.Pair> pairs = ConceptCauses.pairs(rules, Map.of(), ConceptRelations.KINDS.keySet());
        assertEquals(List.of("comparison", "elaboration-general-specific"), pairs.stream().map(ConceptCauses.Pair::relation).toList(), "cause is step 2's");
        String q = ConceptRelations.question(new BookOutline.Node("3.3.1", "subsection", "Integer Types", null, "f.md"), pairs, Map.of("b1", "integer types"), Map.of());
        assertTrue(q.contains("p1: the comparison sentence (\"The long type is for larger numbers.\") and its nucleus (\"The int type is the most practical.\"); "
                + "name the thing compared, then what it is compared with"), q);
        assertEquals(List.of(new ConceptRelations.Relation(1, "comparison", "compared with", "the int type", "the long type", "b1/2")),
                ConceptRelations.read("p1 | the int type | the long type.\np2 | none\n", pairs, Map.of("b1", 1)));
    }
}

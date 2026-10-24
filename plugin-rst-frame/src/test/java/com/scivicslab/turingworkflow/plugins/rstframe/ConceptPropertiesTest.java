package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure tests: a group's sentences with their relations, the question on them, and the properties a reply gives. */
class ConceptPropertiesTest {

    private static final String RULES = """
            (defrule s1 -> b4 b5 :heading "## 3.4.1")
            (defrule b4 -> :nucleus b4/1 :elaboration-object-attribute b4/2)
            (defrule b4/1 -> "An identifier is a sequence of letters and digits.")
            (defrule b4/2 -> "The first character cannot be a digit.")
            (defrule b5 -> b5/1 b5/2 :kind bullet)
            (defrule b5/1 -> "No symbols like + or @.")
            (defrule b5/2 -> "Case is significant.")
            """;

    @Test
    void theSentencesCarryTheirRelationAndAListsItemsAreItems() {
        List<ConceptProperties.Sentence> s = ConceptProperties.sentences(RULES, List.of("b4", "b5"));
        assertEquals(List.of(new ConceptProperties.Sentence("b4/1", "nucleus", "An identifier is a sequence of letters and digits."),
                new ConceptProperties.Sentence("b4/2", "elaboration-object-attribute", "The first character cannot be a digit."),
                new ConceptProperties.Sentence("b5/1", "item", "No symbols like + or @."),
                new ConceptProperties.Sentence("b5/2", "item", "Case is significant.")), s);
        var g = new SectionGroups.Group(2, "sequence", "identifier", List.of(new SectionGroups.Member("b4", "x"), new SectionGroups.Member("b5", "y")));
        String q = ConceptProperties.question(new BookOutline.Node("3.4.1", "subsection", "Declaring Variables", null, "f.md"), g, s);
        assertTrue(q.contains("[b4/2] (elaboration-object-attribute) The first character cannot be a digit.\n"), q);
        assertTrue(q.contains("Which sentences state a property of identifier itself?"), q);
    }

    @Test
    void theReplyGivesThePropertiesOfTheGroupsSentencesOnly() {
        List<ConceptProperties.Sentence> s = ConceptProperties.sentences(RULES, List.of("b4", "b5"));
        var g = new SectionGroups.Group(2, "sequence", "identifier", List.of(new SectionGroups.Member("b4", "x")));
        assertEquals(List.of(new ConceptProperties.Property(2, "identifier", "is", "a sequence of letters and digits", "b4/1"),
                new ConceptProperties.Property(2, "identifier", "has", "a first character that is not a digit", "b4/2")),
                ConceptProperties.read("b4/1 | is | a sequence of letters and digits.\n**b4/2** | Has | a first character that is not a digit\n"
                        + "b9/1 | has | something elsewhere\n", g, s));
    }
}

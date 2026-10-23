package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A rule's form found by its name, as the rule file writes it. */
class RuleFileFormTest {

    @Test
    void aRulesFormIsFoundByItsNameAsTheFileWritesIt() {
        String text = "(defrule p1 -> :nucleus p1/1 :example p1/2)\n(defrule p1/1 ->\n  \"A string (of chars) is text.\")\n(defrule p1/10 -> \"Ten.\")\n";
        assertEquals("(defrule p1/1 ->\n  \"A string (of chars) is text.\")", RuleFile.formOf(text, "p1/1"));
        assertEquals("(defrule p1/10 -> \"Ten.\")", RuleFile.formOf(text, "p1/10"));
        assertNull(RuleFile.formOf(text, "p2"));
    }
}

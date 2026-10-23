package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class FrameItemsTest {

    private static final List<BookStore.FrameRule> RULES = List.of(
            new BookStore.FrameRule(BookStore.FrameRule.PROPERTY, "b4/1", "The identifier is made up of letters, digits, currency symbols, and punctuation connectors."),
            new BookStore.FrameRule(BookStore.FrameRule.PROPERTY, "b4/2", "The first character cannot be a digit."),
            new BookStore.FrameRule(BookStore.FrameRule.PROPERTY, "b5/2", "Letter case is significant."),
            new BookStore.FrameRule(BookStore.FrameRule.BEHAVIOR, "b8/1", "Tip: Even though $ is valid, you should not use it."));

    @Test
    void theQuestionShowsEverySentenceByItsRule() {
        String q = FrameItems.question(new BookOutline.Node("3.4.1", "subsection", "Declaring Variables", "3.4", "f.md"), "identifier", RULES);
        assertTrue(q.contains("[b4/2] The first character cannot be a digit."), q);
        assertTrue(q.contains("Name the questions about \"identifier\""), q);
    }

    @Test
    void eachItemKeepsTheRulesThatAnswerItAndAnItemNoRuleAnswersIsLeftOut() {
        List<BookStore.FrameItem> items = FrameItems.read("""
                Q1: What does an identifier consist of?
                **Q2:** What may an identifier not begin with?
                Q3: Does letter case matter?
                Q4: What should be avoided?
                Q5: Something no sentence answers
                b4/1 | Q1
                b4/2 | Q2
                [b5/2] | Q3
                b8/1 | Q4
                b9/9 | Q1
                b4/1 | Q1
                """, RULES);
        assertEquals(List.of(new BookStore.FrameItem("What does an identifier consist of?", List.of("b4/1")),
                new BookStore.FrameItem("What may an identifier not begin with?", List.of("b4/2")),
                new BookStore.FrameItem("Does letter case matter?", List.of("b5/2")),
                new BookStore.FrameItem("What should be avoided?", List.of("b8/1"))), items);
    }
}

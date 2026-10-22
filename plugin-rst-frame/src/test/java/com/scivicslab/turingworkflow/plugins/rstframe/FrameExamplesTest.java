package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the source-book paragraphs a blank is shown, found through roles tables and made-from lines. */
class FrameExamplesTest {

    private static final String BOOK_A = "doc_X/docs/A/a1/a1.lisp";
    private static final String BOOK_B = "doc_X/docs/B/b1/b1.lisp";

    private static final Map<String, String> FRAMES = Map.of(
            "subject/a/design.lisp", """
                    ;;; subject: a 23 Design
                    ;;; made-from: doc_X/docs/A/a1 / ch23
                    ;;; members: p-system-what
                    (defrule design -> p-what)
                    (defrule p-what -> :instruction "say what the system is" :sample "FileBuy is a system.")
                    """,
            "subject/b/ipv4.lisp", """
                    ;;; subject: b 4 IPv4
                    ;;; made-from: doc_X/docs/B/b1/b1.lisp
                    ;;;            / ch4
                    (defrule ipv4 -> p-what)
                    (defrule p-what -> :instruction "say what the subsystem is")
                    """,
            "explanation/explain.lisp", """
                    ;;; use: explains one program
                    ;;; integrates: subject/a/design.lisp (A, 23 Design)
                    ;;; integrates: subject/b/ipv4.lisp (B, 4 IPv4)
                    ;;; roles (role: design / ipv4; - = none):
                    ;;;   p-what:  p-system-what / p-what p-functions
                    ;;;   p-prev:  -             / p-prev
                    (defrule explain -> p-what p-prev flows)
                    (defrule p-what -> :nucleus p-what/n)
                    (defrule p-what/n -> :instruction "say what the program is")
                    """,
            "explanation/explain.flow.lisp", """
                    ;;; one flow
                    ;;; roles (role: design / ipv4):
                    ;;;   flow-what: p-flow / -
                    (defrule flow -> flow-what)
                    (defrule flow-what -> :instruction "say what the flow does")
                    """);

    private static final Map<String, String> WORKS = Map.of(
            BOOK_A, """
                    (defrule ch23 -> p-system-what p-flow)
                    (defrule p-system-what -> :nucleus p-system-what/n :elaboration p-system-what/more)
                    (defrule p-system-what/n -> "FileBuy is a file-buying system.")
                    (defrule p-system-what/more -> "It runs on several machines." :heading "## not text")
                    (defrule p-flow -> "A buyer asks every seller.")
                    """,
            BOOK_B, """
                    (defrule ch4 -> p-what p-functions p-prev)
                    (defrule p-what -> "IPv4 is the network layer.")
                    (defrule p-functions -> "It routes packets.")
                    (defrule p-prev -> "The last chapter was about routing.")
                    """);

    private static FrameExamples examples() {
        return new FrameExamples(FRAMES, WORKS::get);
    }

    @Test
    void aBlankOfTheIntegratedFrameGetsTheParagraphsItsRowNames() {
        List<FrameExamples.Example> e = examples().forBlank("explanation/explain.lisp", "p-what/n", 5);
        assertEquals(List.of("a1.lisp / p-system-what", "b1.lisp / p-what", "b1.lisp / p-functions"),
                e.stream().map(FrameExamples.Example::source).toList());
        assertEquals("FileBuy is a file-buying system. It runs on several machines.", e.get(0).text());
    }

    @Test
    void theNumberOfExamplesIsBounded() {
        assertEquals(2, examples().forBlank("explanation/explain.lisp", "p-what/n", 2).size());
    }

    @Test
    void aBlankOfACopiedSubFrameFindsItsPartAmongTheSegmentsOfItsName() {
        List<FrameExamples.Example> e = examples().forBlank("explanation/explain.lisp", "flows/2/flow-what", 5);
        assertEquals(List.of("a1.lisp / p-flow"), e.stream().map(FrameExamples.Example::source).toList());
    }

    @Test
    void aSegmentThatStartsWithAPartNameBelongsToThatPart() {
        assertEquals(List.of("a1.lisp / p-system-what", "b1.lisp / p-what", "b1.lisp / p-functions"),
                examples().forBlank("explanation/explain.lisp", "p-what-definition", 5).stream().map(FrameExamples.Example::source).toList());
    }

    @Test
    void aBlankOfAConcreteFrameGetsTheOtherFramesParagraphsOfTheSameRow() {
        List<FrameExamples.Example> e = examples().forBlank("subject/b/ipv4.lisp", "p-what", 5);
        assertEquals(List.of("a1.lisp / p-system-what"), e.stream().map(FrameExamples.Example::source).toList());
    }

    @Test
    void aRowNamingAConcreteFramesRuleGetsThatRulesSamples() {
        Map<String, String> frames = new java.util.HashMap<>(FRAMES);
        frames.put("explanation/explain-type.lisp", """
                ;;; integrates: subject/a/design.lisp
                ;;; roles (role: design; the head line may end without a colon)
                ;;;   kind:  p-what
                (defrule kind -> :instruction "say the kind")
                """);
        List<FrameExamples.Example> e = new FrameExamples(frames, WORKS::get).forBlank("explanation/explain-type.lisp", "kind", 5);
        assertEquals(List.of("subject/a/design.lisp / p-what"), e.stream().map(FrameExamples.Example::source).toList());
        assertEquals("FileBuy is a system.", e.get(0).text());
    }

    private static final List<String> LINKS = List.of(
            "# a_file\ta_rule\tb_file\tb_rule\tkind\n"
                    + "explanation/explain.lisp\tp-what\tsubject/a/design.lisp\tp-what\tintegrates\n"
                    + "explanation/explain.lisp\tp-what\tsubject/b/ipv4.lisp\tp-what\tintegrates\n",
            "subject/a/design.lisp\tp-what\t" + BOOK_A + "\tp-system-what\tmade-from\n");

    /** As {@link #FRAMES}, with a sample in ipv4's p-what, which has no made-from edge. */
    private static Map<String, String> framesWithIpv4Sample() {
        Map<String, String> f = new java.util.HashMap<>(FRAMES);
        f.put("subject/b/ipv4.lisp", f.get("subject/b/ipv4.lisp").replace(
                ":instruction \"say what the subsystem is\")", ":instruction \"say what the subsystem is\" :sample \"IPv4 is a layer.\")"));
        return f;
    }

    @Test
    void withAnEdgeListAnIntegratedBlankFollowsIntegratesThenMadeFrom() {
        FrameExamples fx = new FrameExamples(framesWithIpv4Sample(), LINKS, WORKS::get);
        List<FrameExamples.Example> e = fx.forBlank("explanation/explain.lisp", "p-what/n", 5);
        assertEquals(List.of("a1.lisp / p-system-what", "subject/b/ipv4.lisp / p-what"), e.stream().map(FrameExamples.Example::source).toList(),
                "design has a made-from edge to the book; ipv4 has none, so its rule's own sample stands in");
        assertEquals("FileBuy is a file-buying system. It runs on several machines.", e.get(0).text());
        assertEquals("IPv4 is a layer.", e.get(1).text());
    }

    @Test
    void withAnEdgeListAConcreteBlankGetsTheOtherConcreteRulesOfItsIntegratedRule() {
        FrameExamples fx = new FrameExamples(framesWithIpv4Sample(), LINKS, WORKS::get);
        assertEquals(List.of("subject/b/ipv4.lisp / p-what"),
                fx.forBlank("subject/a/design.lisp", "p-what", 5).stream().map(FrameExamples.Example::source).toList());
        assertEquals(List.of("a1.lisp / p-system-what"),
                fx.forBlank("subject/b/ipv4.lisp", "p-what", 5).stream().map(FrameExamples.Example::source).toList());
    }

    @Test
    void withAnEdgeListTheRolesTableIsNotRead() {
        FrameExamples fx = new FrameExamples(FRAMES, LINKS, WORKS::get);
        assertTrue(fx.forBlank("explanation/explain.lisp", "p-prev", 5).isEmpty(), "p-prev is in the roles table but has no edge");
    }

    @Test
    void aBlankWithNoRowGetsNothing() {
        assertTrue(examples().forBlank("explanation/explain.lisp", "title", 5).isEmpty());
        assertTrue(examples().forBlank("nowhere.lisp", "p-what", 5).isEmpty());
    }

    @Test
    void theExamplesComeAfterTheModelSentenceInTheQuestion() {
        FrameFiller filler = new FrameFiller();
        String file = FRAMES.get("explanation/explain.lisp");
        FrameFiller.Blank blank = filler.next(file);
        String prompt = filler.sentencePrompt(file, blank, "class Squares {}", List.of(), "English",
                examples().forBlank("explanation/explain.lisp", blank.name(), 3));
        assertTrue(prompt.contains("Paragraphs of other books that play the same part"), prompt);
        assertTrue(prompt.contains("- [b1.lisp / p-what] IPv4 is the network layer.\n"), prompt);
        assertTrue(prompt.indexOf("Paragraphs of other books") < prompt.indexOf("Write only the sentence"));
    }
}

package com.scivicslab.turingworkflow.plugins.semanticscholar;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * E2E runner for {@link SemanticScholarActor} against the live Graph API
 * ({@code https://api.semanticscholar.org/graph/v1}). No key needed; the shared pool is slower and
 * sometimes answers 429, which the client waits out once.
 *
 * Test paper: "Attention Is All You Need" (Vaswani et al., 2017), arXiv:1706.03762
 *
 * Run with:
 *   mvn exec:java -pl plugin-semantic-scholar \
 *     -Dexec.mainClass=com.scivicslab.turingworkflow.plugins.semanticscholar.SemanticScholarActorE2eRunner
 */
public class SemanticScholarActorE2eRunner {

    private static final String TEST_ID = "arXiv:1706.03762";
    /** As the catalogue spells it: "Attention is All you Need", so the check ignores case. */
    private static final String TEST_TITLE = "attention is all you need";

    public static void main(String[] args) {
        SemanticScholarActor actor = new SemanticScholarActor("test-s2", null);
        run_getPaper_returnsTitleAuthorsAndCitationCount(actor);
        run_getReferences_returnsWhatThePaperCites(actor);
        run_getCitations_returnsWhoCitedIt(actor);
        run_searchPapersTopK_honoursTheCount(actor);
        System.out.println("[E2E] All SemanticScholarActor tests PASSED");
    }

    static void run_getPaper_returnsTitleAuthorsAndCitationCount(SemanticScholarActor actor) {
        System.out.println("[E2E] run_getPaper_returnsTitleAuthorsAndCitationCount");

        ActionResult result = actor.getPaper(new SemanticScholarActor.PaperArgs(TEST_ID));

        System.out.println("[E2E] getPaper result:\n" + result.getResult());
        assertTrue(result.isSuccess(), "getPaper must succeed for a known paper");
        assertContains(result.getResult(), TEST_TITLE, "the title");
        assertContains(result.getResult(), "Authors:", "the authors");
        assertContains(result.getResult(), "cited ", "the citation count");
    }

    static void run_getReferences_returnsWhatThePaperCites(SemanticScholarActor actor) {
        System.out.println("[E2E] run_getReferences_returnsWhatThePaperCites");

        ActionResult result = actor.getReferences(new SemanticScholarActor.RelatedArgs(TEST_ID, 3));

        System.out.println("[E2E] getReferences first 500 chars:\n"
                + result.getResult().substring(0, Math.min(500, result.getResult().length())));
        assertTrue(result.isSuccess(), "getReferences must succeed");
        assertContains(result.getResult(), "Papers cited by", "the heading");
        assertContains(result.getResult(), "1. ", "a first reference");
    }

    static void run_getCitations_returnsWhoCitedIt(SemanticScholarActor actor) {
        System.out.println("[E2E] run_getCitations_returnsWhoCitedIt");

        ActionResult result = actor.getCitations(new SemanticScholarActor.RelatedArgs(TEST_ID, 3));

        System.out.println("[E2E] getCitations first 500 chars:\n"
                + result.getResult().substring(0, Math.min(500, result.getResult().length())));
        assertTrue(result.isSuccess(), "getCitations must succeed");
        assertContains(result.getResult(), "Papers citing", "the heading");
    }

    static void run_searchPapersTopK_honoursTheCount(SemanticScholarActor actor) {
        System.out.println("[E2E] run_searchPapersTopK_honoursTheCount");

        ActionResult result = actor.searchPapersTopK(
                new SemanticScholarActor.TopPapersArgs("actor model concurrency", 3, null, "Computer Science"));

        System.out.println("[E2E] searchPapersTopK result:\n" + result.getResult());
        assertTrue(result.isSuccess(), "searchPapersTopK must succeed");
        assertContains(result.getResult(), "showing 3", "three results");
        assertTrue(!result.getResult().contains("4. "), "and no fourth result");
    }

    // -------------------------------------------------------------------------

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError("[E2E] FAILED: " + what);
    }

    private static void assertContains(String haystack, String needle, String what) {
        if (haystack == null
                || !haystack.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT))) {
            throw new AssertionError("[E2E] FAILED: expected " + what + " ('" + needle + "') in:\n" + haystack);
        }
    }
}

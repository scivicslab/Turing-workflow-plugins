package com.scivicslab.turingworkflow.plugins.arxiv;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * E2E runner for {@link ArxivActor} against the live arXiv API
 * ({@code http://export.arxiv.org/api/query}). No key and no local setup.
 *
 * Test paper: "Training language models to follow instructions with human feedback"
 *   arXiv: 2203.02155
 *
 * Run with:
 *   mvn exec:java -pl plugin-arxiv \
 *     -Dexec.mainClass=com.scivicslab.turingworkflow.plugins.arxiv.ArxivActorE2eRunner
 */
public class ArxivActorE2eRunner {

    private static final String TEST_ID = "2203.02155";
    private static final String TEST_TITLE = "Training language models to follow instructions";

    public static void main(String[] args) {
        run_getPaper_byId_returnsTitleAuthorsAndAbstract();
        run_getPdfUrl_returnsArxivPdfLink();
        run_searchPapers_byKeyword_returnsResults();
        run_searchPapersTopK_honoursTheCount();
        run_searchByAuthor_returnsThatAuthorsPapers();
        System.out.println("[E2E] All ArxivActor tests PASSED");
    }

    static void run_getPaper_byId_returnsTitleAuthorsAndAbstract() {
        System.out.println("[E2E] run_getPaper_byId_returnsTitleAuthorsAndAbstract");
        ArxivActor actor = new ArxivActor("test-arxiv", null);

        ActionResult result = actor.getPaper(new ArxivActor.PaperArgs(TEST_ID));

        System.out.println("[E2E] getPaper result:\n" + result.getResult());
        assertTrue(result.isSuccess(), "getPaper must succeed for a known id");
        assertContains(result.getResult(), TEST_TITLE, "the title");
        assertContains(result.getResult(), "Authors:", "the authors");
        assertContains(result.getResult(), "Abstract:", "the abstract");
        assertContains(result.getResult(), "Categories:", "the categories");
    }

    static void run_getPdfUrl_returnsArxivPdfLink() {
        System.out.println("[E2E] run_getPdfUrl_returnsArxivPdfLink");
        ArxivActor actor = new ArxivActor("test-arxiv", null);

        ActionResult result = actor.getPdfUrl(new ArxivActor.PaperArgs("arXiv:" + TEST_ID));

        System.out.println("[E2E] getPdfUrl result: " + result.getResult());
        assertTrue(result.isSuccess(), "getPdfUrl must succeed for a known id");
        assertContains(result.getResult(), "arxiv.org/pdf/", "an arXiv PDF link");
    }

    static void run_searchPapers_byKeyword_returnsResults() {
        System.out.println("[E2E] run_searchPapers_byKeyword_returnsResults");
        ArxivActor actor = new ArxivActor("test-arxiv", null);

        ActionResult result = actor.searchPapers(new ArxivActor.QueryArgs("actor model concurrency"));

        System.out.println("[E2E] searchPapers first 400 chars:\n"
                + result.getResult().substring(0, Math.min(400, result.getResult().length())));
        assertTrue(result.isSuccess(), "searchPapers must succeed");
        assertContains(result.getResult(), "arXiv papers for", "the header");
        assertContains(result.getResult(), "1. ", "a first result");
    }

    static void run_searchPapersTopK_honoursTheCount() {
        System.out.println("[E2E] run_searchPapersTopK_honoursTheCount");
        ArxivActor actor = new ArxivActor("test-arxiv", null);

        ActionResult result = actor.searchPapersTopK(
                new ArxivActor.TopPapersArgs("cat:cs.DC AND abs:actor", 3, "newest"));

        System.out.println("[E2E] searchPapersTopK result:\n" + result.getResult());
        assertTrue(result.isSuccess(), "searchPapersTopK must succeed");
        assertContains(result.getResult(), "showing 3 by submission date", "three, newest first");
        assertTrue(!result.getResult().contains("4. "), "and no fourth result");
    }

    static void run_searchByAuthor_returnsThatAuthorsPapers() {
        System.out.println("[E2E] run_searchByAuthor_returnsThatAuthorsPapers");
        ArxivActor actor = new ArxivActor("test-arxiv", null);

        ActionResult result = actor.searchByAuthor(new ArxivActor.QueryArgs("Joe Armstrong"));

        System.out.println("[E2E] searchByAuthor first 300 chars:\n"
                + result.getResult().substring(0, Math.min(300, result.getResult().length())));
        assertTrue(result.isSuccess(), "searchByAuthor must succeed");
    }

    // -------------------------------------------------------------------------

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError("[E2E] FAILED: " + what);
    }

    private static void assertContains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("[E2E] FAILED: expected " + what + " ('" + needle + "') in:\n" + haystack);
        }
    }
}

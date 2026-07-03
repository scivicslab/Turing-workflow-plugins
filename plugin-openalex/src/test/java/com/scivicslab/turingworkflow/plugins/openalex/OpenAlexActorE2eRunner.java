package com.scivicslab.turingworkflow.plugins.openalex;

import com.scivicslab.pojoactor.core.ActionResult;

/**
 * E2E runner for OpenAlexActor — Phase 1 of the paper-deep-read pipeline.
 *
 * Calls the live OpenAlex REST API (https://api.openalex.org).
 * No local setup required: OpenAlex is always available and requires no authentication.
 *
 * Test paper: "Highly accurate protein structure prediction with AlphaFold" (Jumper et al., 2021)
 *   DOI: 10.1038/s41586-021-03819-2
 *   OA PDF: https://www.nature.com/articles/s41586-021-03819-2.pdf
 *   License: hybrid OA — freely downloadable
 *
 * Run with:
 *   mvn exec:java -pl plugin-openalex \
 *     -Dexec.mainClass=com.scivicslab.turingworkflow.plugins.openalex.OpenAlexActorE2eRunner
 */
public class OpenAlexActorE2eRunner {

    private static final String TEST_DOI    = "10.1038/s41586-021-03819-2";
    private static final String TEST_TITLE  = "AlphaFold";

    public static void main(String[] args) throws Exception {
        run_getWork_byDoi_returnsTitleAndAbstract();
        run_getWork_byDoi_includesJournalAndCitationCount();
        run_getPdfUrl_openAccessPaper_returnsHttpUrl();
        run_searchWorks_byKeyword_returnsResults();
        run_searchWorks_byKeyword_resultsAreSortedByCitationCount();
        System.out.println("[E2E] All OpenAlexActor tests PASSED");
    }

    // -------------------------------------------------------------------------
    // Test cases — Phase 1: OpenAlex metadata and PDF URL
    // -------------------------------------------------------------------------

    static void run_getWork_byDoi_returnsTitleAndAbstract() {
        System.out.println("[E2E] run_getWork_byDoi_returnsTitleAndAbstract");
        OpenAlexActor actor = new OpenAlexActor("test-openalex", null);

        ActionResult result = actor.getWork(TEST_DOI);

        System.out.println("[E2E] getWork result:\n" + result.getResult());
        assertTrue(result.isSuccess(), "getWork must succeed for known DOI");
        assertContains(result.getResult(), TEST_TITLE, "result must contain paper title keyword");
        assertContains(result.getResult(), "Abstract:", "result must include abstract section");
    }

    static void run_getWork_byDoi_includesJournalAndCitationCount() {
        System.out.println("[E2E] run_getWork_byDoi_includesJournalAndCitationCount");
        OpenAlexActor actor = new OpenAlexActor("test-openalex", null);

        ActionResult result = actor.getWork(TEST_DOI);

        assertTrue(result.isSuccess(), "getWork must succeed");
        assertContains(result.getResult(), "cited ", "result must contain citation count");
        assertContains(result.getResult(), "DOI:", "result must contain DOI line");
        assertContains(result.getResult(), "OpenAlex:", "result must contain OpenAlex ID");
    }

    static void run_getPdfUrl_openAccessPaper_returnsHttpUrl() {
        System.out.println("[E2E] run_getPdfUrl_openAccessPaper_returnsHttpUrl");
        OpenAlexActor actor = new OpenAlexActor("test-openalex", null);

        ActionResult result = actor.getPdfUrl(TEST_DOI);

        System.out.println("[E2E] PDF URL: " + result.getResult());
        assertTrue(result.isSuccess(), "getPdfUrl must succeed for open-access arXiv paper");
        assertTrue(result.getResult().startsWith("http"), "PDF URL must start with http");
        assertNotBlank(result.getResult(), "PDF URL must not be blank");
    }

    static void run_searchWorks_byKeyword_returnsResults() {
        System.out.println("[E2E] run_searchWorks_byKeyword_returnsResults");
        OpenAlexActor actor = new OpenAlexActor("test-openalex", null);

        ActionResult result = actor.searchWorks("transformer self-attention neural network");

        System.out.println("[E2E] searchWorks result (first 300 chars):\n"
                + result.getResult().substring(0, Math.min(300, result.getResult().length())));
        assertTrue(result.isSuccess(), "searchWorks must succeed");
        assertContains(result.getResult(), "1.", "result must contain numbered list");
        assertContains(result.getResult(), "cited ", "results must include citation counts");
    }

    static void run_searchWorks_byKeyword_resultsAreSortedByCitationCount() {
        System.out.println("[E2E] run_searchWorks_byKeyword_resultsAreSortedByCitationCount");
        OpenAlexActor actor = new OpenAlexActor("test-openalex", null);

        ActionResult result = actor.searchWorksTopK("{\"query\": \"deep learning\", \"perPage\": 3}");

        assertTrue(result.isSuccess(), "searchWorksTopK must succeed");
        String text = result.getResult();
        // Extract citation counts from lines like "cited N times"
        int firstCite  = extractFirstCitationCount(text);
        int secondCite = extractSecondCitationCount(text);
        System.out.println("[E2E] First result cited " + firstCite
                + " times, second cited " + secondCite + " times");
        assertTrue(firstCite >= secondCite,
                "results must be sorted descending by citation count ("
                + firstCite + " >= " + secondCite + ")");
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("[E2E] FAILED: " + message);
    }

    private static void assertContains(String value, String substring, String message) {
        if (value == null || !value.contains(substring))
            throw new AssertionError("[E2E] FAILED: " + message
                    + " (expected to contain: \"" + substring + "\")");
    }

    private static void assertNotBlank(String value, String message) {
        if (value == null || value.isBlank())
            throw new AssertionError("[E2E] FAILED: " + message);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int extractFirstCitationCount(String text) {
        return extractNthCitationCount(text, 1);
    }

    private static int extractSecondCitationCount(String text) {
        return extractNthCitationCount(text, 2);
    }

    private static int extractNthCitationCount(String text, int n) {
        int found = 0;
        int idx = 0;
        while (idx < text.length()) {
            int pos = text.indexOf("cited ", idx);
            if (pos < 0) break;
            int start = pos + 6;
            int end = text.indexOf(" times", start);
            if (end < 0) { idx = pos + 1; continue; }
            try {
                int count = Integer.parseInt(text.substring(start, end).trim());
                found++;
                if (found == n) return count;
            } catch (NumberFormatException ignored) {}
            idx = pos + 1;
        }
        return 0;
    }
}

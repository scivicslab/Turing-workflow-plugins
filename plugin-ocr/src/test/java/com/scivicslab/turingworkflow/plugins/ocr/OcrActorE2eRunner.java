package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.core.ActionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E2E runner for OcrActor and SectionIteratorActor — Phases 2 and 3 of the
 * paper-deep-read pipeline.
 *
 * Phase 2 — OcrActor:
 *   Downloads a real PDF from arXiv and sends it to the Marker OCR server
 *   at 192.168.5.13:8001. Marker must be running before this test is executed.
 *   Expected duration: 1-5 minutes for a full paper.
 *
 * Phase 3 — Full workflow:
 *   Invokes paper-deep-read.yaml via turing-run.sh. Requires:
 *     - Marker OCR at 192.168.5.13:8001
 *     - MCP Gateway at localhost:8888 with agent chat-ui-39500
 *   Output is written to /tmp/paper-e2e-out/. Expected duration: 10-30 minutes.
 *
 * Test papers:
 *   OCR unit test — "Attention Is All You Need" (Vaswani et al., 2017, arXiv)
 *     PDF: https://arxiv.org/pdf/1706.03762 (freely downloadable, 2.2 MB)
 *   Full workflow — "Highly accurate protein structure prediction with AlphaFold" (Jumper et al., 2021)
 *     DOI: 10.1038/s41586-021-03819-2  (OA, OpenAlex-indexed, PDF 3.6 MB)
 *
 * Run Phases 2 only (fast, no LLM needed):
 *   mvn exec:java -pl plugin-ocr \
 *     -Dexec.mainClass=com.scivicslab.turingworkflow.plugins.ocr.OcrActorE2eRunner \
 *     -Dexec.args="phase2"
 *
 * Run all phases including full workflow:
 *   mvn exec:java -pl plugin-ocr \
 *     -Dexec.mainClass=com.scivicslab.turingworkflow.plugins.ocr.OcrActorE2eRunner
 */
public class OcrActorE2eRunner {

    private static final String TEST_PDF_URL = "https://arxiv.org/pdf/1706.03762";  // Attention Is All You Need (for OCR unit test)
    private static final String TEST_DOI     = "10.1038_s41586-021-03819-2";          // AlphaFold2 filename-safe
    private static final String TEST_DOI_RAW = "10.1038/s41586-021-03819-2";          // AlphaFold2 for OpenAlex + full workflow
    private static final String TEST_TOPIC   = "protein structure prediction";

    private static final String MARKER_URL   = "http://192.168.5.13:8001";
    private static final String MCP_URL      = "http://localhost:8888/mcp/_all";
    private static final String AGENT        = "chat-ui-39500";

    private static final Path WORKS_DIR      = Path.of(System.getProperty("user.home"), "works");
    private static final Path TURING_RUN     = WORKS_DIR.resolve("workflow/turing-run.sh");
    private static final Path WORKFLOW_YAML  = WORKS_DIR.resolve("workflow/paper-deep-read.yaml");
    private static final Path OUTPUT_DIR     = Path.of("/tmp/paper-e2e-out");

    public static void main(String[] args) throws Exception {
        boolean phase2Only = args.length > 0 && "phase2".equals(args[0]);

        // Phase 2: OcrActor and SectionIteratorActor
        run_sectionIterator_extractsHeadingBoundaries();
        run_sectionIterator_getNext_iteratesAllSections();
        run_sectionIterator_mergesShortSections();
        run_markerOcr_withArxivPdf_returnsMarkdown();

        if (!phase2Only) {
            // Phase 3: Full paper-deep-read workflow
            run_fullWorkflow_createsOutputFile();
        }

        System.out.println("[E2E] All OcrActor tests PASSED");
    }

    // -------------------------------------------------------------------------
    // Phase 2a: SectionIteratorActor — no external services needed
    // -------------------------------------------------------------------------

    static void run_sectionIterator_extractsHeadingBoundaries() {
        System.out.println("[E2E] run_sectionIterator_extractsHeadingBoundaries");
        SectionIteratorActor actor = new SectionIteratorActor("test-sections", null);
        actor.setMinChars("10");  // disable merging to test heading split in isolation

        String markdown =
                "## Introduction\n\nThis paper proposes a new model.\n\n"
                + "## Method\n\nWe use self-attention layers.\n\n"
                + "### Sub-section\n\nDetails here.\n\n"
                + "## Results\n\nWe achieve state-of-the-art performance.\n\n"
                + "## Conclusion\n\nFuture work remains.";

        ActionResult result = actor.extractSections(markdown);

        System.out.println("[E2E] extractSections: " + result.getResult());
        assertTrue(result.isSuccess(), "extractSections must succeed");
        assertContains(result.getResult(), "sections extracted", "result must report section count");
        // 4 top-level headings, but sub-section merges with Method if short
        int count = extractSectionCount(result.getResult());
        assertTrue(count >= 4, "must extract at least 4 sections, got " + count);
    }

    static void run_sectionIterator_getNext_iteratesAllSections() {
        System.out.println("[E2E] run_sectionIterator_getNext_iteratesAllSections");
        SectionIteratorActor actor = new SectionIteratorActor("test-sections", null);

        actor.setMinChars("10");

        String markdown =
                "## Section One\n\nContent of section one.\n\n"
                + "## Section Two\n\nContent of section two.\n\n"
                + "## Section Three\n\nContent of section three.\n";

        actor.extractSections(markdown);

        int iterations = 0;
        while (true) {
            ActionResult next = actor.getNext("");
            if (!next.isSuccess()) break;
            iterations++;
            assertNotBlank(next.getResult(), "getNext must return non-blank section content");
            System.out.println("[E2E]   Section " + iterations + ": "
                    + next.getResult().lines().findFirst().orElse("(empty)"));
        }

        System.out.println("[E2E] getNext returned " + iterations + " sections then stopped");
        assertTrue(iterations == 3, "must iterate exactly 3 sections, got " + iterations);
    }

    static void run_sectionIterator_mergesShortSections() {
        System.out.println("[E2E] run_sectionIterator_mergesShortSections");
        SectionIteratorActor actor = new SectionIteratorActor("test-sections", null);

        actor.setMinChars("100");

        String markdown =
                "## Long Section\n\nThis section has enough content to stand on its own. "
                + "It contains many words to exceed the minimum character threshold that was set.\n\n"
                + "## Short\n\nTiny.\n\n"
                + "## Another Long Section\n\nThis section also has enough content to stand alone and must not be merged with the previous section, because it clearly exceeds the minimum character threshold.\n";

        ActionResult result = actor.extractSections(markdown);

        int count = extractSectionCount(result.getResult());
        System.out.println("[E2E] mergesShortSections: " + result.getResult());
        assertTrue(count == 2,
                "short middle section must be merged into previous, resulting in 2 sections, got " + count);
    }

    // -------------------------------------------------------------------------
    // Phase 2b: OcrActor.markerOcr — calls Marker at 192.168.5.13:8001
    // -------------------------------------------------------------------------

    static void run_markerOcr_withArxivPdf_returnsMarkdown() throws Exception {
        System.out.println("[E2E] run_markerOcr_withArxivPdf_returnsMarkdown");
        System.out.println("[E2E]   PDF: " + TEST_PDF_URL);
        System.out.println("[E2E]   Marker: " + MARKER_URL + " — expected duration 1-5 min");

        OcrActor actor = new OcrActor("test-ocr", null);
        actor.setMarkerUrl(MARKER_URL);

        long start = System.currentTimeMillis();
        ActionResult result = actor.markerOcr(TEST_PDF_URL);
        long elapsed = (System.currentTimeMillis() - start) / 1000;

        System.out.println("[E2E]   OCR completed in " + elapsed + "s");
        System.out.println("[E2E]   Output length: " + result.getResult().length() + " chars");
        System.out.println("[E2E]   First 500 chars:\n"
                + result.getResult().substring(0, Math.min(500, result.getResult().length())));

        assertTrue(result.isSuccess(),
                "markerOcr must succeed — check that Marker is running at " + MARKER_URL);
        assertTrue(result.getResult().length() > 1000,
                "OCR output must be substantial (>1000 chars), got " + result.getResult().length());
        assertContains(result.getResult(), "#",
                "Marker output must contain Markdown headings");
        assertContains(result.getResult(), "attention",
                "OCR output must contain the word 'attention' from the paper");

        // Verify the output also splits cleanly into sections
        SectionIteratorActor sectionActor = new SectionIteratorActor("test-sections", null);
        sectionActor.setMinChars("200");
        ActionResult sectionsResult = sectionActor.extractSections(result.getResult());
        assertTrue(sectionsResult.isSuccess(), "extractSections must succeed on Marker output");
        int sectionCount = extractSectionCount(sectionsResult.getResult());
        System.out.println("[E2E]   Extracted " + sectionCount + " sections from OCR output");
        assertTrue(sectionCount >= 3,
                "OCR output must split into at least 3 sections, got " + sectionCount);
    }

    // -------------------------------------------------------------------------
    // Phase 3: Full paper-deep-read.yaml workflow
    // -------------------------------------------------------------------------

    static void run_fullWorkflow_createsOutputFile() throws Exception {
        System.out.println("[E2E] run_fullWorkflow_createsOutputFile");
        System.out.println("[E2E]   DOI: " + TEST_DOI_RAW);
        System.out.println("[E2E]   Topic: " + TEST_TOPIC);
        System.out.println("[E2E]   Output: " + OUTPUT_DIR);
        System.out.println("[E2E]   Agent: " + AGENT + " via " + MCP_URL);
        System.out.println("[E2E]   Expected duration: 10-30 min");

        Files.createDirectories(OUTPUT_DIR);
        Path expectedOutput = OUTPUT_DIR.resolve(TEST_DOI + ".md");
        Files.deleteIfExists(expectedOutput);

        List<String> command = List.of(
                "bash", TURING_RUN.toString(),
                "run",
                "-w", WORKFLOW_YAML.toString(),
                "-P", "doi=" + TEST_DOI,
                "-P", "doi.raw=" + TEST_DOI_RAW,
                "-P", "topic=" + TEST_TOPIC,
                "-P", "doc.dir=" + OUTPUT_DIR,
                "-P", "agent=" + AGENT,
                "-P", "mcp.url=" + MCP_URL
        );

        System.out.println("[E2E]   Command: " + String.join(" ", command));

        Process process = new ProcessBuilder(command)
                .directory(WORKS_DIR.toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        boolean finished = process.waitFor(45, java.util.concurrent.TimeUnit.MINUTES);
        assertTrue(finished, "Workflow must complete within 45 minutes");

        int exitCode = process.exitValue();
        System.out.println("[E2E]   Workflow exit code: " + exitCode);
        assertTrue(exitCode == 0, "Workflow must exit with code 0, got " + exitCode);

        assertTrue(Files.exists(expectedOutput),
                "Output file must exist: " + expectedOutput);

        String content = Files.readString(expectedOutput);
        System.out.println("[E2E]   Output file size: " + content.length() + " chars");
        assertTrue(content.length() > 500,
                "Output file must contain substantial content (>500 chars), got " + content.length());
        assertContains(content, "# Paper Summary",
                "Output must start with Paper Summary heading");
        assertContains(content, "## Final Report",
                "Output must contain Final Report section");
        assertContains(content, TEST_DOI_RAW,
                "Output must contain the paper DOI");

        System.out.println("[E2E]   Output file content (first 800 chars):\n"
                + content.substring(0, Math.min(800, content.length())));
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

    private static int extractSectionCount(String result) {
        // Result format: "N sections extracted"
        try {
            return Integer.parseInt(result.trim().split(" ")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Actor that reads OCR TSV files and provides page text one page at a time.
 *
 * <p>OCR TSV format: hiragana TAB kanji TAB page (hiragana column is always empty).</p>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code loadFile} - Load a single OCR TSV file</li>
 *   <li>{@code nextPage} - Advance to next page; returns fail when exhausted</li>
 *   <li>{@code getPageText} - Get current page OCR text (newline-joined fragments)</li>
 *   <li>{@code getPageInfo} - Get current page number and source filename</li>
 * </ul>
 */
public class OcrActor extends IIActorRef<OcrActor> {

    private static final Logger logger = Logger.getLogger(OcrActor.class.getName());

    private String sourceFile = "";
    private List<Integer> pageOrder = new ArrayList<>();
    private Map<Integer, List<String>> pages = new TreeMap<>();
    private int currentPageIndex = -1;
    private int currentPageNum = -1;

    public OcrActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Load an OCR TSV file. Groups kanji-column text by page number.
     */
    @Action("loadFile")
    public ActionResult loadFile(String filePath) {
        filePath = parseFirstArgument(filePath);
        if (filePath.isBlank()) {
            return new ActionResult(false, "File path is required");
        }
        Path path = Path.of(filePath);

        pages.clear();
        pageOrder.clear();
        currentPageIndex = -1;
        currentPageNum = -1;
        sourceFile = path.getFileName().toString();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // skip header
                String[] cols = line.split("\t", -1);
                if (cols.length < 3) continue;
                String kanji = cols[1].strip();
                String pageStr = cols[2].strip();
                if (kanji.isEmpty() || !pageStr.matches("\\d+")) continue;
                int pageNum = Integer.parseInt(pageStr);
                pages.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(kanji);
            }
        } catch (IOException e) {
            return new ActionResult(false, "Failed to read file: " + e.getMessage());
        }

        pageOrder = new ArrayList<>(pages.keySet());
        logger.info("Loaded " + sourceFile + ": " + pageOrder.size() + " pages");
        return new ActionResult(true, "Loaded " + pageOrder.size() + " pages from " + sourceFile);
    }

    /**
     * Advance to the next page. Returns failure (false) when all pages are exhausted.
     * This causes the workflow to try the next row (e.g., transition to end state).
     */
    @Action("nextPage")
    public ActionResult nextPage(String args) {
        currentPageIndex++;
        if (currentPageIndex >= pageOrder.size()) {
            return new ActionResult(false, "No more pages");
        }
        currentPageNum = pageOrder.get(currentPageIndex);
        int lineCount = pages.get(currentPageNum).size();
        logger.info("Advancing to page " + currentPageNum + " (" + lineCount + " lines)");
        return new ActionResult(true, "Page " + currentPageNum);
    }

    /**
     * Get the OCR text of the current page (fragments joined by newlines).
     */
    @Action("getPageText")
    public ActionResult getPageText(String args) {
        if (currentPageNum < 0) {
            return new ActionResult(false, "No page loaded. Call nextPage first.");
        }
        List<String> lines = pages.get(currentPageNum);
        if (lines == null || lines.isEmpty()) {
            return new ActionResult(false, "Page " + currentPageNum + " has no content");
        }
        String text = String.join("\n", lines);
        return new ActionResult(true, text);
    }

    /**
     * Get metadata about the current page: "pageNum\tsourceFile".
     */
    @Action("getPageInfo")
    public ActionResult getPageInfo(String args) {
        if (currentPageNum < 0) {
            return new ActionResult(false, "No page loaded");
        }
        return new ActionResult(true, currentPageNum + "\t" + sourceFile);
    }

}

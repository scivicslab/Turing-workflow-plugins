package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.ActionResult;

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
 * Reads OCR TSV files and yields the kanji text of one page at a time. The TSV format must contain a hiragana column
 * (which is ignored), a kanji column, and a page number column.
 *
 * <ul>
 * <li>{@code loadFile} - Loads a single OCR TSV file and resets the current page position.</li>
 * <li>{@code nextPage} - Advances to the next available page; returns a failure if no more pages exist.</li>
 * <li>{@code getPageText} - Returns the kanji text of the current page as a single string with fragments joined by
 * newlines.</li>
 * <li>{@code getPageInfo} - Returns the current page number and the source filename as a tab-separated string.</li>
 * </ul>
 *
 * <p>The object holds the loaded pages and the current position in the file. A new file must be loaded via {@code
 * loadFile} before {@code nextPage} or other actions can succeed.</p>
 *
 * <p>One instance serves one workflow at a time, as the state of the current page is held within the instance.</p>
 *
 * <p>Example workflow usage:</p>
 * <pre>{@code
 * actor: ocr_pages
 * method: loadFile
 * arguments:
 *   filePath: "data/book.tsv"
 * ---
 * actor: ocr_pages
 * method: nextPage
 * ---
 * actor: ocr_pages
 * method: getPageText
 * }</pre>
 *
 * <p>If {@code loadFile} fails due to an invalid path or an {@code IOException}, the action result is a failure. If
 * {@code nextPage} is called when no pages remain, or if {@code getPageText} or {@code getPageInfo} are called before
 * any page is loaded, the actions return a failure.</p>
 */
public class OcrPages {

    private static final Logger logger = Logger.getLogger(OcrPages.class.getName());

    private String sourceFile = "";
    private List<Integer> pageOrder = new ArrayList<>();
    private Map<Integer, List<String>> pages = new TreeMap<>();
    private int currentPageIndex = -1;
    private int currentPageNum = -1;

    /**
     * Loads an OCR TSV file and groups the kanji-column text by page number.
     *
     * @param filePath the path to the TSV file to be loaded
     * @return a success result with a message containing the number of pages loaded if the file was read successfully,
     * or a failure result with an error message if the path is blank or the file cannot be read
     */
    public ActionResult loadFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
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
     * Advances to the next page in the loaded file.
     *
     * Returns a success result with a message containing the new page number if a next page exists; otherwise, returns
     * a failure result with the message "No more pages" if all pages have been exhausted.
     *
     * @return an action result that is a success with the new page number as its message, or a failure if no more
     * pages are available
     */
    public ActionResult nextPage() {
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
     * Returns the OCR text of the current page as a single string with fragments joined by newlines.
     *
     * <p>A failure is returned if no page has been loaded via {@code nextPage} or if the current page contains no
     * text.</p>
     *
     * @return an action result that is a success with the newline-joined text as its message, or a failure if the page
     * is not loaded or is empty.
     */
    public ActionResult getPageText() {
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
     * Returns a string containing the current page number and the source filename separated by a tab.
     *
     * @return an action result that is a success containing the page number and filename as "pageNum\tsourceFile" if a
     * page is loaded, or a failure if no page has been loaded via {@code nextPage}.
     */
    public ActionResult getPageInfo() {
        if (currentPageNum < 0) {
            return new ActionResult(false, "No page loaded");
        }
        return new ActionResult(true, currentPageNum + "\t" + sourceFile);
    }

}

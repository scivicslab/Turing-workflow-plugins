package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.ActionResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Validates LLM output and writes kana-kanji pairs to a TSV file.
 *
 * <ul>
 * <li>{@code openOutput} - Opens the output TSV file, appending to it if it exists or creating it with a header if it
 * does not.</li>
 * <li>{@code setPageInfo} - Sets the page number and source filename to be attached to subsequent writes.</li>
 * <li>{@code checkHiragana} - Verifies that tab-separated lines containing Japanese characters include a hiragana
 * reading in the first column.</li>
 * <li>{@code writePairs} - Writes the validated tab-separated pairs to the file, appending the current page and source
 * metadata.</li>
 * <li>{@code closeOutput} - Flushes and closes the output file.</li>
 * </ul>
 *
 * <p>The instance holds an open file handle and the current page/source metadata between calls.</p>
 *
 * <p>One instance may serve several workflows at once.</p>
 *
 * <p>An action fails if the file cannot be accessed, if the response is empty, if no tab-separated lines are found, or
 * if the hiragana validation fails.</p>
 */
public class PairWriter {

    private static final Logger logger = Logger.getLogger(PairWriter.class.getName());

    private BufferedWriter writer = null;
    private String currentPage = "";
    private String currentSource = "";
    private int totalPairs = 0;

    /**
     * Opens the output TSV file for writing.
     *
     * Returns a success result if the file was opened or created, with a message containing the file path; returns a
     * failure result if the filePath is null, blank, or if an I/O error occurs. If the file already exists, the new
     * content is appended to the end of the file. If the file is being created for the first time, a header row of
     * hiragana, kanji, page, and source is written.
     *
     * @param filePath the path to the file to be opened or created
     */
    public ActionResult openOutput(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return new ActionResult(false, "Output file path is required");
        }
        Path path = Path.of(filePath);
        boolean exists = Files.exists(path);
        try {
            writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!exists) {
                writer.write("hiragana\tkanji\tpage\tsource\n");
                writer.flush();
            }
            return new ActionResult(true, "Output opened: " + filePath);
        } catch (IOException e) {
            return new ActionResult(false, "Failed to open output: " + e.getMessage());
        }
    }

    /**
     * Sets the page number and source filename to be used for subsequent {@code writePairs} calls.
     *
     * Returns a success result if the input is non-blank, with a message containing the set page and source; otherwise
     * returns a failure result.
     *
     * If the input contains a tab, the part before the tab is the page number and the part after is the source; if no
     * tab is present, the entire input is treated as the page number and the source is set to an empty string.
     *
     * @param pageInfo a string containing the page number and source filename, separated by a tab (e.g.,
     * "12\tbook.md")
     */
    public ActionResult setPageInfo(String pageInfo) {
        String info = pageInfo == null ? "" : pageInfo;
        if (info.isBlank()) {
            return new ActionResult(false, "Page info is required");
        }
        String[] parts = info.split("\t", 2);
        if (parts.length == 2) {
            currentPage = parts[0];
            currentSource = parts[1];
        } else {
            currentPage = info;
            currentSource = "";
        }
        return new ActionResult(true, "Page info set: page=" + currentPage + " source=" + currentSource);
    }

    /**
     * Validates that a text response contains tab-separated lines where Japanese characters are accompanied by
     * hiragana readings.
     *
     * Returns a success result with a message containing the count of lines processed if the validation passes;
     * returns a failure result if the response is blank, contains no tab-separated lines, or if any line containing
     * Japanese characters lacks a corresponding hiragana reading in the first column.
     *
     * @param response the text containing the potential kana-kanji pairs to validate
     */
    public ActionResult checkHiragana(String response) {
        String text = response == null ? "" : response;
        if (text.isBlank()) {
            return new ActionResult(false, "Empty response");
        }
        List<String> lines = text.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && l.contains("\t"))
                .toList();

        if (lines.isEmpty()) {
            return new ActionResult(false, "No tab-separated lines found");
        }

        // Only require hiragana on lines whose kanji column contains Japanese characters.
        // Lines with purely ASCII/numeric content (formulas, English terms) need no reading.
        int japaneseLines = 0;
        int missingHiragana = 0;
        for (String line : lines) {
            String[] parts = line.split("\t", 2);
            String kanji = parts.length > 1 ? parts[1] : parts[0];
            if (containsJapanese(kanji)) {
                japaneseLines++;
                if (!containsHiragana(parts[0])) {
                    missingHiragana++;
                }
            }
        }

        if (japaneseLines > 0 && missingHiragana == japaneseLines) {
            // Every Japanese line is missing hiragana — LLM failed to produce readings
            logger.warning("Hiragana check failed: 0/" + japaneseLines + " Japanese lines have hiragana");
            return new ActionResult(false, "No hiragana readings produced for " + japaneseLines + " Japanese lines");
        }

        logger.info("Hiragana check passed: " + (japaneseLines - missingHiragana) + "/" + japaneseLines
                + " Japanese lines have hiragana, " + lines.size() + " total lines");
        return new ActionResult(true, String.valueOf(lines.size()));
    }

    /**
     * Writes tab-separated kana-kanji pairs from a text response to the open output file, appending the current page
     * and source metadata to each line.
     *
     * Returns a success result with a message containing the number of lines written if the response is non-empty and
     * the output file is open; otherwise returns a failure result. Lines in the response that do not contain a tab are
     * skipped.
     *
     * If the output file is not open, returns a failure result.
     *
     * @param response the text containing tab-separated pairs to be written
     * @return a success result with the count of written lines as the message, or a failure result if the output is
     * not open, the response is blank, or an I/O error occurs
     */
    public ActionResult writePairs(String response) {
        if (writer == null) {
            return new ActionResult(false, "Output not open. Call openOutput first.");
        }
        String text = response == null ? "" : response.trim();
        if (text.isBlank()) {
            return new ActionResult(false, "Empty response");
        }

        List<String> written = new ArrayList<>();
        for (String line : text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
            if (!line.contains("\t")) continue;
            String[] parts = line.split("\t", 2);
            try {
                writer.write(parts[0] + "\t" + parts[1] + "\t" + currentPage + "\t" + currentSource + "\n");
                written.add(line);
            } catch (IOException e) {
                return new ActionResult(false, "Write failed: " + e.getMessage());
            }
        }

        try {
            writer.flush();
        } catch (IOException e) {
            return new ActionResult(false, "Flush failed: " + e.getMessage());
        }

        totalPairs += written.size();
        logger.info("Wrote " + written.size() + " pairs (total: " + totalPairs + ")");
        return new ActionResult(true, String.valueOf(written.size()));
    }

    /**
     * Closes the output TSV file and flushes any remaining data.
     *
     * Returns a success result with a message containing the total number of pairs written if the file is closed
     * successfully, or a failure result if an error occurs during closing. If no output file is currently open, it
     * returns a success result.
     *
     * @return an action result that is a success and contains the total number of pairs written as a message, or is
     * not a success if an error occurs during the close operation.
     */
    public ActionResult closeOutput() {
        if (writer == null) {
            return new ActionResult(true, "Output already closed");
        }
        try {
            writer.close();
            writer = null;
            return new ActionResult(true, "Output closed. Total pairs written: " + totalPairs);
        } catch (IOException e) {
            return new ActionResult(false, "Close failed: " + e.getMessage());
        }
    }

    private static boolean containsJapanese(String text) {
        for (char c : text.toCharArray()) {
            if ((c >= '\u3041' && c <= '\u3096')
                    || (c >= '\u30A1' && c <= '\u30F6')
                    || (c >= '\u4E00' && c <= '\u9FFF')
                    || (c >= '\u3400' && c <= '\u4DBF')) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHiragana(String text) {
        for (char c : text.toCharArray()) {
            if (c >= '\u3041' && c <= '\u3096') return true;
        }
        return false;
    }

}

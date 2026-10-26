package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.action.ActionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Splits Markdown text into sections based on headings and iterates through them one at a time.
 *
 * <ul>
 * <li>{@code extractSections} — parses the provided Markdown text into a list of sections, returning a success result
 * with the count of sections extracted</li>
 * <li>{@code getNext} — returns the next section as a string, or a failure result if no sections remain</li>
 * <li>{@code setMinChars} — sets the minimum character length for a section before it is merged into the preceding
 * one, returning a success result</li>
 * <li>{@code reset} — rewinds the iterator to the first section without re-parsing the text</li>
 * </ul>
 *
 * <p>Sections are split on Markdown headings (lines starting with {@code #}). Sections shorter than {@code minChars}
 * are merged into the previous section to avoid sending tiny fragments to an LLM.</p>
 *
 * <p>An instance holds the list of extracted sections and the current cursor position; it may serve several workflows
 * if the same instance is shared as an actor.</p>
 *
 * <pre>{@code
 * - states: ["N", "section-loop"]
 *   actions:
 *     - actor: sections
 *       method: extractSections
 *       arguments: "$(str:full_markdown.get)"
 *
 * - states: ["section-loop", "next"]
 *   actions:
 *     - actor: sections
 *       method: getNext}
 * </pre>
 *
 * <p>If {@code extractSections} is called with null or blank text, it returns a failure result. {@code getNext}
 * returns a failure result when the cursor reaches the end of the section list.</p>
 */
public class SectionIterator {

    private static final Logger logger = Logger.getLogger(SectionIterator.class.getName());
    private static final int DEFAULT_MIN_CHARS = 100;

    private final List<String> sections = new ArrayList<>();
    private int cursor = 0;
    private int minChars = DEFAULT_MIN_CHARS;

    /**
     * Splits the Markdown text into sections based on headings and resets the iterator.
     *
     * <p>Sections shorter than {@code minChars} are merged into the preceding section to avoid trivial content. If the
     * input is blank or null, the action fails.</p>
     *
     * @param markdown the Markdown text to be parsed
     * @return a success result with a message containing the number of sections extracted, or a failure result if the
     * input is blank
     */
    public ActionResult extractSections(String markdown) {
        markdown = markdown == null ? "" : markdown.trim();
        if (markdown.isBlank()) return new ActionResult(false, "Markdown text is required");

        sections.clear();
        cursor = 0;

        String[] lines = markdown.split("\n", -1);
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (line.matches("^#{1,4} .*") && current.length() > 0) {
                // Flush current section before starting a new one
                String sec = current.toString().strip();
                if (!sec.isBlank()) sections.add(sec);
                current.setLength(0);
            }
            current.append(line).append("\n");
        }
        // Flush last section
        String last = current.toString().strip();
        if (!last.isBlank()) sections.add(last);

        // Merge short sections into the previous one
        List<String> merged = new ArrayList<>();
        for (String sec : sections) {
            if (!merged.isEmpty() && sec.length() < minChars) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + "\n\n" + sec);
            } else {
                merged.add(sec);
            }
        }
        sections.clear();
        sections.addAll(merged);

        logger.info("SectionIterator: extracted " + sections.size() + " sections");
        return new ActionResult(true, sections.size() + " sections extracted");
    }

    /**
     * Returns the next section of Markdown text and advances the cursor.
     *
     * <p>Returns a success result containing the section text as its message, or a failure result with the message "No
     * more sections" if no sections remain. A failure result causes a {@code paraloop} state machine to exit the
     * loop.</p>
     *
     * @return a success result with the section text as the message, or a failure result if no more sections are
     * available
     */
    public ActionResult getNext() {
        if (cursor >= sections.size()) {
            return new ActionResult(false, "No more sections");
        }
        String section = sections.get(cursor++);
        logger.fine("SectionIterator: returning section " + cursor + "/" + sections.size());
        return new ActionResult(true, section);
    }

    /**
     * Sets the minimum character count for a section before it is merged into the preceding section.
     *
     * @param n the minimum character count in characters; must be non-negative
     * @return a success result if the value was updated, or a failure result if n is negative
     */
    public ActionResult setMinChars(int n) {
        if (n < 0) return new ActionResult(false, "minChars must be non-negative");
        this.minChars = n;
        return new ActionResult(true, "minChars set to " + n);
    }

    /**
     * Rewinds the iterator to the first section without re-parsing the Markdown.
     *
     * @return an action result that is a success with a message stating the new position relative to the total number
     * of sections, or a failure if the iterator is empty.
     */
    public ActionResult reset() {
        cursor = 0;
        return new ActionResult(true, "Reset to section 0/" + sections.size());
    }
}

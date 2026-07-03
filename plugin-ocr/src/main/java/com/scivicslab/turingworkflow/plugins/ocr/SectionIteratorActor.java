package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Actor that splits Markdown text into sections and iterates them one at a time.
 *
 * <p>Designed for the {@code paraloop} pattern in Turing workflow YAML:</p>
 * <pre>
 * - states: ["N", "section-loop"]
 *   actions:
 *     - actor: sections
 *       method: extractSections
 *       arguments: "$(str:full_markdown.get)"
 *
 * - states: ["section-loop", "next"]      # succeeds while sections remain
 *   actions:
 *     - actor: sections
 *       method: getNext
 *
 * - states: ["section-loop", "done"]      # fires when getNext has no more items
 *   actions:
 *     - actor: interpreter
 *       method: doNothing
 *
 * - states: ["next", "section-loop"]
 *   actions:
 *     - actor: ... process ${result} ...
 * </pre>
 *
 * <p>Sections are split on Markdown headings (lines starting with {@code #}).
 * Each section includes its heading line and all body text until the next heading.
 * Sections shorter than {@code minChars} (default 100) are merged into the
 * previous section to avoid sending tiny fragments to the LLM.</p>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code extractSections} — load Markdown text and split into sections;
 *       returns "{count} sections extracted"</li>
 *   <li>{@code getNext}         — return the next section; fails (returns false)
 *       when all sections have been returned</li>
 *   <li>{@code setMinChars}     — set the minimum section length before merging
 *       (default 100)</li>
 *   <li>{@code reset}           — rewind the iterator to the first section</li>
 * </ul>
 */
public class SectionIteratorActor extends IIActorRef<SectionIteratorActor> {

    private static final Logger logger = Logger.getLogger(SectionIteratorActor.class.getName());
    private static final int DEFAULT_MIN_CHARS = 100;

    private final List<String> sections = new ArrayList<>();
    private int cursor = 0;
    private int minChars = DEFAULT_MIN_CHARS;

    public SectionIteratorActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Splits the Markdown text into sections and resets the iterator.
     *
     * <p>Sections shorter than {@code minChars} are merged into the preceding
     * section so the LLM is never called with trivial content.</p>
     *
     * @param args Markdown text (the full OCR output)
     * @return "{count} sections extracted"
     */
    @Action("extractSections")
    public ActionResult extractSections(String args) {
        String markdown = args == null ? "" : args.trim();
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
     * Returns the next section and advances the cursor.
     *
     * <p>Returns {@code ActionResult(false, ...)} when all sections have been
     * returned, which causes the {@code paraloop} state machine to exit the loop.</p>
     *
     * @param args unused
     */
    @Action("getNext")
    public ActionResult getNext(String args) {
        if (cursor >= sections.size()) {
            return new ActionResult(false, "No more sections");
        }
        String section = sections.get(cursor++);
        logger.fine("SectionIterator: returning section " + cursor + "/" + sections.size());
        return new ActionResult(true, section);
    }

    /**
     * Sets the minimum character count for a section before it is merged
     * into the preceding section. Default is 100.
     *
     * @param args minimum character count as a string
     */
    @Action("setMinChars")
    public ActionResult setMinChars(String args) {
        try {
            int n = Integer.parseInt(parseFirstArgument(args).trim());
            if (n < 0) return new ActionResult(false, "minChars must be non-negative");
            this.minChars = n;
            return new ActionResult(true, "minChars set to " + n);
        } catch (NumberFormatException e) {
            return new ActionResult(false, "Invalid number: " + args);
        }
    }

    /**
     * Rewinds the iterator to the first section without re-parsing the Markdown.
     *
     * @param args unused
     */
    @Action("reset")
    public ActionResult reset(String args) {
        cursor = 0;
        return new ActionResult(true, "Reset to section 0/" + sections.size());
    }
}
